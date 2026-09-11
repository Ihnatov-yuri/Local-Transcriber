package nl.ihnatov.transcriber.asr

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Gemma 4 ASR backend via **LiteRT-LM** (`com.google.ai.edge.litertlm:litertlm-android`).
 *
 * Why LiteRT-LM and not MediaPipe LLM Inference: the Gemma 4 `.litertlm`
 * weights from `litert-community/gemma-4-E2B-it-litert-lm` are LiteRT-LM-native.
 * Loading them through MediaPipe's `tasks-genai` (the older `.task` runtime)
 * crashes inside `LlmInferenceEngine_CreateEngine` looking for the wrong layout
 * and incidentally probes a Qualcomm `libpenguin.so` that we don't ship. Google's
 * own docs at ai.google.dev/edge/litert-lm/android explicitly recommend migrating
 * to LiteRT-LM for newer multimodal models.
 *
 * The wire format is unchanged: this backend still takes a 16 kHz mono float
 * buffer in [-1, 1] and emits one [RawSegment] covering the whole clip — Gemma
 * doesn't produce per-utterance timestamps, so diarization (sherpa-onnx) is what
 * gets you speaker-attributed segments.
 */
class Gemma4Backend(
    private val context: Context,
    private val promptStore: PromptStore? = null,
    /**
     * User-configurable compute knobs (GPU/CPU choice, max context tokens,
     * CPU thread count). Nullable so tests + the legacy two-arg constructor
     * still work; in production [AppContainer] passes one in.
     */
    private val gemmaSettings: GemmaSettingsStore? = null,
) : AsrBackend {

    override val id: String get() = "gemma-4-E2B (litert-lm)"

    private var engine: Engine? = null
    private val mutex = Mutex()

    override val isReady: Boolean
        get() = engine != null

    override suspend fun load(modelPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            engine?.close()
            engine = null
            // Which backends to try, in order. The user's choice in
            // GemmaSettingsStore drives this:
            //   Auto → GPU then CPU (the historic behaviour)
            //   Gpu  → GPU only; surface the error if it fails
            //   Cpu  → CPU only; never touch the OpenCL path
            val choice = gemmaSettings?.backend?.value ?: GemmaBackendChoice.Auto
            val attempts = when (choice) {
                GemmaBackendChoice.Auto -> listOf(true, false)
                GemmaBackendChoice.Gpu -> listOf(true)
                GemmaBackendChoice.Cpu -> listOf(false)
            }
            // Collect failures across all attempts (typically GPU then CPU
            // in Auto mode) so the final error message names BOTH failures,
            // not just the last one. Otherwise "GPU OOM → CPU model too
            // large" silently becomes "CPU model too large" and the user
            // never learns the GPU attempt happened.
            val failures = mutableListOf<String>()
            for (gpu in attempts) {
                try {
                    engine = buildEngine(modelPath, gpu = gpu)
                    Log.i(TAG, "LiteRT-LM engine ready on ${if (gpu) "GPU" else "CPU"} " +
                        "(model=$modelPath, choice=${choice.id})")
                    return@withLock Result.success(Unit)
                } catch (t: Throwable) {
                    val backend = if (gpu) "GPU" else "CPU"
                    Log.e(TAG, "engine init failed on $backend", t)
                    failures += "$backend: ${t.message ?: t.javaClass.simpleName}"
                }
            }
            Result.failure(IllegalStateException(
                if (failures.isEmpty()) "No backend attempts configured for choice=${choice.id}"
                else "Gemma engine init failed — ${failures.joinToString("; ")}"
            ))
        }
    }

    private fun buildEngine(modelPath: String, gpu: Boolean): Engine {
        val backend = if (gpu) {
            Backend.GPU()
        } else {
            // 0 means "let the SDK decide" (it picks ~half the cores). The
            // setting is clamped to 0..8 in GemmaSettingsStore.
            val threads = gemmaSettings?.cpuThreads?.value ?: 0
            if (threads > 0) Backend.CPU(threads) else Backend.CPU()
        }
        // The Gemma 4 E2B/E4B `.litertlm` audio models constrain their audio
        // encoder to CPU. If we set `audioBackend = Backend.GPU()` (matching
        // a GPU text backend), engine init throws:
        //     "Audio backend constraint mismatch. Model requires one of
        //      [cpu] but Audio backend is GPU"
        // The Auto path used to recover by retrying everything on CPU,
        // which works but loses the GPU text-decode win. Pinning the audio
        // encoder to CPU explicitly lets text decode stay on GPU when the
        // user picked GPU/Auto, while keeping the audio path on its
        // required CPU.
        val audioCpuThreads = gemmaSettings?.cpuThreads?.value ?: 0
        val audioBackend: Backend =
            if (audioCpuThreads > 0) Backend.CPU(audioCpuThreads) else Backend.CPU()
        val cfg = EngineConfig(
            modelPath = modelPath,
            backend = backend,
            audioBackend = audioBackend,
            // Context window. The SDK default is ~512 — too low for a 30-sec
            // audio chunk's worth of tokens. 8192 (our default) covers (a) a
            // full chunk of Arabic-dialect ASR with margin, and (b) the
            // "Context-aware rewrite" preset which needs to fit the entire
            // transcript plus output in one pass. Gemma 3n E2B advertises 32K
            // context; user can opt into more via Settings → Gemma 4 compute,
            // at the cost of extra KV-cache RAM on long inputs.
            maxNumTokens = gemmaSettings?.maxNumTokens?.value ?: GemmaSettingsStore.DEFAULT_MAX_TOKENS,
        )
        // Construct first, then initialize. If initialize() throws, close()
        // the partially-constructed Engine — otherwise its native handles
        // (libLiteRtClGlAccelerator, ~5 MB of mmap'd weights) leak across
        // the GPU→CPU fallback path.
        val engine = Engine(cfg)
        try {
            engine.initialize()
        } catch (t: Throwable) {
            runCatching { engine.close() }
            throw t
        }
        return engine
    }

    override suspend fun transcribe(
        samples: FloatArray,
        sampleRate: Int,
        language: String?,
        translate: Boolean,
        progress: ((Float) -> Unit)?,
    ): Result<List<RawSegment>> = withContext(Dispatchers.Default) {
        // Legacy entry point — promotes the single string to a one-element
        // list (or empty list for auto). The legacy bool only expressed
        // "translate to English"; map to translateTo="en" so the new
        // user-message template still does the right thing. New callers
        // should prefer [transcribeChunk] with explicit `translateTo` so
        // they can target any language.
        val languages = if (language.isNullOrBlank() || language == "auto") emptyList()
            else listOf(language)
        val translateTo = if (translate) "en" else null
        mutex.withLock {
            val e = engine ?: return@withLock Result.failure(IllegalStateException("Gemma 4 not loaded"))
            try {
                progress?.invoke(0.02f)
                val chunkSamples = (CHUNK_SECONDS * sampleRate)
                val out = mutableListOf<RawSegment>()
                val numChunks = (samples.size + chunkSamples - 1) / chunkSamples

                for (i in 0 until numChunks) {
                    val from = i * chunkSamples
                    val to = minOf(samples.size, from + chunkSamples)
                    val slice = samples.copyOfRange(from, to)
                    val startSec = from.toDouble() / sampleRate
                    val endSec = to.toDouble() / sampleRate

                    if (i == 0) {
                        val rendered = buildSystemInstruction(languages, translateTo != null)
                        Log.i(TAG, "languages=$languages translateTo=$translateTo")
                        Log.i(TAG, "system instruction:\n$rendered")
                    }
                    Log.i(TAG, "chunk ${i + 1}/$numChunks  ${"%.1f".format(startSec)}s – ${"%.1f".format(endSec)}s")
                    val text = runOneChunkLocked(e, slice, sampleRate, languages, translateTo)
                    if (text.isNotEmpty()) {
                        out += RawSegment(startSeconds = startSec, endSeconds = endSec, text = text)
                    }
                    progress?.invoke(0.02f + 0.96f * (i + 1) / numChunks)
                }
                Result.success(out)
            } catch (t: Throwable) {
                Log.e(TAG, "transcribe failed", t)
                Result.failure(t)
            }
        }
    }

    /**
     * Public: transcribe a single 25-second-or-less audio chunk. Used both by
     * the in-class chunking inside [transcribe] (full FloatArray path) and by
     * [TranscriptionRunner] when it consumes the streaming decoder for Gemma —
     * lets us avoid materializing the full audio in memory for long files.
     *
     * Caller passes the chunk's absolute start time; we don't track offsets.
     *
     * @param translateTo Target language code (en/ar/uk/nl/…). null means
     *  "transcribe in source language". Any non-null value triggers the
     *  "Translate … in {source} into {target} text" template per Google's
     *  audio cookbook; the model handles cross-language translation.
     */
    /**
     * One sherpa-clustered speaker turn within a single chunk, with times
     * in *chunk-relative* seconds (i.e. 0.0 = start of this chunk). The
     * caller is responsible for converting from globally-clustered sherpa
     * results into chunk-relative form before passing in. Speaker IDs are
     * presented to Gemma as 1-based ("Speaker 1", "Speaker 2") but stored
     * here as the sherpa 0-based value; rendering adds the +1.
     */
    data class SpeakerHint(
        val startSec: Double,
        val endSec: Double,
        val speakerId: Int,
    )

    suspend fun transcribeChunk(
        chunkSamples: FloatArray,
        sampleRate: Int,
        languages: List<String>,
        translateTo: String?,
        diarize: Boolean = false,
        /**
         * Tail of the previous chunk's transcript (last ~250 chars). When
         * non-null we tell Gemma "the previous chunk ended with ...; continue
         * naturally from there." This anchors named entities, speaker
         * continuity, and pronouns across the hard chunk boundary — without
         * it each chunk reads as if it started cold.
         */
        previousContext: String? = null,
        /**
         * Pre-computed speaker turns from sherpa-onnx voice clustering,
         * filtered to overlap this chunk and remapped to chunk-relative
         * time. When non-null, replaces the generic diarization prompt with
         * specific "Speaker N occurs at [X-Y]s" instructions so Gemma's
         * inline labels match sherpa's global numbering by construction.
         * Identity is still reconciled against sherpa upstream — these
         * hints just keep Gemma's output aligned so the override is rarely
         * needed.
         */
        speakerHints: List<SpeakerHint>? = null,
        /**
         * Streaming callback fired with the cumulative chunk text as Gemma
         * generates tokens. **Must not suspend or call into the parent
         * Flow's emit()** — it fires from inside Gemma's Default-dispatcher
         * coroutine, which is a different context than the caller's Flow
         * collector. Use a non-blocking sink (e.g. `AtomicReference.set`)
         * here and surface progress upstream via a separate coroutine
         * launched on the Flow body's own context. Ignored when null.
         */
        onPartialText: ((String) -> Unit)? = null,
        /**
         * Seconds at the START of this chunk that are a recap of the
         * previous chunk's tail (overlap region from
         * [nl.ihnatov.transcriber.audio.AudioDecoder.Chunk.overlapSeconds]).
         * When > 0, the prompt explicitly instructs Gemma to skip
         * transcribing this opening window and start its output at the
         * point where NEW content begins. Lets us bridge hard-cut chunk
         * boundaries without losing the first few words of each new
         * chunk to a half-syllable cold start.
         */
        overlapSeconds: Double = 0.0,
    ): String = withContext(Dispatchers.Default) {
        val e = engine ?: return@withContext ""
        mutex.withLock {
            runOneChunkLocked(e, chunkSamples, sampleRate, languages, translateTo, diarize, previousContext, speakerHints, onPartialText, overlapSeconds)
        }
    }

    /** One inference on a single chunk. The mutex protects the shared Engine. */
    private suspend fun runOneChunkLocked(
        engine: Engine,
        chunkSamples: FloatArray,
        sampleRate: Int,
        languages: List<String>,
        translateTo: String?,
        diarize: Boolean = false,
        previousContext: String? = null,
        speakerHints: List<SpeakerHint>? = null,
        onPartialText: ((String) -> Unit)? = null,
        overlapSeconds: Double = 0.0,
    ): String {
        val wavBytes = pcmFloatToWavBytes(chunkSamples, sampleRate)

        // System instruction holds rules/persona that don't change run-to-run.
        // Language adherence is NOT here — per Google's audio docs at
        // ai.google.dev/gemma/docs/capabilities/audio, language anchors stick
        // best when the instruction lives in the user message next to the
        // audio token. Putting "transcribe in Arabic" in the system prompt
        // dilutes the anchor and the model drifts to English/MSA on dialect
        // audio. So the system channel here is just rules; the per-call
        // language template goes in the user turn below.
        val systemText = buildSystemInstruction(languages, translateTo != null, diarize)
        val conv = engine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(Content.Text(systemText)),
                // Low temperature for ASR — Google's audio examples and our
                // own A/B testing show 0.0–0.2 reduces drift to English and
                // hallucinated translations on Gulf Arabic and Ukrainian.
                // topK=1, topP=0.95 follow Google's audio cookbook defaults.
                // We drop the 4th `seed` positional arg — it's undocumented
                // in the public LiteRT-LM 0.11.0 Kotlin API and could break
                // on future SDK bumps. With temperature=0.1 and topK=1 the
                // output is effectively deterministic anyway.
                samplerConfig = SamplerConfig(
                    topK = 1,
                    topP = 0.95,
                    temperature = 0.1,
                ),
            )
        )
        try {
            // User message = audio + Google's exact ASR template. The
            // template names the language twice (input → output) on purpose;
            // this redundancy is what their audio cookbook recommends and is
            // measurably more reliable than a single mention.
            //
            // For diarized mode we add speaker-label instructions HERE (not
            // in the system prompt) for the same reason — anchored next to
            // the audio.
            val userText = buildUserMessage(languages, translateTo, diarize, previousContext, speakerHints, overlapSeconds)
            // Detailed step logging — the upstream TranscriptionRunner wraps
            // each chunk with a 5-minute timeout. When that fires, these
            // logs let us see which sub-step we were stuck in (build
            // conversation? sendMessageAsync emit? response decode?) instead
            // of just "the chunk hung."
            val sendStart = System.currentTimeMillis()
            Log.d(TAG, "  sendMessageAsync start (audio=${wavBytes.size}B, " +
                "prompt=${userText.length}chars, diarize=$diarize, " +
                "hints=${speakerHints?.size ?: 0})")
            // Streaming: collect deltas off the Flow form of sendMessage.
            // Three wins vs the blocking sendMessage we used before:
            //   1. Token-by-token UI updates via [onPartialText] — the user
            //      sees text appearing instead of staring at a stale "3/12"
            //      pill for 30+ sec.
            //   2. Real cancellation. If the outer withTimeoutOrNull (or a
            //      user-pressed Stop) cancels the coroutine, the collect
            //      throws CancellationException — we then call
            //      conv.cancelProcess() to actually stop the native worker.
            //      Without that, the engine keeps inferring in the background
            //      and wedges the next chunk per LiteRT-LM issue #2202.
            //   3. The Flow completes naturally on engine onDone/onError, so
            //      we don't need a separate await; the for-each-delta loop
            //      ends and we move on to post-processing.
            val accumulated = StringBuilder()
            var cancelled = false
            // Two detectors run in parallel during streaming:
            //
            //   (a) Repetition detector — fires when the model is producing
            //       text but in a degenerate-decoding loop (the user-
            //       reported "Speaker 1: Speaker 1: …" pattern).
            //
            //   (b) Silent-wedge watchdog — fires when the model is alive
            //       (sendMessageAsync running) but produces NO deltas at
            //       all for too long. This is the real failure mode at the
            //       30-second audio edge: Gemma accepts the input, never
            //       emits a token, and we'd otherwise wait the full outer
            //       timeout (was 5 min) for nothing. The watchdog cuts the
            //       wait to NO_DELTA_WEDGE_MS.
            //
            // Watchdog is a sibling coroutine; on trigger it calls
            // cancelProcess() to release the native worker and the
            // collect's CancellationException catch handles the unwind.
            var loopDetected = false
            var lastDeltaMs = System.currentTimeMillis()
            val cleaned: String = coroutineScope {
                val watchdogJob = launch(Dispatchers.Default) {
                    var cancelCalledAt: Long = 0
                    while (isActive) {
                        delay(2_000L)
                        val now = System.currentTimeMillis()
                        val sinceLast = now - lastDeltaMs
                        if (cancelCalledAt == 0L && sinceLast > NO_DELTA_WEDGE_MS) {
                            // Stage 1: soft cancel via cancelProcess().
                            // Most of the time this unblocks the collect
                            // within a few hundred ms and the function
                            // returns normally.
                            Log.w(TAG, "  silent-wedge watchdog: no delta for " +
                                "${sinceLast / 1000}s (accumulated=${accumulated.length} chars). " +
                                "Calling cancelProcess() (soft abort).")
                            runCatching { conv.cancelProcess() }
                            cancelCalledAt = now
                            // Do NOT break — keep monitoring in case
                            // cancelProcess is a no-op on this wedge
                            // (observed for prefill stalls on long
                            // audio + diarize prompts: cancelProcess
                            // returns immediately but the collect
                            // stays blocked forever).
                        } else if (cancelCalledAt > 0 &&
                            now - cancelCalledAt > NO_DELTA_HARD_CLOSE_MS
                        ) {
                            // Stage 2: hard close. cancelProcess didn't
                            // unblock the worker within the grace
                            // window. Force-close the Conversation —
                            // that closes the underlying callback
                            // channel and surfaces an exception in the
                            // collect, unwinding the scope. Don't
                            // touch the engine here; releasing /
                            // reloading is the chunk-loop's job (it
                            // has the retry path wired up) and doing
                            // it here would require synchronising
                            // with the class-level engine field.
                            Log.e(TAG, "  silent-wedge watchdog: cancelProcess() did " +
                                "not unblock in ${(now - cancelCalledAt) / 1000}s. " +
                                "Force-closing conversation.")
                            runCatching { conv.close() }
                            break
                        }
                    }
                }
                try {
                    try {
                        conv.sendMessageAsync(
                            Contents.of(
                                Content.AudioBytes(wavBytes),
                                Content.Text(userText),
                            ),
                            emptyMap(),
                        ).collect { msg ->
                            val delta = msg.contents.contents
                                .filterIsInstance<Content.Text>()
                                .joinToString("") { it.text }
                            if (delta.isNotEmpty()) {
                                lastDeltaMs = System.currentTimeMillis()
                                accumulated.append(delta)
                                onPartialText?.invoke(accumulated.toString())
                                if (isRepetitionLoop(accumulated)) {
                                    loopDetected = true
                                    Log.w(TAG, "  detected runaway repetition in chunk output, " +
                                        "cancelling native worker. tail=" +
                                        accumulated.takeLast(120).toString().replace('\n', '|'))
                                    runCatching { conv.cancelProcess() }
                                    throw RepetitionLoopException()
                                }
                            }
                        }
                    } catch (_: RepetitionLoopException) {
                        // Swallow — we already called cancelProcess() and
                        // the partial accumulated text will get loop-tail
                        // trimmed below.
                        cancelled = true
                    } catch (ce: CancellationException) {
                        // Outer timeout or user-pressed Stop. cancelProcess()
                        // was already called by the watchdog if THIS path
                        // was triggered by a silent wedge; call it again
                        // is a no-op. Rethrow so withTimeoutOrNull at the
                        // chunk-loop layer sees the timeout.
                        cancelled = true
                        runCatching { conv.cancelProcess() }
                        throw ce
                    }
                    Log.d(TAG, "  sendMessageAsync done in " +
                        "${System.currentTimeMillis() - sendStart}ms " +
                        "(${accumulated.length} chars" +
                        (if (cancelled) ", CANCELLED" else "") + ")")
                    // Three-pass cleanup: outer preamble strip first (the
                    // model's "Sure, here's the transcript:" framing),
                    // then a leak scrubber for mid-text fragments of our
                    // own context prompt that occasionally land inside a
                    // "Speaker 2: ..." body, then a repetition-tail trim
                    // that drops a runaway "Speaker 1: Speaker 1: …" loop
                    // or short n-gram pile-up at the END of the chunk.
                    val rawCleaned = stripLeakedContext(
                        stripPreamble(accumulated.toString().trim())
                    )
                    val tailTrimmed = trimRepetitionTail(rawCleaned)
                    val out = scrubBareVocabEcho(tailTrimmed)
                    if (diarize && !Regex("(?im)^\\s*Speaker\\s+\\d+\\s*:").containsMatchIn(out)) {
                        Log.w(TAG, "  diarize=true but no 'Speaker N:' prefixes found in output. " +
                            "Gemma chunked-diar is best-effort; consider Whisper+embedding for harder audio.")
                    }
                    Log.i(TAG, "  gemma → ${out.take(120)}${if (out.length > 120) "…" else ""}")
                    out
                } finally {
                    // Always cancel the watchdog so coroutineScope can
                    // unblock and the function returns. Without this, the
                    // forever-running while(isActive) loop would hold the
                    // scope open after every chunk.
                    watchdogJob.cancel()
                }
            }
            return cleaned
        } finally {
            runCatching { conv.close() }
        }
    }

    /**
     * Build the per-call user message. Pattern follows Google's official
     * audio-ASR template:
     *
     *   "Transcribe the following speech segment in <LANGUAGE> into <LANGUAGE>
     *    text. Only output the transcription, with no newlines."
     *
     * Repeating the language name (in → into) is intentional and is the most
     * reliable language anchor on Gemma 3n/4 multimodal models. For mixed
     * language sets we let the model choose, but still constrain to the
     * candidate list. For Arabic we name the dialect explicitly to avoid
     * the model collapsing Gulf/Qatari speech to MSA.
     */
    private fun buildUserMessage(
        languages: List<String>,
        translateTo: String?,
        diarize: Boolean,
        previousContext: String?,
        speakerHints: List<SpeakerHint>? = null,
        overlapSeconds: Double = 0.0,
    ): String {
        val cleaned = languages.map { it.lowercase() }.distinct()
        val core = buildString {
            if (translateTo != null) {
                // Translate path. Source = `languages` set, target = translateTo.
                // Google's parallel template: "Translate the following speech
                // segment in {SOURCE} into {TARGET} text." with both sides named.
                // Works for any pair including "ar" → "uk" or "nl" → "en".
                val sourceName = when (cleaned.size) {
                    0 -> "the source language"
                    1 -> userMessageLanguageName(cleaned.first())
                    else -> "one of " + cleaned.joinToString(" or ") { userMessageLanguageName(it) }
                }
                val targetName = userMessageLanguageName(translateTo)
                append("Translate the following speech segment in ").append(sourceName)
                append(" into natural ").append(targetName).append(" text. ")
            } else {
                when (cleaned.size) {
                    0 -> append("Transcribe the following speech segment into text in its spoken language. ")
                    1 -> {
                        val name = userMessageLanguageName(cleaned.first())
                        append("Transcribe the following speech segment in ").append(name)
                        append(" into ").append(name).append(" text. ")
                    }
                    else -> {
                        val names = cleaned.joinToString(" or ") { userMessageLanguageName(it) }
                        append("Transcribe the following speech segment into text. ")
                        append("The speech is in ").append(names).append("; ")
                        append("detect which and transcribe in that language exactly as heard. ")
                    }
                }
            }
            // Punctuation directive moved EARLY in the user message and
            // restated with three concrete imperatives. Earlier wording
            // ("Use natural punctuation as a fluent writer would") sat
            // at the tail of the prompt and got diluted on longer
            // chunks — Gemma followed it for the first few sentences
            // then drifted to a flat unpunctuated stream. Positioning
            // immediately after the core verb anchors it for the whole
            // generation; concrete imperatives ("End sentences with…",
            // "Insert commas…", "Capitalize…") outperform vague style
            // hints. Suppressed under Verbatim mode (the system prompt
            // explicitly forbids punctuation there).
            val verbatim = promptStore?.verbatim?.value == true
            if (!verbatim) {
                append("Produce properly formatted written text with full ")
                append("punctuation: end every sentence with a period or ")
                append("question mark, insert commas at natural pauses and in ")
                append("lists, and capitalize sentence starts and proper nouns. ")
            }
            append("Only output the transcription, with no preamble and no surrounding quotes. ")
            append("When transcribing numbers, write the digits.")
        }
        // Continuity hint across the hard chunk boundary. Helps Gemma
        // carry named entities + pronouns forward instead of restarting
        // cold. Fenced inside an explicit CONTEXT block with a strong
        // "do not repeat this in the output" instruction — earlier
        // wording ("The previous chunk ended with: ...") was leaking
        // into transcripts as "Speaker 2: the previous chunk ended
        // with: their...". stripLeakedContext() is the second line of
        // defense; we still need this guarded prompt because once a
        // diarized Speaker N marker swallows leaked text, even aggressive
        // post-stripping risks chewing real audio words.
        val tail = previousContext?.takeIf { it.isNotBlank() }?.takeLast(250)
        val continuation = if (tail != null) {
            "\n\n=== CONTEXT (DO NOT TRANSCRIBE OR REPEAT THESE LINES) ===\n" +
                "Continuity hint — the previous chunk's final words were: $tail\n" +
                "=== END CONTEXT ===\n\n" +
                "Now transcribe ONLY the audio in the current chunk. " +
                "Do NOT echo, paraphrase, or quote any text from the CONTEXT block. " +
                "Do NOT write phrases like \"the previous chunk\", \"continuing\", " +
                "\"as I was saying\", or any reference to prior content."
        } else ""
        // Overlap-window guidance. Only emitted when the recap is large
        // enough that the model can reliably identify "the recap" as a
        // distinct opening segment to skip. With VAD-aligned chunking
        // the hard-cut fallback uses ~1 sec of overlap; experimentally
        // Gemma's instruction-following at that scale is unreliable, so
        // we rely on text-level boundary dedup downstream (see
        // dedupChunkBoundaries / findBoundaryOverlapDrop in
        // TranscriptionRunner.kt) rather than asking the model to skip.
        // Larger overlaps (legacy fixed-time 3 sec path, kept as a
        // last-ditch fallback) still get the explicit instruction.
        val overlapBlock = if (overlapSeconds >= 2.0) {
            val secStr = "%.1f".format(overlapSeconds)
            "\n\nThe audio begins with a $secStr-second recap of the previous chunk. " +
                "Begin your transcription at the first new sentence after the recap."
        } else ""
        // Diarization. Two flavours:
        //   - Hybrid (speakerHints != null): we've already clustered voices
        //     using sherpa-onnx on the full recording. Tell Gemma the
        //     concrete answer ("Speaker 1 is the voice at 0–4.2s, Speaker 2
        //     at 4.2–12s…") so its inline labels match sherpa's global
        //     numbering by construction. Identity is reconciled upstream
        //     against sherpa anyway, but agreement-by-default is much
        //     cleaner than override-on-conflict.
        //   - Pure Gemma (diarize=true, no hints): generic "label the
        //     speakers yourself" prompt. Used when sherpa's embedding
        //     model isn't installed or the user picked the fast mode.
        val diarBlock = when {
            speakerHints != null && speakerHints.isNotEmpty() -> buildString {
                append("\n\nThis audio has multiple speakers. Voice analysis of ")
                append("the full recording identifies the following speaker turns ")
                append("in this segment (times relative to the start of this segment):\n")
                for (h in speakerHints) {
                    append("- ").append("%.1f".format(h.startSec))
                    append("–").append("%.1f".format(h.endSec))
                    append("s: Speaker ").append(h.speakerId + 1).append("\n")
                }
                append("\nUse exactly these speaker labels (Speaker 1, Speaker 2, …) ")
                append("when transcribing. Prefix each spoken turn with the matching ")
                append("\"Speaker N: \" tag and put each turn on its own line. Do not ")
                append("invent new speakers; do not renumber. The speaker labels are ")
                append("part of the required output.")
            }
            diarize -> "\n\nThis audio has multiple speakers. Prefix each spoken segment " +
                "with \"Speaker 1: \", \"Speaker 2: \", etc. — assigned in the order " +
                "the speakers first appear. Use the same Speaker N for the same " +
                "person throughout. Put each speaker turn on its own line. The " +
                "speaker labels are part of the required output."
            else -> ""
        }
        return core + continuation + overlapBlock + diarBlock
    }

    /**
     * Language name as written in the user message. For Arabic we name the
     * dialect explicitly — research and Google's own model card note that
     * "Arabic (any dialect)" tends to collapse to MSA on Gulf/Levantine audio,
     * while naming the dialect ("Gulf Arabic") keeps the output in-dialect.
     */
    private fun userMessageLanguageName(code: String): String = when (code) {
        "ar" -> "Gulf Arabic (Qatari dialect)"
        "uk" -> "Ukrainian"
        "en" -> "English"
        "nl" -> "Dutch"
        else -> "the language with ISO code '$code'"
    }

    override suspend fun release(): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            engine?.close()
            engine = null
        }
    }

    /**
     * Text-only one-shot generation against the loaded Gemma 4 engine. Used by
     * `PostProcessor` to run preset prompts (Summary, Clean, Translate-polish)
     * over an already-transcribed text. Same engine handle as audio mode — no
     * extra model load, no second RAM allocation.
     */
    suspend fun generateText(
        systemInstruction: String,
        userText: String,
    ): Result<String> = withContext(Dispatchers.Default) {
        mutex.withLock {
            val e = engine ?: return@withLock Result.failure(
                IllegalStateException("Gemma 4 not loaded")
            )
            var conv: Conversation? = null
            try {
                conv = e.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(Content.Text(systemInstruction)),
                        // Slightly warmer than the ASR path. Post-processing
                        // (summarize, clean, rewrite-in-context) benefits from
                        // a bit of variation in word choice; the ASR path
                        // wants none of it. Named args + 3 fields only — the
                        // 4th `seed` arg is undocumented in 0.11.0.
                        samplerConfig = SamplerConfig(
                            topK = 40,
                            topP = 0.95,
                            temperature = 0.4,
                        ),
                    )
                )
                // Stream and accumulate. Same Flow shape as the audio path
                // so user-pressed Stop on a long preset (Context-aware
                // rewrite can take 30+ s) actually aborts the native worker
                // via cancelProcess() instead of running to completion in
                // the background.
                val acc = StringBuilder()
                try {
                    conv.sendMessageAsync(
                        Contents.of(Content.Text(userText)),
                        emptyMap(),
                    ).collect { msg ->
                        val delta = msg.contents.contents
                            .filterIsInstance<Content.Text>()
                            .joinToString("") { it.text }
                        acc.append(delta)
                    }
                } catch (ce: CancellationException) {
                    runCatching { conv.cancelProcess() }
                    throw ce
                }
                val text = acc.toString().trim()
                Log.i(TAG, "text-gen → ${text.take(120)}${if (text.length > 120) "…" else ""}")
                Result.success(text)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.e(TAG, "generateText failed", t)
                Result.failure(t)
            } finally {
                runCatching { conv?.close() }
            }
        }
    }

    /**
     * Best-effort cleanup pass over [DiarizationRunner]'s global clustering:
     * hands Gemma the cluster ids with their durations and per-segment-
     * average confidence, and asks which ones are actually the same
     * speaker misclustered as different ids. Constrained JSON decoding
     * (`ResponseFormat.json` + `enableResponseFormat = true`, LiteRT-LM
     * 0.17.0) keeps the output to exactly `{"merge": [[a,b], ...]}` — no
     * free text to parse out.
     *
     * Never fails the caller: a timed-out, malformed, or model-unavailable
     * response just means "no merge suggested" ([parseMergeMapResponse]
     * already drops anything invalid). This is a cleanup layer on top of
     * clustering that already works on its own, not a dependency.
     */
    suspend fun suggestSpeakerMergeMap(
        clusters: List<ClusterSummary>,
    ): List<Pair<Int, Int>> = withContext(Dispatchers.Default) {
        if (clusters.size < 2) return@withContext emptyList()
        mutex.withLock {
            val e = engine ?: return@withLock emptyList()
            var conv: Conversation? = null
            try {
                conv = e.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(Content.Text(MERGE_MAP_SYSTEM_PROMPT)),
                        // Deterministic — this is a structured-data task,
                        // not prose; no benefit to sampling variety.
                        samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.1),
                        enableResponseFormat = true,
                    )
                )
                val userMessage = clusters.joinToString("\n", prefix = "Clusters:\n") { c ->
                    "id=${c.id} duration=${"%.1f".format(c.durationSeconds)}s confidence=${"%.2f".format(c.confidence)}"
                }
                val acc = StringBuilder()
                try {
                    conv.sendMessageAsync(
                        Contents.of(Content.Text(userMessage)),
                        emptyMap(),
                        responseFormat = ResponseFormat.json(MERGE_MAP_JSON_SCHEMA),
                    ).collect { msg ->
                        acc.append(msg.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text })
                    }
                } catch (ce: CancellationException) {
                    runCatching { conv.cancelProcess() }
                    throw ce
                }
                val pairs = parseMergeMapResponse(acc.toString(), validIds = clusters.map { it.id }.toSet())
                Log.i(TAG, "speaker merge-map: ${clusters.size} clusters → ${pairs.size} merge pairs suggested")
                pairs
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "speaker merge-map suggestion failed, skipping merge", t)
                emptyList()
            } finally {
                runCatching { conv?.close() }
            }
        }
    }

    /**
     * Role + hard rules. Stays in the systemInstruction channel; the user
     * message at run-time is just "Transcribe." or "Translate." so the model
     * has nothing to echo back.
     *
     * If a [PromptStore] is wired in, the prompt is the user-editable one from
     * Settings (with `{language_hint}` substituted). Otherwise we fall back to
     * the same defaults baked into PromptStore.
     *
     * [diarize] true appends inline-speaker-label guidance to the system
     * prompt — Gemma will produce "Speaker 1: ...", "Speaker 2: ..." prefixes
     * which our caller parses out.
     */
    private fun buildSystemInstruction(
        languages: List<String>,
        translate: Boolean,
        diarize: Boolean = false,
    ): String {
        promptStore?.let { return it.render(translate = translate, languages = languages, diarize = diarize) }
        // Standalone fallback identical to PromptStore defaults — used in
        // test environments without a wired-up PromptStore.
        val cleaned = languages.map { it.lowercase() }.distinct()
        val langHint = when {
            cleaned.isEmpty() ->
                "The audio may be in any language. Detect it from the audio itself."
            cleaned.size == 1 -> when (cleaned.first()) {
                "ar" -> "The audio is in Arabic (any dialect, including Gulf/Qatari)."
                "uk" -> "The audio is in Ukrainian."
                "en" -> "The audio is in English."
                "nl" -> "The audio is in Dutch (Nederlands)."
                else -> "The audio is in the language identified by ISO code '${cleaned.first()}'."
            }
            else -> {
                val names = cleaned.map { langDisplay(it) }
                "The audio is in one of: ${names.joinToString(", ")}. Detect which " +
                    "language is being spoken and transcribe in that language."
            }
        }
        return if (translate) {
            PromptStore.DEFAULT_TRANSLATE.replace("{language_hint}", langHint)
        } else {
            PromptStore.DEFAULT_TRANSCRIBE.replace("{language_hint}", langHint)
        }
    }

    private fun langDisplay(code: String): String = when (code) {
        "ar" -> "Arabic (any dialect including Gulf/Qatari)"
        "uk" -> "Ukrainian"
        "en" -> "English"
        "nl" -> "Dutch"
        else -> "the language with ISO code '$code'"
    }

    /**
     * Strips leakage patterns Gemma sometimes adds despite explicit instructions
     * not to. Conservative: only removes a known-bad prefix (or surrounding
     * quotes) and leaves real content alone.
     */
    private fun stripPreamble(text: String): String {
        if (text.isEmpty()) return text
        var s = text.trim()

        // Drop surrounding straight or curly quotes (single pair only).
        val quotePairs = listOf("\"" to "\"", "“" to "”", "‘" to "’", "'" to "'")
        for ((open, close) in quotePairs) {
            if (s.startsWith(open) && s.endsWith(close) && s.length > open.length + close.length) {
                s = s.substring(open.length, s.length - close.length).trim()
                break
            }
        }

        // Strip a leading "Here is/are ... :" / "Sure, ..." / "Transcription:" /
        // "Translation:" preamble, including its trailing colon or comma. We
        // only strip up to the first newline or sentence boundary so we don't
        // accidentally eat real content.
        val preambleRegex = Regex(
            "^(?:sure[!,. ]*\\s*|okay[!,. ]*\\s*|of course[!,. ]*\\s*|" +
                "here(?:'s| is| are)[^\\n:]*[:\\.\\-—]\\s*|" +
                "the (?:transcription|translation)[^\\n:]*[:\\.\\-—]\\s*|" +
                "transcription[:\\.\\-—]\\s*|translation[:\\.\\-—]\\s*)",
            RegexOption.IGNORE_CASE,
        )
        s = preambleRegex.replace(s, "")
        return s.trim()
    }

    /**
     * Scrub fragments of our own cross-chunk CONTEXT prompt that Gemma
     * occasionally echoes back inside its transcript output. Targets
     * exact phrasings the chunk-continuation prompt uses, so it won't
     * destroy real audio words even when speakers say "the previous
     * chunk" out loud (rare edge case; if it bites we can tighten).
     *
     * Runs once over the whole chunk output BEFORE parseGemmaDiarSegments,
     * so a leak landing inside a "Speaker N: ..." body is removed
     * before diarization splitting. Pattern set:
     *   - "=== CONTEXT ... === END CONTEXT ===" fenced block
     *   - "[continuing as Speaker N: ...]" wrapper from older prompts
     *   - "the previous chunk ended with: ..." up to next sentence end
     *   - "continuity hint — the previous chunk's final words were: ..."
     *   - "continuing from where it stopped" + nearby phrasing
     */
    private fun stripLeakedContext(text: String): String {
        if (text.isEmpty()) return text
        var s = text
        // Whole fenced CONTEXT block. DOTALL so the .* spans newlines.
        // `=+` instead of `===` to tolerate Gemma inflating the fence
        // marker ("==== END CONTEXT ====" — observed in the wild —
        // wouldn't match the previous strict-3-equals pattern).
        s = s.replace(
            Regex("=+\\s*CONTEXT[\\s\\S]*?=+\\s*END CONTEXT\\s*=+\\s*", RegexOption.IGNORE_CASE),
            "",
        )
        // Orphan END CONTEXT fence — Gemma sometimes echoes ONLY the
        // closing marker without the opening block (the opening
        // narration gets folded into adjacent transcript content).
        // Without this pattern, the previous block-only regex misses
        // the standalone `==== END CONTEXT ====` line and it lands in
        // the user-visible transcript. Match any run of `=` around
        // both forms of the marker.
        s = s.replace(
            Regex("=+\\s*END CONTEXT\\s*=+\\s*", RegexOption.IGNORE_CASE),
            "",
        )
        // Orphan opening fence with no closing — same failure mode,
        // mirror-image. The `DO NOT TRANSCRIBE` parenthetical is
        // included in the start marker we emit, so we anchor on it
        // (specific enough to avoid false positives on coincidental
        // "context" mentions in real speech).
        s = s.replace(
            Regex(
                "=+\\s*CONTEXT\\s*\\(\\s*DO NOT (?:TRANSCRIBE|REPEAT)[^=\\n]*?\\)?\\s*=+\\s*",
                RegexOption.IGNORE_CASE,
            ),
            "",
        )
        // Bracketed "[continuing as Speaker N: ...]" hold-over from the
        // earlier prompt variant. Leave the rest of the line intact.
        s = s.replace(
            Regex("\\[continuing as Speaker\\s+\\d+\\s*:[^\\]]*\\]\\s*", RegexOption.IGNORE_CASE),
            "",
        )
        // "Continuity hint — the previous chunk's final words were: ..."
        // through to the next sentence break or end. Anchored on our
        // own phrasing so we don't trim audio that happens to mention
        // "continuity" colloquially.
        s = s.replace(
            Regex(
                "continuity\\s+hint[^\\n.]*?the previous chunk[^\\n.]*?(?:[.\\n]|$)\\s*",
                RegexOption.IGNORE_CASE,
            ),
            "",
        )
        // Bare "the previous chunk ended with: '...'" — matches both the
        // old prompt's phrasing and Gemma's paraphrases. Stops at the
        // first sentence end so it doesn't eat the rest of the turn.
        s = s.replace(
            Regex(
                "the previous chunk ended with[^\\n.]*?(?:[.\\n]|$)\\s*",
                RegexOption.IGNORE_CASE,
            ),
            "",
        )
        // "Continue naturally from where it stopped" boilerplate.
        s = s.replace(
            Regex(
                "continue naturally from where (?:it stopped|the previous chunk[^\\n.]*)\\.?\\s*",
                RegexOption.IGNORE_CASE,
            ),
            "",
        )
        // User-message prompt boilerplate that Gemma sometimes echoes
        // back verbatim into the output. Matches the exact phrasings
        // emitted by buildUserMessage above — "Transcribe the following
        // speech segment in <LANG> into <LANG> text. ...", "Translate
        // the following speech segment in <SRC> into <TGT> text.",
        // "Only output the transcription...", "When transcribing
        // numbers, write the digits." Live transcription showed this
        // most aggressively because each chunk is short and Gemma has
        // less audio to anchor against; file transcription gets it too
        // but rarely. Each strip is anchored on our exact wording so
        // colloquial speech that uses the same words won't be eaten.
        s = s.replace(
            Regex(
                "(?:transcribe|translate) the following speech segment[^.\\n]*?(?:[.\\n]|$)\\s*",
                RegexOption.IGNORE_CASE,
            ),
            "",
        )
        s = s.replace(
            Regex(
                "only output the transcription[^.\\n]*?(?:[.\\n]|$)\\s*",
                RegexOption.IGNORE_CASE,
            ),
            "",
        )
        s = s.replace(
            Regex(
                "when transcribing numbers[^.\\n]*?(?:[.\\n]|$)\\s*",
                RegexOption.IGNORE_CASE,
            ),
            "",
        )
        // "The speech is in <X or Y>; detect which and transcribe..."
        // — the constrained-auto language hint that lands in the user
        // message when multiple source languages are picked.
        s = s.replace(
            Regex(
                "the speech is in [^.\\n]*?detect which and transcribe[^.\\n]*?(?:[.\\n]|$)\\s*",
                RegexOption.IGNORE_CASE,
            ),
            "",
        )
        // System-prompt "Additional instructions" extras that Gemma
        // echoes verbatim when the audio chunk is silent / low-energy.
        // Live transcription showed the vocabulary list leaking into
        // the LAST HEARD preview during silences — the model was
        // padding the output with system-prompt content because it
        // had no audio to transcribe. All four patterns are anchored
        // on the exact opening phrasings emitted by
        // PromptStore.buildExtras(), so real speech that happens to
        // use these words isn't eaten.
        s = s.replace(
            Regex(
                "Vocabulary \\(spell exactly[^:]*\\):[^\\n]*?(?:[.\\n]|$)\\s*",
                RegexOption.IGNORE_CASE,
            ),
            "",
        )
        s = s.replace(
            Regex(
                "Remove filler words:[^\\n]*?(?:[.\\n]|$)\\s*",
                RegexOption.IGNORE_CASE,
            ),
            "",
        )
        s = s.replace(
            Regex(
                "Verbatim mode:[^\\n]*?(?:[.\\n]|$)\\s*",
                RegexOption.IGNORE_CASE,
            ),
            "",
        )
        s = s.replace(
            Regex(
                "Tone:\\s*(?:formal|casual|enthusiastic|technical|neutral)\\b[^\\n]*?(?:[.\\n]|$)\\s*",
                RegexOption.IGNORE_CASE,
            ),
            "",
        )
        s = s.replace(
            Regex(
                "^\\s*Additional instructions:\\s*$",
                RegexOption.MULTILINE,
            ),
            "",
        )
        // PostProcessor's parallel phrasing — same vocabulary list,
        // different wrapper text ("...when these appear:"). Used by
        // the CLEAN / SUMMARY / TRANSLATE-AND-POLISH presets; echoed
        // back when the input is too short for meaningful processing.
        s = s.replace(
            Regex(
                "Vocabulary \\(spell exactly when these appear\\):[^\\n]*?(?:[.\\n]|$)\\s*",
                RegexOption.IGNORE_CASE,
            ),
            "",
        )
        // Empty "Speaker N:" turns left behind after stripping their
        // entire body. Collapse them so parseGemmaDiarSegments doesn't
        // emit phantom turns.
        s = s.replace(
            Regex("(?im)^\\s*Speaker\\s+\\d+\\s*:\\s*$", RegexOption.MULTILINE),
            "",
        )
        return s.trim()
    }

    /**
     * Output-side safety net for the "bare vocabulary list" leak.
     *
     * Failure mode: on silent or near-silent audio Gemma sometimes
     * echoes the user's vocabulary list back as a bare comma-separated
     * sequence — e.g. `"Yuri, Len, ABCI, SNB, FAB."` — with no
     * surrounding phrase that [stripLeakedContext] can anchor on. The
     * silence pre-skip in TranscriptionRunner catches most cases at
     * the audio level, but a chunk with very low SNR (some background
     * noise but no actual speech) can sneak through and trigger this.
     *
     * Conservative rule: drop the chunk's text only if EVERY non-
     * trivial token belongs to the user's vocabulary AND there are
     * ≥3 such tokens. Mixed output with even a single non-vocab word
     * ("Then Yuri called Len about ABCI…") is kept — that's real
     * prose. The bar is intentionally high to avoid false-positives
     * on conversations that legitimately list domain terms.
     */
    private fun scrubBareVocabEcho(text: String): String {
        val store = promptStore ?: return text
        val raw = store.vocabulary.value
        if (raw.isBlank()) return text
        val trimmed = text.trim()
        // Short outputs only — leaks fire when there was no real
        // speech, so the echo is typically a single line. A 500-char
        // chunk has too much real content for this scrubber to
        // possibly improve.
        if (trimmed.length > 200) return text
        // Strip diarization-output framing before vocabulary check.
        // The leak commonly appears as `"Speaker 1: Yuri, Len, ABCI."`
        // — without removing the speaker tag first, the scrubber's
        // "every token is vocab" guard fails on the `Speaker` and `1`
        // tokens that bracket the actual echoed list. Drop every
        // `Speaker N:` marker and surrounding whitespace so we score
        // only the body.
        val depspeaked = trimmed
            .replace(Regex("\\bSpeaker\\s+\\d+\\s*:\\s*", RegexOption.IGNORE_CASE), "")
            .trim()
        if (depspeaked.isEmpty()) return text
        val vocab = raw.split('\n', ',')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toHashSet()
        if (vocab.isEmpty()) return text
        val tokens = depspeaked
            .split(Regex("[\\s,]+"))
            .map { it.trim().trim('.', ',', ';', ':', '!', '?', '"', '\'', '(', ')').lowercase() }
            .filter { it.isNotEmpty() }
        if (tokens.size < 3) return text
        val nonVocab = tokens.filterNot { it in vocab }
        if (nonVocab.isEmpty()) {
            Log.w(TAG, "  scrubbing bare vocab echo: ${tokens.size} tokens all in vocab " +
                "(`${trimmed.take(80)}${if (trimmed.length > 80) "…" else ""}`)")
            return ""
        }
        return text
    }

    /** Build a minimal 16-bit PCM WAV in memory from float samples in [-1, 1]. */
    private fun pcmFloatToWavBytes(samples: FloatArray, sampleRate: Int): ByteArray {
        val pcmLen = samples.size * 2
        val total = 44 + pcmLen
        val out = ByteArrayOutputStream(total)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(total - 8)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcmLen)
        out.write(header.array())
        val pcm = ByteArray(pcmLen)
        var j = 0
        for (s in samples) {
            val clamped = s.coerceIn(-1f, 1f)
            val v = (clamped * 32767f).toInt()
            pcm[j] = (v and 0xff).toByte()
            pcm[j + 1] = ((v shr 8) and 0xff).toByte()
            j += 2
        }
        out.write(pcm)
        return out.toByteArray()
    }

    /**
     * Detect a runaway-repetition decoding loop in the streaming
     * accumulator. Triggers on either of:
     *
     *   1. SHORT-window check: the last [REPETITION_SHORT_WINDOW_CHARS]
     *      characters contain ≥3 back-to-back `Speaker N:` markers with
     *      ≤30-char gaps. Fires earliest — typically within ~50 chars
     *      of the loop starting, which is well before any 5-min timeout.
     *   2. LONG-window check (only after enough output): in the last
     *      [REPETITION_LONG_WINDOW_CHARS] characters, the most-common
     *      8-char n-gram covers >50% of the window. Catches generic
     *      "you know, you know, you know,…" style fragment loops that
     *      aren't speaker-markers.
     *
     * Both checks are O(window-length) — cheap enough to run on every
     * delta. False-positive risk on natural-speech repetition is
     * mitigated by requiring (a) the SAME fragment, byte-identical
     * (Speaker markers are too) and (b) extremely tight gaps. A speaker
     * saying "yeah, yeah, yeah" is the same fragment but lands inside
     * a larger context, so the long-window n-gram coverage stays well
     * under 50%.
     */
    private fun isRepetitionLoop(buf: StringBuilder): Boolean {
        // SHORT-window: Speaker N: loop. Earlier version triggered on any
        // 3 back-to-back markers in 80 chars, which false-positived on
        // legitimate fast-paced exchanges ("Good day." / "Yeah, good day."
        // / "Thanks. Bye." — three short turns, three markers, but real
        // content). The real loop signature is markers with EMPTY bodies
        // (trailing partial: "...Speaker 2:" with nothing after) or
        // IDENTICAL bodies repeating. Require at least
        // REPETITION_THRESHOLD-1 such "loop hits" in the window.
        if (buf.length >= REPETITION_SHORT_WINDOW_CHARS) {
            val shortTail = buf.substring(buf.length - REPETITION_SHORT_WINDOW_CHARS)
            val matches = SPEAKER_MARKER_RE.findAll(shortTail).toList()
            if (matches.size >= REPETITION_THRESHOLD) {
                val bodies = Array(matches.size) { i ->
                    val start = matches[i].range.last + 1
                    val end = if (i + 1 < matches.size)
                        matches[i + 1].range.first else shortTail.length
                    shortTail.substring(start, end).trim()
                }
                var loopHits = 0
                for (i in bodies.indices) {
                    val isEmpty = bodies[i].isEmpty()
                    val isDup = i > 0 && bodies[i].isNotEmpty() && bodies[i] == bodies[i - 1]
                    if (isEmpty || isDup) loopHits++
                }
                if (loopHits >= REPETITION_THRESHOLD - 1) return true
            }
        }
        // LONG-window: generic n-gram pile-up. Requires more accumulated
        // output before it can fire — we don't want to false-positive on
        // a short opening line.
        if (buf.length >= REPETITION_LONG_WINDOW_CHARS) {
            val longTail = buf.substring(buf.length - REPETITION_LONG_WINDOW_CHARS)
            val ngramLen = 8
            val counts = HashMap<String, Int>()
            for (i in 0..longTail.length - ngramLen) {
                val g = longTail.substring(i, i + ngramLen)
                if (g.trim().length < 4) continue
                counts[g] = (counts[g] ?: 0) + 1
            }
            val topCount = counts.values.maxOrNull() ?: 0
            if (topCount * ngramLen > REPETITION_LONG_WINDOW_CHARS * 0.5) {
                return true
            }
        }
        return false
    }

    /**
     * Trim a runaway-repetition tail from chunk output before returning
     * it. Walks back from the end of [text] until we find a chunk of
     * content that ISN'T the loop fragment; everything from that point
     * onward gets dropped.
     *
     * Two patterns we recognise:
     *   - Trailing `Speaker N: Speaker N: Speaker N: …` (the dominant
     *     observed loop). Trim everything from the first marker of the
     *     consecutive run onward.
     *   - Trailing n-gram pile-up (any short fragment repeated 4+ times
     *     consecutively in the last 200 chars). Trim from the first
     *     instance of the dominant fragment.
     *
     * Conservative: if neither pattern matches, return the input
     * unchanged. The goal is to salvage real content; better to leave
     * a small loop tail than to nibble real audio.
     */
    private fun trimRepetitionTail(text: String): String {
        if (text.length < 30) return text
        // Speaker-marker tail trim. Only remove the LOOP tail —
        // trailing markers whose bodies are empty or duplicate of the
        // previous body. Real fast exchanges ("Good day." / "Yeah, good
        // day." / "Thanks Paul. Bye.") have unique non-empty bodies and
        // must be preserved.
        val matches = SPEAKER_MARKER_RE.findAll(text).toList()
        if (matches.size < 2) return text
        val bodies = Array(matches.size) { i ->
            val start = matches[i].range.last + 1
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else text.length
            text.substring(start, end).trim()
        }
        // The loop signal is the LAST body being empty (the trailing
        // partial marker that fired the watchdog) OR the last few bodies
        // being identical. Walk back from the end through markers whose
        // body is empty OR duplicates the next body; stop at the first
        // marker with unique non-empty content.
        if (bodies.last().isEmpty() ||
            (bodies.size >= 2 && bodies.last() == bodies[bodies.size - 2])
        ) {
            var runStart = matches.size - 1
            for (i in matches.size - 2 downTo 0) {
                val isEmpty = bodies[i].isEmpty()
                val isDup = bodies[i].isNotEmpty() && bodies[i] == bodies[i + 1]
                if (isEmpty || isDup) runStart = i
                else break
            }
            val cutAt = matches[runStart].range.first
            val dropped = text.length - cutAt
            val removedMarkerCount = matches.size - runStart
            Log.w(TAG, "  trimming repetition tail: $removedMarkerCount trailing " +
                "empty/duplicate Speaker markers from offset $cutAt " +
                "($dropped chars dropped)")
            return text.substring(0, cutAt).trimEnd()
        }
        return text
    }

    companion object {
        private const val TAG = "Gemma4Backend"

        /** System prompt for [suggestSpeakerMergeMap]. Output shape is enforced by the JSON schema, not this text. */
        private const val MERGE_MAP_SYSTEM_PROMPT =
            "You are cleaning up speaker-diarization output. You will be given a list of " +
                "speaker clusters, each with an id, total speaking duration, and average " +
                "confidence. Some clusters may actually be the SAME speaker, split into " +
                "separate ids by clustering error — this is common for short or low-" +
                "confidence clusters. Only suggest merging two clusters if you have good " +
                "reason to believe they are the same speaker; a short cluster is not, by " +
                "itself, reason to merge it with a longer one. If you are unsure, do not " +
                "suggest a merge. Never suggest merging two clusters that are both long " +
                "and high-confidence — those are almost always genuinely different " +
                "speakers. Respond with only the merge pairs; an empty list is a valid " +
                "and often correct answer."

        /**
         * Audio per inference. Gemma 4 internally caps at ~30 sec; we leave
         * 2 sec of headroom so end-of-clip tokenization doesn't get truncated.
         * Bigger chunks mean fewer hard boundaries (which Gemma reads as
         * sentence breaks) and better quality — keep this as close to 30 as
         * we dare. Lower it if the model starts truncating output.
         */
        const val CHUNK_SECONDS = 28

        /**
         * Short tail window for the Speaker-marker loop check. 80 chars
         * is roughly 3 back-to-back `"Speaker 1: "` markers (11 chars
         * each with a space) — exactly the size at which the loop is
         * unambiguous but the user-visible preview has barely had time
         * to scroll. Lower than the previous 240-char window so we
         * fire well before the user notices the preview is wedged.
         */
        private const val REPETITION_SHORT_WINDOW_CHARS = 80

        /**
         * Long tail window for the generic n-gram pile-up check. Wider
         * because we need enough text to compute a meaningful
         * fragment-coverage ratio without false-positives on short
         * legitimate openings.
         */
        private const val REPETITION_LONG_WINDOW_CHARS = 200

        /**
         * Number of back-to-back repeated fragments required before we
         * declare a runaway loop. 3 catches the user-reported
         * `"Speaker 1: Speaker 1: Speaker 1: Speaker 1:"` case quickly
         * while staying clear of legitimate `"yeah yeah"` repetition
         * (which is typically 2 and isn't structurally the same
         * fragment).
         */
        private const val REPETITION_THRESHOLD = 3

        /**
         * Maximum character gap between successive copies of a fragment
         * before we stop counting them as "back-to-back." Larger gaps
         * mean real content sits between them, so it's not a loop.
         */
        private const val REPETITION_FRAGMENT_MAX_GAP = 30

        /**
         * Pre-compiled `Speaker N:` marker regex. Reused by both the
         * online loop detector and the offline tail-trimmer; compiling
         * once keeps the per-delta check allocation-free.
         */
        private val SPEAKER_MARKER_RE = Regex(
            "\\bSpeaker\\s+\\d+\\s*:",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Max milliseconds we wait between successive deltas from
         * sendMessageAsync before declaring a silent engine wedge.
         * Gemma 4 E2B on a flagship phone emits at minimum a few tokens
         * per second once it starts decoding (~50–100 ms between
         * deltas); even on a stalled GPU we'd still expect SOMETHING
         * within 60 s. Beyond that the engine is wedged — most
         * commonly because the input audio is at or near the 30-second
         * hard limit. We abort the worker, the chunk loop sees a
         * timeout-equivalent and triggers its retry path.
         */
        private const val NO_DELTA_WEDGE_MS = 60_000L

        /**
         * Grace period AFTER the watchdog calls cancelProcess(). If
         * the collect still hasn't unblocked by then, the watchdog
         * escalates to a hard `conv.close()`. Observed for prefill
         * stalls on LiteRT-LM: cancelProcess returns immediately but
         * the wedged native worker doesn't exit, so the Flow never
         * completes and our code stays blocked. The hard close forces
         * the callback channel down and the collect throws.
         */
        private const val NO_DELTA_HARD_CLOSE_MS = 30_000L
    }
}

/**
 * Sentinel thrown from inside the streaming `collect { }` lambda when
 * runaway repetition is detected. Caught by the surrounding try/catch
 * and converted into a CancellationException so the upstream chunk
 * loop sees a "chunk failed" signal and triggers its engine-reload +
 * retry path (vs sitting wedged for the full 5-minute outer timeout).
 *
 * Top-level rather than nested so it's reachable from the catch block
 * without an `Outer.RepetitionLoopException` qualifier.
 */
private class RepetitionLoopException : RuntimeException("repetition loop detected")
