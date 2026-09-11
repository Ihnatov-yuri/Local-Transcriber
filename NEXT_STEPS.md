# What's next

> **September 2026:** the current roadmap is [docs/PLAN-2026-09.md](docs/PLAN-2026-09.md). Item A below (chunked diarization) shipped in `e98ce6d`; the rest is folded into that plan.

Live planning doc — features that didn't fit the current sprint, sorted by ROI.
Everything above the line in [README.md](README.md) is shipped.

## Recently shipped (this session — May 2026)

**Audio chunking + boundary handling**
- VAD-aligned chunk cuts with **asymmetric flex** — silences can only land at-or-before the nominal target, never past it. Caps chunks at 28 s and eliminates the deterministic prefill wedge at 30 s.
- **Adaptive RMS threshold** in silence scanning (`p20 × 2`, clamped 0.005..0.02) — works on noisy recordings.
- **Surgical boundary dedup**: only the actual chunk-seam segments are dedup candidates (time-based detection, ±2 s tolerance). The matcher allows up to 3 mismatched tokens at the start of the next chunk before the matching run (handles paraphrased boundaries like `"literally refused to" / "Really refused to see him"`).
- **Per-cut overlap decisions**: silence-aligned cuts emit `overlapSamples=0` (no double-transcription); hard-cut fallbacks bridge with 1 s.
- **Silence pre-skip**: chunks below RMS 0.002 (~−54 dBFS) skip Gemma inference entirely — kills vocab-echo leaks at the source.

**Gemma reliability**
- **Silent-wedge watchdog**: 60 s no-delta → soft `cancelProcess()`; +30 s grace → hard `conv.close()`. Replaces the previous 5-min outer-timeout wait.
- **Outer chunk timeout** lowered 5 min → 2 min (watchdog is the primary defense now).
- **Smarter retry on timeout**: re-sends 24 s of audio (truncated) with `diarize=false` + no `previousContext` to break deterministic attractors.
- **Runaway-repetition detector + tail-trim** with body-content check (empty/duplicate body required, not just back-to-back markers). Avoids false-positives on fast natural exchanges.
- **Punctuation enforcement**: positive instruction moved to the *opening* of the user message and added as a system-prompt rule. Migration auto-clears the previous default for installed users.
- **Output scrubbers** extended for: bare vocab-list echoes (no `Vocabulary:` prefix), system-prompt extras (`Remove filler words:`, `Verbatim mode:`, `Tone:`), orphan `END CONTEXT` fences with `=+` count, and PostProcessor variants.

**Diarization**
- Catalog: **CAM++ multilingual zh+en** (28 MB) + **WeSpeaker ResNet221-LM** (95 MB) added alongside CAM++ English.
- **Settings → Models → Active embedding model** picker (radio selector) when ≥2 are installed. User preference overrides the priority cascade.
- **ORT 1.24.3 versioned-symbol fix**: sherpa-onnx 8.5.1 requires `OrtGetApiBase@VERS_1.24.3` (verified via `llvm-readelf --dyn-syms`). Microsoft 1.26.0 / 1.19.2 and the bundled lib-onnx 6.16.7 all expose wrong versions. Pin: `onnxruntime = "1.24.3"` + exclude `com.bihe0832.android:lib-onnx`. Diarization actually loads now.
- **Memory pre-check** before sherpa decode: compute `duration × 16k × 4 × 3` peak, compare to `Runtime.maxMemory() − used`. If over 70% of budget, skip sherpa with a friendly Stage event and fall through to Gemma-only labels.
- **`largeHeap="true"`** in manifest — 256 → 512 MB heap. Handles ~45-min files with hybrid.
- **Diarization cancellation**: blocking `diar.process(samples)` + sibling coroutine that calls `diar.release()` on parent cancellation. ≤ few-seconds Stop latency.
- **Heartbeat progress** every 5 s during clustering (`Identifying speakers · 35s elapsed`) — no real percentage available without `processWithCallback`, which crashes the JNI on capturing Kotlin lambdas in sherpa-onnx 8.5.1.
- **DiarizationRunner load-order trick removed** (`System.loadLibrary("onnxruntime")` and `OrtEnvironment.getEnvironment()`) — irrelevant once the ORT version matches.

**UI / editorial polish**
- TabStrip split into two rows (tabs + view-controls) so READ ⤢ never overflows on narrow phones.
- Post-process preset grid shown **only in fullscreen READ** mode — normal Detail view stays minimal.
- Help-modal pattern for RUN options rows (`?` chip → per-row explanation dialog).
- HYBRID row shows the active embedding model in the value (`ON · WESPEAKER`).
- Quick-Fill Vocabulary cleaned up to a single ledger row per domain with `+ N TERMS` / `REMOVE` toggle + `?` for sources.
- Vocab-section language picker decoupled from per-recording language pick.

## Open issues to tackle next

### A. Chunked diarization for files > 45 min ★ highest payoff

**Problem.** The memory pre-check (and reality) currently skips hybrid for ~1 h+ files. Sherpa needs the full mono-float buffer in heap (≈ 220 MB for 1 h, peak ≈ 660 MB with codec/growth overhead — past our 512 MB largeHeap).

**Plan.** Process audio in 5-min windows independently, merge clusters across windows using cosine similarity on the longest-segment-per-cluster as canonical vectors. Sidesteps the full-file heap requirement and gives linear wall-clock cost.

**Bonus.** Solves the "new speaker appears late in the meeting" problem — clusters update per window, so a speaker first appearing at minute 47 is captured.

**Effort.** Medium. Couple of hours; wrap sherpa's `process()` per window, track speaker centroids in Kotlin, do online merge.

### B. Diarization quality on Arabic (over-segmentation) ★ user-impacting

**Observed.** 30-min Khaleeji Arabic recording with two speakers → sherpa CAM++-English produces 7 clusters (5+ false speakers). 25/52 hybrid-reconcile conflicts (Gemma's inline labels mostly overridden by sherpa).

**Cause.** CAM++ was trained on VoxCeleb (mostly Western voices). Arabic phoneme/prosody distribution + Pyannote's tight segmentation on overlap-heavy speech → intra-speaker embedding variance > inter-speaker variance. Default clustering threshold (0.5) was tuned for English.

**Mitigations**:
1. **Force `Expected Speakers = N` in RUN options** before transcribing — `FastClusteringConfig(numClusters=N)` collapses over-segmentation deterministically. *Already shipped; surface this as the recommended fix in UI tooltip.*
2. Tune clustering threshold per-language. Expose as Settings knob, default 0.7 for non-English audio.
3. Lower `hybrid hints cap` 12 → 8 to reduce prompt-size pressure on Gemma's prefill (correlates with timeout chunks).
4. When sherpa over-segments dramatically (e.g. ≥6 clusters), warn the user in the diarization Stage event with a "tap Expected Speakers" hint.

### C. Whisper streaming for long files

**Why.** Gemma path now streams (~6 MB peak heap regardless of file length), but Whisper still materializes the full audio. A 3-hour podcast OOMs on Whisper.

**How.** Split at VAD boundaries (we already have `AudioDecoder.scanSilences` + `computeCutPoints`), run `whisper_full` on each block, stitch with offsets. Mirror Mac app's `transcribe_mixed`.

**Effort.** 1–2 days. The chunking primitives are already in place from the Gemma work; the new bit is per-chunk Whisper invocation + stitching.

### D. Library shows job state per row

**Why.** Bulk-enqueue several transcriptions and you can't see which is in flight without opening each Detail screen.

**How.** Project `transcriptionJobManager.statuses` into `RecordingsListScreen`'s VM; render a small chip on each row's right edge: "Queued", "Transcribing 4/12", "Waiting for charger".

**Effort.** ~3 hours.

### E. Mid-chunk Stop button feedback

**Why.** Stop now works end-to-end (channelFlow cancellation → `cancelProcess()` / `conv.close()` / `diar.release()`) but the UI has no transient "Stopping…" state.

**How.** Add a `Stopping` enum value to `LiveStatus` / `JobStatus`. Bridge it on Stop tap; clear when cancellation propagates.

**Effort.** ~1 hour.

## Medium-value

- **Auto-classify recording type** (Meeting / Interview / Note / Idea) — one-shot Gemma call after auto-title, picks from a fixed list. Drives downstream preset suggestions.
- **Per-recording preset overrides** — small `RecordingOverrides` Room column (JSON blob). UI on Detail: "Override defaults" sheet.
- **Backtrack for live transcription** — Wispr Flow's "scratch that" feature. VAD-aware live worker tracks last N segments; on long pause + similar opening words, replace instead of append. Heuristic-heavy.
- **Real markdown library** — current `MarkdownText` doesn't handle tables, fenced code, blockquotes. Switch to `commonmark-android` or `compose-richtext`. APK +1–2 MB.
- **Background WorkManager for long jobs** — covers process death. (DB-backed FIFO queue already covers most "lost work" cases.)
- **Live transcription cross-chunk context** — file path passes the tail; live path starts each chunk cold and wobbles on language detection. ~30 min.
- **Hybrid diarization on by default** once over-segmentation is fixed (B above) and chunked mode (A) ships for long files.

## Lower priority

- **Voice keyboard mode** — Wispr Flow's main schtick. Distinct product surface; defer or fork.
- **Per-language preset import** from Mac app's `config.yaml` (VAD onset/offset, alignment model).
- **Shared team dictionary/snippets** — out of scope for a personal sideload app.
- **Real markdown export from share** — `text/markdown` MIME type. Trivial once the markdown library lands.
- **iOS port** — Kotlin core could move to KMP. Not in this lifetime.

## Considered and decided against

### Stereo capture from the phone mic
**Verdict:** Not worth it. Whisper and Gemma 4 both expect mono; we'd record 2× bytes and immediately downmix. On most phones the L/R channels exposed via `CHANNEL_IN_STEREO` aren't independent capsules — the SoC has already beamformed/noise-cancelled them, and the mic spacing (1–4 cm) is too narrow for spatial separation at speech wavelengths.

**Re-open this only if:** someone wants to transcribe **externally-recorded** files where channels are hard-panned by source (e.g. dual-lav interview, OBS multi-track capture). The right shape for that is a per-channel *import* option in `AudioDecoder.decodeChunked` (split → run each as a separate "speaker"), not changes to `WavRecorder`.

### ERes2Net for speaker embeddings
**Verdict:** Skip. Drop-in compatible with sherpa-onnx, but the English ONNX export is actually *worse* than CAM++ on VoxCeleb-O (0.84 vs 0.65 EER). The strong ERes2Net variants are zh-CN-trained and don't help English/Arabic content. WeSpeaker ResNet221-LM remains the best English option in the catalog. Detailed comparison logged in [docs/GEMMA4_INTEGRATION.md](docs/GEMMA4_INTEGRATION.md).

### `processWithCallback` for sherpa progress + cancellation
**Verdict:** Not usable on sherpa-onnx 8.5.1. The JNI lookup for `Function3.invoke(IIJ)Ljava/lang/Integer;` is broken when R8/D8 generates synthetic-lambda classes (which is whenever the Kotlin lambda captures any state). Fatal crash on first callback fire. Use the blocking `process()` path + a sibling watcher coroutine that calls `diar.release()` on cancellation. Heartbeat progress instead of real percentage.

## Known limits / open bugs

- **Whisper path OOMs** on multi-hour audio (see C above).
- **Audio backend pinned to CPU** for Gemma 4 E2B — model constraint, still enforced by SDK v0.11.0, no upstream fix planned (see LiteRT-LM issue #1848). Our current code is the correct mitigation.
- **AudioDecoder resampler uses linear interpolation** — fine for ASR, mediocre for music.
- **`SamplerConfig.seed`** isn't in LiteRT-LM 0.11.0's public Kotlin API — we now pass only the three documented fields. If a future bump re-introduces it as a named param we can opt back in.
- **16 KB page-size warning** for AGP 9 is gone (ORT 1.24.3 ships aligned binaries), but several upstream libs (`libwhisper`, `libggml*`, `libLiteRt*`, `libsherpa-onnx-jni`, `libonnxruntime4j_jni`) are still 4 KB-aligned. Non-fatal — Android runs them in compat mode. Resolves as those SDKs ship 16 KB builds.
- **Diarization over-segments on Arabic** (B above).
- **Hybrid hints prompt size** correlates with Gemma prefill wedges on long-prompt chunks (~1700+ chars). 2-min outer timeout + retry-with-trimmed-input handles it but eats ~3 min per stuck chunk.

## Mac side

- **Mac-app PR** — blocked on `gh auth login`. Run that, then `/create-pr` and the Mac changes (Claude model defaults, Ukrainian preset, android-plan docs) ship.
- Mac app's `transcriber/llm.py` keeps `claude-sonnet-4-6` as default — sync with the Android `Settings → Gemma prompts` defaults.
