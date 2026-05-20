# Gemma 4 on Android — Integration Guide

How this app actually runs Gemma 4 E2B on-device for audio transcription,
translation, post-processing, and prompt-based diarization. Written for
engineers who need to understand the pipeline end-to-end before they
change it, or who want to port the same approach to a different app.

Codebase reference: every claim below cites the file + symbol in
`app/src/main/kotlin/nl/ihnatov/transcriber/`. If a claim and the code
disagree, the code is right — open a ticket against this doc.

---

## 1. The shape of the integration in one paragraph

We load Gemma 4 E2B's `.litertlm` weights via Google's **LiteRT-LM
Android SDK v0.11.0** (`com.google.ai.edge.litertlm:litertlm-android`).
A single long-lived `Engine` handle is held inside
[Gemma4Backend.kt](../app/src/main/kotlin/nl/ihnatov/transcriber/asr/Gemma4Backend.kt),
protected by a `Mutex`. For each audio chunk (up to ~28 seconds of
16 kHz mono PCM), we create a fresh `Conversation` with a system
instruction + sampler config, then stream the response via
`Conversation.sendMessageAsync(...).collect { … }`. Long-form audio is
sliced into VAD-aligned chunks with a 1 sec recap overlap; we feed the
previous chunk's tail text as a `[CONTEXT … END CONTEXT]` block so
named entities and speaker numbering carry across boundaries. Output
goes through a two-pass scrubber that strips Gemma's well-known
prompt-leak patterns before being parsed into `Speaker N:`-prefixed
segments. Diarization piggybacks on a sherpa-onnx pre-pass; the cluster
results feed Gemma as inline hints and reconcile its labels at write
time. Cancellation is real (the Flow form supports it via
`Conversation.cancelProcess()`), and per-chunk timeouts release +
rebuild the engine and retry once before failing the run.

That paragraph is the spec; the rest of this doc is detail.

---

## 2. Why Gemma 4 (and not something else)

Five things tipped the model selection:

1. **Multimodal in one weight set** — same model handles transcription,
   translation, and post-processing prompts (Summary, Clean,
   Translate-polish). No second download for translate, no separate
   text-LLM for cleanup.
2. **Multilingual quality on dialectal Arabic** — Whisper large-v3 is
   strong on MSA but folds Gulf/Qatari Arabic into MSA. Gemma 4 holds
   the dialect when the prompt names it explicitly ("Gulf Arabic,
   Qatari"). This is the single biggest reason we shipped Gemma over
   Whisper as the default.
3. **Apache 2.0 weights** — no HF token gating, no terms-of-use click-
   through. Users can install offline (the on-device experience matters).
4. **LiteRT-LM, not MediaPipe `tasks-genai`** — the `.litertlm` audio
   weights only load through LiteRT-LM. MediaPipe's older `.task`
   format crashes inside `LlmInferenceEngine_CreateEngine` on these
   weights (it probes a Qualcomm `libpenguin.so` we don't ship).
   Google's own Android docs recommend migrating to LiteRT-LM for
   newer multimodal models.
5. **E2B fits the device target** — 676 MB GPU footprint on a
   Snapdragon 8 Gen 4-class device, 2.59 GB on disk. E4B doubles RAM
   on CPU (3.28 GB) and is borderline on a 12 GB phone with the user's
   normal background apps running. E2B is the right knob for the
   "small flagship" target.

Whisper.cpp is still in the binary as the alternate backend
([WhisperCppBackend.kt](../app/src/main/kotlin/nl/ihnatov/transcriber/asr/WhisperCppBackend.kt)),
selectable from the Detail screen's RUN sheet. Different tradeoffs:
faster cold start, predictable per-chunk latency, weaker on dialect.

---

## 3. SDK + dependency wiring

`gradle/libs.versions.toml`:

```toml
litertlm = "0.11.0"
...
litertlm-android = {
    module = "com.google.ai.edge.litertlm:litertlm-android",
    version.ref = "litertlm"
}
```

`app/build.gradle.kts`:

```kotlin
implementation(libs.litertlm.android)
```

The artifact lives on **Google Maven** (already in
`settings.gradle.kts` via `google()`), not Maven Central. Don't move
this dep — `tasks-genai` is the older runtime and silently crashes on
`.litertlm` audio weights.

### Version-pin discipline

LiteRT-LM ships every ~4–6 weeks and the audio path is the youngest
surface — APIs move. When bumping, do the migration in a branch and
run a 5-minute multi-chunk recording end-to-end before merging.
Specific things that have moved historically:

- `SamplerConfig` constructor — the `seed` positional arg appeared in
  0.10.x, was dropped from the documented public API in 0.11.0. We
  pass only `topK / topP / temperature` as **named arguments** to be
  bump-safe. See [Gemma4Backend.kt:283](../app/src/main/kotlin/nl/ihnatov/transcriber/asr/Gemma4Backend.kt).
- `Conversation.sendMessageAsync` Flow form arrived in 0.10. Before
  that we'd have to use the blocking `sendMessage` + a watchdog.
- `Backend.NPU(nativeLibraryDir)` is mentioned in 0.11.0 release notes
  but we haven't tested it; current code uses `GPU()`/`CPU()` only.

---

## 4. Engine lifecycle

State diagram for the singleton `Engine` instance held by `Gemma4Backend`:

```
                  ┌──────────────────────────────────┐
                  │              null                │  Idle: no model loaded
                  └──────────────┬───────────────────┘
                                 │ load(modelPath)
                                 ▼
              ┌──────────────────────────────────────┐
              │  try GPU → success → ready (GPU)     │
              │  try GPU → fail → try CPU → ready    │
              │  try CPU → fail → Result.failure(…)  │
              └──────┬───────────────────────────────┘
                     │ ready
                     ▼
        ┌───────────────────────────────────┐
        │  ready (Engine handle alive)      │  ←─ many transcribeChunk()
        │  • mutex.withLock per chunk       │     reuses the engine
        │  • createConversation() per chunk │
        │  • conv.sendMessageAsync.collect  │
        └────────────┬──────────────────────┘
                     │ release()  /  load(otherModelPath)  /  timeout
                     ▼
              engine.close()  →  null
```

Key invariants:

- **Exactly one Engine handle per process**, held inside `Gemma4Backend`.
  Sherpa-onnx, Whisper.cpp, etc. have their own runtimes and don't
  count.
- **The Mutex serializes chunk inference.** Two callers (LiveTranscriber
  during recording + TranscriptionRunner doing a file pass) racing on
  the same Engine would corrupt its state — the Mutex makes that
  invisible to the calling coroutine.
- **GPU→CPU fallback only happens during `load()`**, never mid-run.
  Once an engine is up on CPU, it stays on CPU until released.
- **`audioBackend` is pinned to CPU**, even when the text path runs on
  GPU. The Gemma 4 audio encoder constrains itself to CPU; setting
  `audioBackend = Backend.GPU()` throws "Audio backend constraint
  mismatch. Model requires one of [cpu] but Audio backend is GPU" at
  engine init. This is still true in 0.11.0 — LiteRT-LM issue #1848
  was closed as not planned. Don't try to "fix" it.
- **Lazy load.** The Engine isn't constructed at app start; first
  `transcribeChunk` triggers `load()`. Subsequent calls reuse.
  Detail screens and the Live recorder both hit the same singleton.

---

## 5. EngineConfig — what we set and why

```kotlin
EngineConfig(
    modelPath = modelPath,                  // absolute path to .litertlm
    backend = Backend.GPU() or Backend.CPU(threads),
    audioBackend = Backend.CPU(threads),    // forced — see §4
    maxNumTokens = 8192,                    // user-tunable in Settings
)
```

- **`maxNumTokens` defaults to 8192.** The SDK default is much lower
  (~512). 28 sec of audio = ~700 audio tokens (Gemma 4 at 25 tok/sec),
  the system instruction + user message + speaker hints add a few
  hundred more, and the output for a fast speaker can easily hit
  300–500 tokens. 8192 leaves headroom for the "Context-aware rewrite"
  preset that feeds the whole transcript in one call. Users can bump
  to 16K or 32K via Settings → Gemma 4 compute when working with
  hour-long inputs; each bump roughly doubles peak KV-cache RAM.
- **`backend`** comes from `GemmaSettingsStore.backend`. Three choices:
  `Auto` (try GPU, fall back to CPU; default), `Gpu` (GPU only, fail
  if no GPU support), `Cpu` (skip GPU entirely — useful when the user
  wants predictable latency or hits a GPU compatibility bug).
- **CPU thread count.** Defaults to 0 = "let the SDK decide" (typically
  ~half the cores). User-overridable to 2/4/6/8. Going above physical
  core count consistently hurts.

---

## 6. The per-chunk inference loop

This is the part that does the actual ASR work. Lives in
[Gemma4Backend.transcribeChunk()](../app/src/main/kotlin/nl/ihnatov/transcriber/asr/Gemma4Backend.kt)
and `runOneChunkLocked()`. One call per audio chunk:

```kotlin
suspend fun transcribeChunk(
    chunkSamples: FloatArray,           // 16 kHz mono float in [-1, 1]
    sampleRate: Int,                    // always 16000 in practice
    languages: List<String>,            // [] = auto, [x] = force x, [x,y] = constrained auto
    translateTo: String?,               // null = transcribe; "en"/"ar"/"uk"/"nl" = translate
    diarize: Boolean,                   // emit "Speaker N:" prefixes
    previousContext: String? = null,    // tail of previous chunk's text (for continuity)
    speakerHints: List<SpeakerHint>? = null, // sherpa-clustered turns in chunk-relative time
    onPartialText: ((String) -> Unit)? = null, // non-suspending streaming callback
    overlapSeconds: Double = 0.0,       // ≥ 0; for the recap-skip prompt
): String
```

Steps inside `runOneChunkLocked` (the mutex-held body):

1. **WAV-wrap the float samples.** `pcmFloatToWavBytes()` writes a
   minimal 44-byte RIFF header + 16-bit signed PCM. LiteRT-LM's
   `Content.AudioBytes` accepts WAV bytes (it doesn't accept raw float
   arrays).
2. **Build the system instruction** via `buildSystemInstruction()`. Rules
   only (e.g., "no preamble, no quotes"). Per Google's audio cookbook,
   the language anchor lives in the USER message, not here.
3. **Create a fresh Conversation** per chunk with the system instruction
   and a `SamplerConfig(topK=1, topP=0.95, temperature=0.1)`. We do not
   reuse a Conversation across chunks — see §10.
4. **Build the user message** via `buildUserMessage()`. Composes:
   - The Google-recommended ASR template ("Transcribe the following
     speech segment in X into X text. Only output the transcription...")
     — names the language **twice** because that's empirically the most
     reliable language anchor on Gemma 3n/4 audio models.
   - Optional `[CONTEXT … END CONTEXT]` block with the previous chunk's
     last 250 chars as a continuity hint. Strongly worded "DO NOT
     transcribe or repeat these lines" to discourage leakage.
   - Optional overlap-skip block for chunks where `overlapSeconds ≥ 2.0`
     (currently never hit at runtime — see §8).
   - Optional diarization block: per-chunk sherpa speaker hints when
     hybrid mode is on, otherwise the generic "Speaker 1, Speaker 2…"
     instruction.
5. **Stream the response** via `conv.sendMessageAsync(contents).collect {
   msg -> … }`. Each emission's `Content.Text` chunks get appended to
   a StringBuilder. The non-suspending `onPartialText` callback fires
   after each delta so the UI layer can surface a live snippet (with
   throttling, see §11).
6. **On `CancellationException` (outer timeout or user-pressed Stop)**:
   call `conv.cancelProcess()` to abort the native worker, then
   rethrow. Without `cancelProcess()`, the engine keeps inferring in
   the background and wedges the next chunk per LiteRT-LM issue #2202.
7. **Two-pass output cleanup**: `stripPreamble(...)` removes
   "Sure, here's the transcript:" framing; `stripLeakedContext(...)`
   removes our own context-block phrases that Gemma sometimes echoes
   verbatim. See §13.
8. **Finally**: `conv.close()` in a `runCatching` to free the native
   handle. Always — including the cancellation path.

---

## 7. The Flow-vs-channelFlow trap

The most painful surprise in this integration. Took two compile cycles
to get right.

**The setup:** `TranscriptionRunner.run` returns `Flow<AsrEvent>` (the
caller subscribes to render progress). Inside the chunk loop we want to
emit Stage events at ~250 ms intervals showing the latest streamed text
from Gemma. The natural shape:

```kotlin
flow {
    for (chunk in chunks) {
        gemma.transcribeChunk(..., onPartialText = { partial ->
            emit(AsrEvent.Stage(partial))   // ← CRASH
        })
    }
}
```

This crashes with **"Flow invariant is violated"**. Two reasons:

1. `Flow.emit()` can only be called from the coroutine that started the
   `flow { }` builder. The callback fires from inside Gemma's
   `withContext(Dispatchers.Default)` block — a different coroutine
   context.
2. Even if you `coroutineScope { launch { emit(...) } }`, you can't —
   `emit` is prohibited from any child coroutine of the producer.

**The fix** uses **two complementary tools**:

1. **`channelFlow { send(...) }` instead of `flow { emit(...) }`** —
   `channelFlow`'s `send` is callable from any coroutine in the
   producer's scope. We converted `TranscriptionRunner.run` wholesale
   (`return@channelFlow` instead of `return@flow`).
2. **An `AtomicReference<String>` sink** for the callback to write into,
   and a **sibling polling coroutine** launched in the channelFlow's
   own scope that reads the AtomicRef every 250 ms and `send()`s a
   Stage event. Pattern:

   ```kotlin
   val partialSink = AtomicReference("")
   coroutineScope {
       val poller = launch {
           while (isActive) {
               delay(250L)
               val p = partialSink.get()
               if (p.isNotEmpty()) send(AsrEvent.Stage("…$p"))
           }
       }
       try {
           gemma.transcribeChunk(
               ...,
               onPartialText = { p -> partialSink.set(p) }, // non-suspending
           )
       } finally {
           poller.cancel()
       }
   }
   ```

This is also why `onPartialText` is **non-suspending** in the public
signature — anything that suspends would tempt callers back into the
trap.

Sentinel exception for cleanup paths: when a chunk fails inside
`.collect { … }`, we can't `return@channelFlow` from inside the
collect lambda (Kotlin disallows non-local returns through the
crossinline collect parameter). We throw a private
`TranscriptionBailout(failure)` exception that the outer `try { ... }
catch (b: TranscriptionBailout)` handler unpacks into the right
`Failed` event before exiting the channelFlow cleanly. The matching
catch must come **before** the generic `catch (Throwable)` or it
gets swallowed.

---

## 8. Chunking strategy

Gemma 4 E2B's audio encoder caps at **30 seconds per inference**.
For anything longer we slice. The slicer lives in
[AudioDecoder.kt](../app/src/main/kotlin/nl/ihnatov/transcriber/audio/AudioDecoder.kt).

### Target chunk size

`CHUNK_SECONDS = 28` ([Gemma4Backend.kt](../app/src/main/kotlin/nl/ihnatov/transcriber/asr/Gemma4Backend.kt)).
Two seconds of headroom under the 30 sec cap because end-of-clip
tokenization sometimes runs long. Bigger chunks = fewer hard
sentence breaks Gemma reads as boundaries = better quality. Going
above 28 starts producing truncated outputs in our tests.

### VAD-aligned cut points

We do NOT use fixed-time 28-sec slicing. Instead:

1. **`AudioDecoder.scanSilences(file)`** does a single MediaCodec pass
   over the file, computing 25-ms RMS windows, and returns the list of
   silence regions ≥ 250 ms with RMS below −46 dBFS. Cheap: ~3 sec
   wall-clock for a 30-min file on an S24 Ultra.
2. **`AudioDecoder.computeCutPoints(silences, duration, target=28, flex=2)`**
   walks the audio; for each chunk's nominal 28-sec end-point, picks
   the silence whose midpoint is closest to that target within ±2 sec.
   Falls back to a hard cut at the target time when no silence lands
   in the flex window (long monologues, music, dense conversation).
3. **`AudioDecoder.decodeAtCutPoints(file, cuts, overlapSamples=16000)`**
   streams the file once more (MediaCodec is fast), emitting chunks at
   the computed boundaries with 1 sec of recap overlap at the start of
   each non-first chunk.

Why VAD instead of fixed time:

- Hard cuts at 28.000 sec frequently slice words mid-syllable. Gemma
  then "loses" the first part of the new chunk (cold start on a
  half-syllable). Users reported this as "the first second of each
  chunk goes missing."
- With silence-aligned cuts, the recap window is mostly silence
  audio, so the model has nothing to transcribe there — no double-
  printed boundary words even though we don't tell it to skip the
  recap.

Why we don't ask Gemma to skip the recap: the prompt-based "Skip the
first 1.0 seconds" instruction combines a **number** and a **negation**,
both of which arxiv:2509.09715 identifies as top hallucination
triggers in Gemma. Empirically the model sometimes obeyed, sometimes
duplicated the recap, sometimes skipped too much. With VAD alignment
and ≤ 1 sec overlap, we just don't say anything — the silence handles
itself.

### Timestamp accounting

Each chunk's segments are stamped from `newContentStart = chunk.startSeconds
+ chunk.overlapSeconds`, NOT from `chunk.startSeconds`. Otherwise every
chunk after the first would double-count its recap window into the
timeline. Sherpa speaker hints in the recap region are filtered out
(`s.coerceAtLeast(effectiveStart)`) so we don't tell Gemma about turns
in audio it's been told to skip.

---

## 9. Long-form continuity

Gemma sees one chunk at a time — by default it has no idea what the
*previous* chunk said. Two affordances stitch chunks together:

1. **`previousContext` text tail.** After processing chunk N we save
   the last 250 chars of its output (with the trailing `Speaker N:`
   marker preserved when diarize is on) as `previousContext`. Chunk
   N+1's user message includes a fenced `=== CONTEXT (DO NOT
   TRANSCRIBE OR REPEAT THESE LINES) === … === END CONTEXT ===` block
   followed by an explicit "Do NOT echo, paraphrase, or quote any
   text from the CONTEXT block." Gives Gemma the named entities,
   speaker numbering, and the partial sentence it was completing.

2. **Trailing-marker preservation.** If chunk N's monologue exceeded
   250 chars, the naive `takeLast(250)` would lose the last `Speaker N:`
   prefix. We detect that case and prepend `[continuing as Speaker N:]`
   to the tail so the next chunk's speaker numbering anchors correctly.
   See `previousContext = if (useGemmaDiar) …` in
   [TranscriptionRunner.kt](../app/src/main/kotlin/nl/ihnatov/transcriber/asr/TranscriptionRunner.kt).

Both are LeakedContext patterns Gemma echoes back surprisingly often;
the output cleanup pass (§13) explicitly strips them.

---

## 10. Why one Conversation per chunk (not one per file)

LiteRT-LM's `Conversation` keeps message history. Tempting design:
reuse a single Conversation across all chunks for a file so the model
has "memory." Don't do this. Three reasons:

1. **Conversation history is text-rendered through a Jinja template**;
   prior chunks' audio embeddings are **not** retained. Only the prior
   text output appears in the prompt. So a multi-turn Conversation
   gives you the same thing as our explicit `[CONTEXT …]` block, just
   less controllable.
2. **KV cache bloat.** Gemma 4 audio = 25 tokens/sec. After 5 chunks of
   28 sec audio, the cache would hold ~3,500 audio tokens of stale
   context — eats into `maxNumTokens` faster than the run can use it.
3. **The wedge problem amplifies.** LiteRT-LM issue #2202 documents
   that a wedged Conversation silently propagates to the next call.
   With one Conversation per chunk, a hang only kills that chunk's
   inference; we can release + rebuild the Engine on the next chunk
   (see §12). With one Conversation per file, you lose the whole run.

So the rule: **fresh `Conversation` per chunk, close it in a finally,
let `previousContext` text carry continuity.**

---

## 11. Streaming UI from non-suspending callbacks

Gemma's per-token streaming is the difference between "static spinner
for 30 sec per chunk" and "watch the words appear." We surface partial
text into the UI via a chain that crosses three coroutine contexts:

```
Gemma SDK (native thread)
   ↓ Content.Text deltas
Conversation.sendMessageAsync collect (Dispatchers.Default)
   ↓ accumulated.toString()
onPartialText callback (non-suspending — just AtomicReference.set)
   ↓ partialSink: AtomicReference<String>
Sibling poller coroutine (channelFlow producer scope)
   ↓ every 250 ms: trySend AsrEvent.Stage
ProducerScope.send (channelFlow safe across coroutines)
   ↓
JobManager._statuses StateFlow
   ↓
RecordingDetailViewModel.ui StateFlow
   ↓
RecordingDetailScreen Compose recomposition
```

The 250 ms throttle is hard-coded in
[TranscriptionRunner.kt](../app/src/main/kotlin/nl/ihnatov/transcriber/asr/TranscriptionRunner.kt)
(`while (isActive) { delay(250L) … }`). Faster updates would burn
StateFlow churn for no visible win; slower updates feel sluggish.
The Stage label shows the last 80 chars as a snippet.

---

## 12. The hang/timeout/rebuild loop

Per LiteRT-LM issue #2202 (real bug, hit in production), a
Conversation can wedge silently — no `onError`, no `onDone`, the Flow
just never completes. We've seen this on Mali-G715 with long context.

Mitigation in [TranscriptionRunner.kt](../app/src/main/kotlin/nl/ihnatov/transcriber/asr/TranscriptionRunner.kt):

```kotlin
private const val CHUNK_TIMEOUT_MS = 5L * 60_000L

var text = withTimeoutOrNull(CHUNK_TIMEOUT_MS) {
    coroutineScope { /* partial-text poller + transcribeChunk */ }
}
if (text == null) {
    // First-pass timeout. Release the wedged engine, reload from
    // disk, and retry the same chunk once.
    asr.release()
    val reloadRes = asr.load(modelFile.absolutePath)
    if (reloadRes.isFailure) throw TranscriptionBailout(AsrEvent.Failed(...))
    partialSink.set("")
    text = withTimeoutOrNull(CHUNK_TIMEOUT_MS) { /* retry */ }
    if (text == null) {
        // Twice-timed-out — abandon the run.
        throw TranscriptionBailout(AsrEvent.Failed("stalled twice…"))
    }
}
```

Real-world chunks take 5–60 sec each. 5 minutes is roughly 10×
real-time — anything past it is genuinely stuck, not slow. The reload
typically succeeds (the wedge is in the Conversation, not the Engine
itself, but releasing both and rebuilding from disk is the only way
we've found to reliably reset the native state).

---

## 13. Output post-processing

Gemma is unreliable about obeying "no preamble" instructions. We
defensively strip after the model returns. Two passes, both in
[Gemma4Backend.kt](../app/src/main/kotlin/nl/ihnatov/transcriber/asr/Gemma4Backend.kt):

**`stripPreamble(text)`** — drops opening framing:

- Surrounding quote pairs (straight, curly, single, double)
- Leading `"Sure, …"`, `"Okay, …"`, `"Of course, …"`
- Leading `"Here's the transcription: …"`, `"The transcription is: …"`
- Leading `"Transcription:"`, `"Translation:"`

**`stripLeakedContext(text)`** — drops mid-text echoes of our own
prompt scaffolding:

- The whole `=== CONTEXT … === END CONTEXT ===` block (DOTALL)
- `[continuing as Speaker N: …]` bracketed wrapper
- `"continuity hint — the previous chunk's final words were: …"`
- `"the previous chunk ended with: …"`
- `"continue naturally from where it stopped …"`
- `"transcribe|translate the following speech segment …"`
- `"only output the transcription …"`
- `"when transcribing numbers …"`
- `"the speech is in X or Y; detect which and transcribe…"`
- Empty `Speaker N:` turns left behind after their body was stripped

Each regex is anchored on our exact prompt wording so colloquial
speech containing the same words (e.g., a podcaster saying "the
transcription is straightforward") isn't damaged.

---

## 14. Diarization integration

The most-asked feature; the trickiest implementation. Three modes:

### Mode 1 — Gemma-only (no extra download)

`diarize = true, hybrid off`. Prompt tells Gemma "Prefix each spoken
segment with 'Speaker 1: ', 'Speaker 2: ', etc. — assigned in the
order the speakers first appear." We parse the response with
`parseGemmaDiarSegments` and stamp speakers as `SPEAKER_NN` based on
the Gemma-emitted number (1-based, stored as 1-padded).

Caveat: **cross-chunk consistency is best-effort.** Gemma 4 has no
memory between chunks (per §10), so chunk 1's "Speaker 1" may be
chunk 2's "Speaker 2." The `previousContext` trailing-marker trick
helps but doesn't guarantee. UI labels this mode "experimental."

### Mode 2 — Hybrid (sherpa-onnx pre-pass + Gemma hints)

`diarize = true, hybrid = true`. Recommended when the embedding model
is installed. Two phases:

1. **Sherpa-onnx clusters the full waveform** via
   [DiarizationRunner.kt](../app/src/main/kotlin/nl/ihnatov/transcriber/asr/DiarizationRunner.kt).
   pyannote segmentation 3.0 + 3D-Speaker CAM++ embeddings. Returns
   globally-consistent `(startSec, endSec, speakerId)` tuples.
2. **Per-chunk hints fed to Gemma.** For each chunk, we filter the
   sherpa output to overlapping turns, **coalesce consecutive
   same-speaker hints** (sherpa over-segments on noisy audio; one
   28-sec chunk produced 80+ turns in the wild), **hard-cap at 12
   turns/chunk**, and translate to chunk-relative time. Inject as
   "Speaker N occurs at [X-Y]s" lines in the user message so Gemma's
   inline labels match sherpa's global numbering.
3. **Reconcile at write time.** When parsing Gemma's output, sherpa
   wins on identity. Gemma's number is checked against sherpa's
   cluster ID by overlap; conflicts get logged but don't change the
   stored label.

The coalesce + cap was added after a real bug: a hint list with 80+
entries blew the user-message past Gemma's effective prompt budget
and the model started hanging on chunk 1.

### Mode 3 — Whisper + sherpa (post-ASR)

`diarize = true, backend = WhisperCpp`. Same `assignSpeakers` helper
runs after the full Whisper pass on the complete mono buffer. Sherpa
is the only identity source; cross-chunk consistency is automatic
because the run is full-file, not chunked.

### Backchannel coalescing (all modes)

[DiarizationRunner.coalesceBackchannels()](../app/src/main/kotlin/nl/ihnatov/transcriber/asr/DiarizationRunner.kt)
runs after `assignSpeakers`. Merges short (< 2 sec) interjections
("yeah", "mhm", "uh-huh" in EN + AR + UK + NL stoplists) into the
surrounding speaker's turn when sandwiched between two segments of
the same different speaker. Mirrors the Mac app's `_merge_backchannels`.

### Auto-naming speakers from speech

[applyInferredSpeakerNames()](../app/src/main/kotlin/nl/ihnatov/transcriber/asr/TranscriptionRunner.kt)
scans the text for `"Hi, I'm Ahmed"`, `"My name is Sara"`, `"This is
Yuri speaking"`, `"I'm X, [the/and/from…]"` patterns. The first
match per speaker key gets propagated to every segment with that
key. Conservative regex + stoplist (catches "I'm Ahmed", skips "I'm
tired"). Runs on every chunk save so a name found in chunk N
back-fills earlier segments for the same speaker.

---

## 15. Speaker-name persistence across re-transcribe

`replaceSegments` does delete + insert; without care, every
re-transcription wipes user-set `speakerName` values. Three places
keep names alive:

1. **In-DB snapshot.** [RecordingRepository.replaceSegments()](../app/src/main/kotlin/nl/ihnatov/transcriber/data/RecordingRepository.kt)
   builds a `speaker → speakerName` map from existing rows before
   deleting; re-applies to incoming rows whose own `speakerName` is
   null.
2. **JSON sidecar.** [TranscriptExporter.writeSidecars()](../app/src/main/kotlin/nl/ihnatov/transcriber/asr/TranscriptExporter.kt)
   writes a `<stem>.speakers.json` (Mac-app-compatible `{ "SPEAKER_NN":
   "Display Name" }` format) when there's at least one named speaker.
   `RecordingRepository.readSpeakersSidecar()` merges it back on the
   next `replaceSegments`; sidecar overrides DB priors (manual edits
   to the JSON, or imports from the Mac pipeline, take precedence).
3. **Live rewrite on rename.** [RecordingDetailViewModel.renameAllByKey()](../app/src/main/kotlin/nl/ihnatov/transcriber/ui/recordings/RecordingDetailViewModel.kt)
   rewrites all sidecars (.txt/.srt/.json/.speakers.json) after each
   rename — persistence is immediate, not deferred to next
   re-transcribe.

---

## 16. Model-currency: MTP speculative decoding

LiteRT-LM v0.11.0 (2026-05-05) added **MTP** — multi-token-prediction
speculative decoding for Gemma 4. ~2× faster decode on mobile GPUs.
Requires the weights to be re-pulled from HuggingFace after the same
date (the SDK + weights both need the MTP heads). Users who installed
before that date silently get the slower decode path.

[ModelCatalog.kt](../app/src/main/kotlin/nl/ihnatov/transcriber/asr/ModelCatalog.kt)
stamps both Gemma 4 entries with `requiredAfterMillis = 1_777_939_200_000L`
(2026-05-05 00:00:00 UTC). The Settings → Models UI compares the
installed file's `lastModified()` to this constant; if older, the
download row shows a tonal "Update" button with the reason
`"Re-download to enable MTP (~2× faster decode on GPU)"`. Re-download
overwrites in place via the existing partial→rename path so the
user's selected-model pin survives.

---

## 17. Live transcription path

[LiveTranscriber.kt](../app/src/main/kotlin/nl/ihnatov/transcriber/asr/LiveTranscriber.kt)
runs during recording — same Engine, different chunking. Chunks come
straight from `WavRecorder.chunks` (5-sec mono float windows). Each
chunk is fed to `gemma.transcribeChunk()` with no `previousContext`,
no diarize, no overlap.

Two implementation tradeoffs vs file transcription:

- **No `previousContext`** between live chunks. The recorder's chunk
  flow doesn't expose adjacent buffers, and stuttery transitions are
  acceptable for the live preview (the post-recording full pass
  overwrites with higher-quality output anyway). Adding context
  carryover is a NEXT_STEPS item.
- **Shared Engine.** The same `Gemma4Backend` singleton serves live
  and file paths. Mutex ensures they don't trample each other.

When the user taps Stop, we snapshot the live transcript, then
`stopLive(clearTranscript = true)` releases the worker, then the
auto-transcribe-on-stop flow re-runs the FULL audio through file
transcription (which uses VAD chunks, hybrid diar, context carryover —
the works) and overwrites the live segments.

---

## 18. Things we tried that didn't work

For the next person who's tempted by these:

- **Reusing one Conversation per file.** §10. Bloats KV, doesn't
  improve quality, wedge propagates.
- **`audioBackend = Backend.GPU()`.** §4. Throws "Audio backend
  constraint mismatch."
- **Larger chunks (35–40 sec).** Started getting truncated outputs.
  30 sec is the encoder cap.
- **Telling Gemma to skip the recap in the prompt.** "Skip the first
  3 seconds…" — Gemma sometimes obeys, sometimes duplicates,
  sometimes drops 6 seconds. VAD-aligned cuts + tiny overlap removed
  the need.
- **Prompt-based diarization without sherpa hints, in production.**
  Works for English short clips, breaks on multi-chunk Arabic
  meetings (speaker numbering drifts between chunks). Hybrid is the
  real product.
- **Calling `emit()` from inside Gemma's callback.** §7. "Flow
  invariant is violated."
- **`return@flow` from inside `.collect { }`.** Compile error
  ("'return' is prohibited here") because `collect` is crossinline.
  Use a sentinel exception caught by an outer try/catch.
- **Passing `seed` as the 4th positional arg to `SamplerConfig`.**
  Works in 0.10.x, dropped from 0.11.0's documented public API. Use
  named args + only `topK / topP / temperature`.

---

## 19. How to validate a change

When you touch anything in [Gemma4Backend.kt](../app/src/main/kotlin/nl/ihnatov/transcriber/asr/Gemma4Backend.kt)
or [TranscriptionRunner.kt](../app/src/main/kotlin/nl/ihnatov/transcriber/asr/TranscriptionRunner.kt),
run this validation set before merging:

1. **Short English clip (≤ 28 sec)** — single chunk path. Tests
   the basic Engine + Conversation + sendMessageAsync wiring.
2. **5-minute Arabic clip with diarize off** — multi-chunk path
   without sherpa. Tests VAD alignment, context carryover, output
   scrubbing.
3. **5-minute Arabic clip with hybrid diarize on, 2 speakers** —
   exercises sherpa pre-pass, hint coalescing, reconcile, backchannel
   merging.
4. **20-minute mixed-language meeting recording** — long-form path,
   stress on KV cache, multiple cut-point fallbacks.
5. **Cancel a transcription mid-chunk** (tap Stop on Detail's RUN
   inverse). Verify in logcat: `sendMessageAsync done … CANCELLED`
   and the engine doesn't deadlock the next run.
6. **Force a timeout** (set `CHUNK_TIMEOUT_MS = 5000L` locally and
   transcribe). Verify the release + reload + retry path executes
   and the final Stage label is the right error message.

logcat tags worth watching:

- `Gemma4Backend` — engine init, sendMessage timing, per-chunk text length
- `TxRunner` — chunk-loop progress, VAD scan results, cut points,
  timeout/retry transitions
- `DiarizationRunner` — sherpa run timing, embedding model load state

---

## 20. Open questions / known limits

Carried over from [NEXT_STEPS.md](../NEXT_STEPS.md):

- **Audio backend is CPU-locked** — limits decode throughput; no
  fix until LiteRT-LM relaxes the encoder constraint
  (issue #1848, closed as not planned)
- **No mid-chunk Stop** in the UI yet. Plumbing exists
  (`sendMessageAsync` + `cancelProcess`); needs a "Stopping…" Stage
  label while cancellation propagates (`cancelProcess` can take ~1 sec)
- **Live cross-chunk context** not implemented; live chunks each start
  cold
- **Whisper still OOMs on multi-hour audio** — Gemma path doesn't
  (streaming decode), but the Whisper alternate backend materializes
  the full buffer. Needs VAD-driven streaming for Whisper too
- **`previousContext` is a string, not structured** — if you ever
  want to feed Gemma the previous chunk's speaker numbering as
  metadata rather than embedded in prose, you'll need a new
  `Conversation` parameter

---

## File map (quick reference)

| File | What it does |
|---|---|
| [Gemma4Backend.kt](../app/src/main/kotlin/nl/ihnatov/transcriber/asr/Gemma4Backend.kt) | Engine handle, sendMessageAsync wiring, system + user message builders, output scrubbers |
| [TranscriptionRunner.kt](../app/src/main/kotlin/nl/ihnatov/transcriber/asr/TranscriptionRunner.kt) | Multi-chunk file pipeline: VAD scan → cut points → chunk loop with timeout/retry → segment persist |
| [LiveTranscriber.kt](../app/src/main/kotlin/nl/ihnatov/transcriber/asr/LiveTranscriber.kt) | Streaming-during-recording path |
| [AudioDecoder.kt](../app/src/main/kotlin/nl/ihnatov/transcriber/audio/AudioDecoder.kt) | MediaCodec wrapper, streaming chunker, silence scanner, cut-point computer |
| [DiarizationRunner.kt](../app/src/main/kotlin/nl/ihnatov/transcriber/asr/DiarizationRunner.kt) | sherpa-onnx wrapper, `assignSpeakers`, `coalesceBackchannels` |
| [GemmaSettingsStore.kt](../app/src/main/kotlin/nl/ihnatov/transcriber/asr/GemmaSettingsStore.kt) | Persisted compute knobs (backend, maxNumTokens, threads) |
| [ModelCatalog.kt](../app/src/main/kotlin/nl/ihnatov/transcriber/asr/ModelCatalog.kt) | Curated download list, MTP cutoff, file metadata |
| [PromptStore.kt](../app/src/main/kotlin/nl/ihnatov/transcriber/asr/PromptStore.kt) | User-editable system prompts, vocabulary, tone, verbatim toggle |
| [DomainVocabulary.kt](../app/src/main/kotlin/nl/ihnatov/transcriber/asr/DomainVocabulary.kt) | Five-domain quick-fill vocabulary packs (Medical/IT/Construction/Legal/Finance × EN/AR/UK/NL) |

---

## TL;DR — the four hard-won rules

1. **One Engine per process, one Conversation per chunk, mutex
   between callers.** Anything else corrupts state or wastes KV.
2. **`channelFlow` not `flow` for the per-chunk loop;
   `AtomicReference` not closure capture for the streaming callback.**
   `Flow.emit` is single-coroutine, the inference is multi-coroutine.
3. **Cut chunks at silence boundaries, not on a stopwatch.** The
   1-second overlap is a safety margin, not the primary mechanism.
4. **Trust nothing in the model output.** Scrub the prompt phrases
   even though the prompt asks for "no preamble"; cancel the native
   worker explicitly even though the Flow says it's done; release the
   Engine on timeout even though "the SDK handles cleanup."

---

## 21. The Gemma 4 prefill wedge (and how we survive it)

### What it looks like

```
→ chunk 6/66 (28s audio, hybrid=false) calling Gemma…
  sendMessageAsync start (audio=919644B, prompt=1255chars, diarize=true, hints=0)
                                                  ← (no deltas; no completion; nothing)
chunk 6 timed out — releasing engine, reloading, and retrying once
```

The native `RunPrefillAsync` step inside LiteRT-LM's session
machinery wedges. `sendMessageAsync` is alive but never emits a
delta. `cancelProcess()` is largely a no-op once this state is
reached — the C++ side won't unwind. The outer timeout used to wait
the full 5 min before triggering the retry path; retry sent the
SAME inputs and wedged the SAME way; ~10 min wasted per stuck chunk.

### Conditions that trigger it

Three correlated factors, every observed wedge had all three:

1. **Chunk audio at or above ~28–30 s.** Asymmetric flex in
   [AudioDecoder.computeCutPoints](../app/src/main/kotlin/nl/ihnatov/transcriber/audio/AudioDecoder.kt)
   now caps content at 28 s (silence-aligned cuts can only land at
   or before nominal target). Eliminates the 30-s-edge wedge mode
   entirely.
2. **Large prompt** (~1200+ chars), which happens when
   `diarize=true` + hybrid hints + non-empty `previousContext` all
   stack onto the user-message template.
3. **Low-information audio** at the chunk — silence, music
   interludes, near-silent crosstalk. The model can't produce a
   confident first token from the audio and the large prompt
   biases its initial-token distribution into a degenerate state.

### Defense in depth

In order of which fires first:

| Layer | Mechanism | Latency before next chunk starts |
|---|---|---|
| Silence pre-skip | RMS of chunk samples < 0.002 → skip Gemma entirely | ~0 ms |
| Silent-wedge watchdog | 60 s no-delta → `cancelProcess()`; +30 s → `conv.close()` | ≤ 90 s |
| Outer chunk timeout | 2 min → engine release + reload | ≤ 2 min |
| Retry with reduced input | Trim audio to 24 s, drop diarize, drop previousContext | sec |

In practice: **silence pre-skip handles 80% of would-be wedges**.
The watchdog catches the rest. The outer timeout is now a backstop
that almost never fires. The retry's "shrunk input" breaks the
deterministic attractor when the original input would have
re-wedged.

See
[TranscriptionRunner.kt](../app/src/main/kotlin/nl/ihnatov/transcriber/asr/TranscriptionRunner.kt)
`SILENCE_PRE_SKIP_RMS`, `CHUNK_TIMEOUT_MS`, `RETRY_AUDIO_SECONDS`
and
[Gemma4Backend.kt](../app/src/main/kotlin/nl/ihnatov/transcriber/asr/Gemma4Backend.kt)
`NO_DELTA_WEDGE_MS`, `NO_DELTA_HARD_CLOSE_MS`.

---

## 22. The runaway-repetition decoding loop

Gemma in degenerate-decoding occasionally lands on a
self-reinforcing pattern — most commonly `"Speaker 1: Speaker 1:
Speaker 1: …"` produced until the timeout fires.

Detection is split between an **online detector** (fires during
streaming, before the model has produced too much junk) and an
**offline tail-trimmer** (cleans up whatever made it through).

### Online detector (`Gemma4Backend.isRepetitionLoop`)

Two windows:

- **Short window (80 chars)**: ≥3 `Speaker N:` markers with ≤30-char
  gaps AND ≥ `REPETITION_THRESHOLD−1` of them have **empty bodies
  or duplicate consecutive bodies**. The body check is essential —
  fast natural exchanges (`"Good day." / "Yeah, good day." /
  "Thanks Paul. Bye."`) have unique non-empty bodies and must NOT
  trigger.
- **Long window (200 chars)**: the most-common 8-char n-gram covers
  >50% of the window. Catches non-speaker-marker loops.

On trigger: `cancelProcess()` is called; the collect throws
`RepetitionLoopException`; outer catch swallows it; we fall
through to post-processing with whatever real content was produced
before the loop.

### Offline tail-trim (`trimRepetitionTail`)

Walks markers from the end. While each marker has an empty or
duplicate-of-next body, drops it. Stops at the first marker with
unique non-empty content. Preserves the real conversation before
the loop and excises only the trailing junk.

---

## 23. Sherpa-onnx ABI gotcha (versioned symbols)

Most expensive single bug in this codebase to diagnose. Documented
here so the next dev doesn't repeat the chain of failed attempts.

**Symptom.** Every diarized run failed with `UnsatisfiedLinkError:
cannot locate symbol "OrtGetApiBase"` from
`libsherpa-onnx-jni.so`. Error was swallowed by `runCatching` so
diarization silently fell back to Gemma-only labels; users saw it
as "hybrid never worked."

**False trails attempted**:

1. Downgrade ORT to 1.19.2 (older API). Still fails.
2. `System.loadLibrary("onnxruntime")` preload in
   `DiarizationRunner.init`. Still fails.
3. Touch `OrtEnvironment.getEnvironment()` to force ORT's Java
   loader. Still fails.
4. Drop the Microsoft ORT exclude — let sherpa use its bundled
   `com.bihe0832.android:lib-onnx:6.16.7`. **Still fails.**

**Actual cause.** Versioned ELF symbols. `llvm-readelf --dyn-syms`
on the .so files in the APK:

```
libsherpa-onnx-jni.so:  UND OrtGetApiBase@VERS_1.24.3
libonnxruntime.so:      GLOBAL OrtGetApiBase@@VERS_1.17.1  (← bundled lib-onnx)
libonnxruntime.so:      GLOBAL OrtGetApiBase@@VERS_1.26.0  (← Microsoft 1.26.0)
libonnxruntime.so:      GLOBAL OrtGetApiBase@@VERS_1.19.2  (← Microsoft 1.19.2)
```

`libsherpa-onnx-jni.so` was compiled against ORT **1.24.3
specifically** with versioned-symbol linking. No other ORT
satisfies the requirement. The Linux dynamic linker won't
substitute across version tags — `VERS_1.26.0` ≠ `VERS_1.24.3`
even if the function body is API-compatible.

**Fix**: pin `onnxruntime = "1.24.3"` + exclude
`com.bihe0832.android:lib-onnx`. Both are required; the bundled
lib has the wrong version, and any other Microsoft version has
the wrong version. Microsoft's 1.24.3 ships 16-KB-aligned
binaries too, so we get both working diarization and a quiet
Android 15 sync.

**Sticky rules**:

- Every sherpa-onnx version pin **also requires** an exact ORT
  version pin. They are coupled.
- If sherpa-onnx is upgraded, check the new
  `libsherpa-onnx-jni.so` with `llvm-readelf --dyn-syms` to find
  the required ORT version, then move `onnxruntime` in
  `libs.versions.toml` to match.
- Don't trust the SDK release notes — `lib-onnx:6.16.7` was the
  bundled "matched" version for sherpa-onnx 8.5.1 according to
  the JitPack pin, but the bundled lib actually ships ORT 1.17.1
  symbols, not 1.24.3. Verify the .so directly.

---

## 24. Cancellation, progress, and the `processWithCallback` trap

Sherpa-onnx has two diarization entry points:

- `diar.process(samples)` — blocking, no progress, no
  cancellation. Sits on the JNI thread for minutes.
- `diar.processWithCallback(samples, callback)` — calls the
  Kotlin callback per internal chunk with `(processed, total,
  arg)`. Returning non-zero aborts native processing.

The callback path **is broken in sherpa-onnx 8.5.1** when the
Kotlin callback captures any state. R8/D8 generates a synthetic
lambda class (`$$ExternalSyntheticLambda0`) whose method
signature doesn't match sherpa's hardcoded JNI lookup of
`invoke(II J)Ljava/lang/Integer;`. Fatal crash on first
callback fire:

```
JNI DETECTED ERROR: java.lang.NoSuchMethodError: no non-static
method "DiarizationRunner$run$2$$ExternalSyntheticLambda0
.invoke(IIJ)Ljava/lang/Integer;"
```

**Workaround**. Use the blocking `process(samples)` + a sibling
coroutine that calls `diar.release()` on parent cancellation. The
native processing then errors out within a few seconds instead
of hanging. Progress is a 5-second heartbeat timer that emits
elapsed-time-as-fraction; the UI shows
`"Identifying speakers · 35s elapsed"`. Not a real percent, but
the user can see the process is alive.

See [DiarizationRunner.kt](../app/src/main/kotlin/nl/ihnatov/transcriber/asr/DiarizationRunner.kt)
for the watcher + heartbeat pattern.

---

## 25. Memory budget for sherpa pre-pass

Sherpa needs the **full** mono-float buffer in heap at once — its
API takes a `FloatArray`, not a stream. Memory cost:

- Raw float buffer: `duration_sec × 16000 × 4 bytes ÷ 1024² = MB`.
  - 30 min → 110 MB
  - 60 min → 220 MB
- Peak during decode: roughly **3× raw** because of the doubling
  growth allocator inside `AudioDecoder.decode` + codec frame
  buffers held alive concurrently.

[TranscriptionRunner.kt](../app/src/main/kotlin/nl/ihnatov/transcriber/asr/TranscriptionRunner.kt)
does a pre-check before sherpa runs:

```kotlin
val budgetMb = Runtime.getRuntime().maxMemory() - usedMemory
val expectedPeakMb = rawFloatMb * 3
if (expectedPeakMb > budgetMb * 0.7) {
    send(AsrEvent.Stage("Recording too long for hybrid diarization...", 0.12f))
    // Fall through to Gemma-only diar
} else {
    // proceed with sherpa
}
```

With `largeHeap="true"` (in `AndroidManifest.xml`) the budget is
~512 MB on flagship devices. **Practical limit: ~45 min** for the
full hybrid pipeline. Beyond that, we automatically fall back to
Gemma-only labels with a friendly Stage event.

A streaming / chunked sherpa pre-pass would lift this cap — see
NEXT_STEPS.md "Chunked diarization for files > 45 min."

---

## 26. Output scrubbing — full list of leak patterns

Gemma echoes prompt content into output when it can't transcribe
the audio (silence, music, low SNR). All patterns below are
anchored on exact phrasings we emit, so real speech using the
same words isn't eaten.

| Pattern | Source | Layer |
|---|---|---|
| `Speaker N: Speaker N: …` (≥3 with empty/dup bodies) | degenerate decoding | online detector + offline trim |
| Bare comma list where all tokens ∈ user's vocabulary | system-prompt extras leak on silence | `scrubBareVocabEcho` |
| `=+ CONTEXT [...] =+ END CONTEXT =+` (any `=` count) | our continuation block | `stripLeakedContext` |
| Orphan `=+ END CONTEXT =+` | model echoed half the fence | `stripLeakedContext` |
| `[continuing as Speaker N: ...]` | older prompt variant | `stripLeakedContext` |
| `Continuity hint — the previous chunk's final words were: …` | our explicit phrasing | `stripLeakedContext` |
| `Vocabulary (spell exactly...): X, Y, Z.` | PromptStore extras | `stripLeakedContext` |
| `Vocabulary (spell exactly when these appear): …` | PostProcessor variant | `stripLeakedContext` |
| `Remove filler words: um, uh, ...` | PromptStore extras | `stripLeakedContext` |
| `Verbatim mode: do not add ...` | PromptStore extras | `stripLeakedContext` |
| `Tone: formal/casual/.../neutral …` | PromptStore extras | `stripLeakedContext` |
| `Additional instructions:` header | PromptStore wrapper | `stripLeakedContext` |
| `Transcribe/Translate the following speech segment …` | our user-message template | `stripLeakedContext` |
| `Only output the transcription …` | our user-message template | `stripLeakedContext` |
| `When transcribing numbers, write the digits.` | our user-message template | `stripLeakedContext` |
| `The speech is in <X or Y>; detect which …` | constrained-auto language hint | `stripLeakedContext` |
| `Sure, here is the transcript:` and family | model framing | `stripPreamble` |
| Wrapping `" ... "` / `'...'` / `“...”` quotes | model framing | `stripPreamble` |

Order in
[transcribeChunk](../app/src/main/kotlin/nl/ihnatov/transcriber/asr/Gemma4Backend.kt):
`stripPreamble` → `stripLeakedContext` → `trimRepetitionTail` →
`scrubBareVocabEcho`. Each layer is conservative on its own —
stacking them is what makes the output clean.
