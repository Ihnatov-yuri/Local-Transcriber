package nl.ihnatov.transcriber.asr

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import nl.ihnatov.transcriber.audio.AudioDecoder
import nl.ihnatov.transcriber.data.RecordingRepository
import nl.ihnatov.transcriber.data.Segment

/**
 * End-to-end transcription pipeline:
 *   1. Read WAV → mono float samples.
 *   2. Hand to selected ASR backend → RawSegments.
 *   3. (optional) Run diarization → assign SPEAKER_XX per segment by max overlap.
 *   4. Persist segments under the recording id.
 *
 * Emits coarse-grained [AsrEvent]s for the UI. The LLM cleaner port from the
 * Mac app lands in a later milestone.
 */
private const val TAG = "TxRunner"

/**
 * Hard ceiling per Gemma chunk inference. Real-world chunks transcribe in
 * 5–60s on a flagship phone; the previous 5-minute ceiling was a relic
 * from when we had no streaming and no silent-wedge watchdog. Now
 * Gemma4Backend has a 60-second no-delta watchdog that aborts wedged
 * workers proactively; this outer ceiling is just a backstop for the
 * case where cancelProcess() doesn't release the native worker.
 *
 * Lowered to 2 minutes: with retry that's 4 minutes worst-case per
 * stuck chunk (vs the previous 10), and on a healthy run we never
 * approach this limit anyway.
 */
private const val CHUNK_TIMEOUT_MS = 2L * 60_000L

/**
 * On retry after a chunk timeout, we re-send the chunk audio truncated to
 * this many seconds (well below Gemma's ~30 s audio limit). The original
 * wedge was usually deterministic on the full-size input, so giving Gemma
 * a strictly smaller input is the cheapest way to break the attractor.
 */
private const val RETRY_AUDIO_SECONDS = 24

/**
 * Max sherpa speaker-hint turns inlined into a single chunk's Gemma
 * user message. Each turn is a "Speaker N at X–Y s" line; too many
 * bloat the prompt and correlate with the prefill silent-wedge.
 */
private const val MAX_HINTS_PER_CHUNK = 6

/**
 * Per-chunk RMS amplitude floor. Chunks below this are pre-skipped —
 * Gemma is never asked to transcribe them. Feeding silent audio to the
 * model is the main trigger of "prompt content echoing into output"
 * (the reported `"Yuri, Len, ABCI, SNB, FAB"` vocabulary leak). The
 * VAD scan that schedules chunk boundaries uses a similar floor
 * (~0.005) and we stay below that here so we don't false-skip
 * quietly-spoken-but-real audio. 0.002 ≈ -54 dBFS, which is well
 * under typical near-silent speech (~−40 dBFS).
 */
private const val SILENCE_PRE_SKIP_RMS = 0.002f

/** Compute root-mean-square amplitude of a float waveform in `[-1, 1]`. */
private fun computeRms(samples: FloatArray): Float {
    if (samples.isEmpty()) return 0f
    var sumSq = 0.0
    for (s in samples) sumSq += s.toDouble() * s.toDouble()
    return kotlin.math.sqrt(sumSq / samples.size).toFloat()
}

/**
 * Recap-overlap seconds applied at the start of every chunk after the
 * first. Down from 3s to 1s now that chunks align to silence boundaries
 * (see [CHUNK_ALIGN_FLEX_SECONDS]) — when the cut already lands in a
 * natural pause, the recap audio is mostly silence anyway, so we only
 * need a tiny safety margin against silence-detection misses. 1s on a
 * ~28-second chunk ≈ 3-4% extra compute, vs the old 11%.
 *
 * Setting this >= 2.0 re-enables the "skip the recap" prompt block in
 * Gemma4Backend.buildUserMessage; below that we trust the cut alignment
 * and don't bother telling the model to skip anything.
 */
private const val CHUNK_OVERLAP_SECONDS = 1

/**
 * Half-width of the window around each chunk's target end-time within
 * which we'll snap the cut to the nearest silence. ±2 s means chunks
 * stay close to 28 s nominal but slide to land on natural pauses.
 * Outside the window (long monologues with no pauses), we fall back to
 * fixed-time cuts.
 */
private const val CHUNK_ALIGN_FLEX_SECONDS = 2.0

/** Minimum pause length (ms) that counts as a real chunk-boundary candidate. */
private const val CHUNK_ALIGN_MIN_SILENCE_MS = 250

/** RMS threshold below which we consider a 25 ms window silent (~ -46 dBFS). */
private const val CHUNK_ALIGN_RMS_THRESHOLD = 0.005f

/**
 * Target chunk length for Whisper's VAD-aligned long-file chunking (Phase
 * 5's OOM fix). Bigger than Gemma's [Gemma4Backend.CHUNK_SECONDS] on
 * purpose — whisper.cpp has none of Gemma's prompt-length/wedge pressure,
 * so fewer, larger chunks mean less per-call overhead while still bounding
 * peak memory far below a multi-hour file's full-buffer size.
 */
private const val WHISPER_CHUNK_TARGET_SECONDS = 60.0

/** Below this, a single full-buffer decode+transcribe (today's shape) has no real OOM risk — not worth the chunking overhead. */
private const val WHISPER_CHUNK_THRESHOLD_SECONDS = 90.0

class TranscriptionRunner(
    private val context: Context,
    private val repository: RecordingRepository,
    private val factory: AsrFactory,
    private val diarizer: DiarizationRunner,
    private val uiPrefs: UiPrefs? = null,
) {

    fun run(
        recordingId: Long,
        audioPath: String,
        backend: AsrBackendKind,
        /**
         * Allowed source-language set. Empty = full auto. One = force.
         * Two-plus = constrained auto — faster + more accurate than letting
         * the model pick from 100+ languages.
         */
        languages: List<String>,
        /**
         * Target translation language. null = transcribe in source, otherwise
         * Gemma is asked to render the output in this language (en / ar / uk /
         * nl / …). Whisper only supports "en" as a target; any other value
         * with the Whisper backend silently falls back to source transcription
         * since whisper.cpp's translate flag is hardcoded to English.
         */
        translateTo: String? = null,
        diarize: Boolean = false,
        expectedSpeakers: Int = -1,
        /**
         * When true and the user requested diarization with the Gemma
         * backend, run a sherpa-onnx voice-clustering pre-pass on the full
         * waveform first and feed per-chunk speaker hints into Gemma.
         * Sherpa decides identity (globally consistent), Gemma uses the
         * hints to keep its inline labels aligned. Falls back to pure
         * Gemma diarization if the sherpa embedding model isn't installed
         * or the pre-pass fails. Ignored when diarize=false or backend
         * isn't Gemma.
         */
        hybridDiarize: Boolean = false,
        /** Super mode (Phase 3): run [superPairA] + [superPairB] and vote-merge instead of just [backend]. */
        superMode: Boolean = false,
        superPairA: AsrBackendKind? = null,
        superPairB: AsrBackendKind? = null,
        /** Constrained-JSON arbitration second pass on low-agreement Super chunks. Meaningless unless [superMode]. */
        maxQuality: Boolean = false,
    ): Flow<AsrEvent> = channelFlow {
        // Why channelFlow not flow {}: the chunk loop launches a sibling
        // "poller" coroutine that surfaces Gemma's per-token partial text
        // into the AsrEvent stream. Plain `flow { emit() }` forbids emit
        // from any coroutine other than the one that started collection
        // ("Flow invariant is violated"); channelFlow's send() is
        // safe to call from any child of the producer scope. The
        // consumer-facing type stays Flow<AsrEvent>, so callers don't
        // notice.
        // Legacy backends and DB columns still want a single "primary" language
        // string. Promote the list: single → force, multi → resolve at-detect
        // time, empty → null.
        val primaryLanguage = languages.singleOrNull()
        val runSuper = superMode && superPairA != null && superPairB != null && superPairA != superPairB
        // Engines that can't translate (the sherpa-onnx ones, and a Super
        // pair — its two halves would disagree) get no target at all;
        // otherwise the output would be stamped with a language it isn't
        // in. Shadows the parameter on purpose so every later use sees it.
        @Suppress("NAME_SHADOWING")
        val translateTo = translateTo?.takeIf { !runSuper && backend.supportsTranslation }
        val recording = repository.get(recordingId)
        if (recording == null) {
            send(AsrEvent.Failed("Recording $recordingId not found"))
            return@channelFlow
        }

        if (diarize && !diarizer.isEmbeddingModelPresent()) {
            send(AsrEvent.Failed(
                "Speaker diarization needs the embedding model. " +
                    "Open Settings → Download 'Speaker embedding' (~28 MB)."
            ))
            return@channelFlow
        }

        // Diarization tuning: Settings override wins, else the language-
        // aware / Mac-ported default. See DiarizationRunner.DEFAULT_* and
        // UiPrefs' diar_* / turn_coalesce_gap_sec keys.
        val clusterThreshold = uiPrefs?.clusterThreshold?.value
            ?: DiarizationRunner.defaultClusterThreshold(languages)
        val minDurationOn = uiPrefs?.minDurationOnSec?.value ?: DiarizationRunner.DEFAULT_MIN_DURATION_ON
        val minDurationOff = uiPrefs?.minDurationOffSec?.value ?: DiarizationRunner.DEFAULT_MIN_DURATION_OFF
        val turnCoalesceGapSec = (uiPrefs?.turnCoalesceGapSec?.value ?: DEFAULT_TURN_COALESCE_GAP_SEC.toFloat()).toDouble()

        // Gemma 4 E4B needs the memory guard whether it's the sole backend
        // or one half of a Super pair — check by NAME so it applies to
        // whichever Gemma variant AsrFactory would actually resolve.
        suspend fun checkGemmaE4BMemory(): AsrEvent.Failed? {
            val gemmaModel = factory.resolveModel(AsrBackendKind.Gemma4) ?: return null
            if (!gemmaModel.name.contains("E4B", ignoreCase = true)) return null
            val budget = MemoryGuard.estimateBudget(context)
            if (budget.estimatedBudgetBytes >= MemoryGuard.GEMMA_E4B_MIN_BUDGET_BYTES) return null
            val have = MemoryGuard.gibString(budget.estimatedBudgetBytes)
            val need = MemoryGuard.gibString(MemoryGuard.GEMMA_E4B_MIN_BUDGET_BYTES)
            val why = if (budget.isLimiterEstimate) {
                " (Android's background memory limiter caps this app around ${have} GB)"
            } else {
                " (only ${have} GB free right now)"
            }
            return AsrEvent.Failed(
                "Gemma 4 E4B needs about $need GB of memory headroom$why. " +
                    "Try Gemma 4 E2B instead, or close other apps and retry."
            )
        }

        val modelFile: File
        val asr: AsrBackend
        if (runSuper) {
            if ((superPairA == AsrBackendKind.Gemma4 || superPairB == AsrBackendKind.Gemma4)) {
                checkGemmaE4BMemory()?.let { send(it); return@channelFlow }
            }
            // No single model FILE for Super mode — EnsembleBackend resolves
            // each sub-engine's own model internally. This placeholder path
            // is never read from disk, only used for logging/display
            // (transcribedWithModel) the same way a real model's name is.
            modelFile = File(factory.modelsDir(), "super-${superPairA.name.lowercase()}-${superPairB.name.lowercase()}")
            asr = EnsembleBackend(superPairA!!, superPairB!!, factory, arbitrationEnabled = maxQuality, languages = languages)
            send(AsrEvent.Stage("Loading Super mode engines", 0.05f))
            val loadRes = asr.load(modelFile.absolutePath)
            if (loadRes.isFailure) {
                send(AsrEvent.Failed(loadRes.exceptionOrNull()?.message ?: "Super mode engines failed to load"))
                asr.release()
                return@channelFlow
            }
        } else {
            val resolved = factory.resolveModel(backend, languages)
            if (resolved == null) {
                val dir = factory.modelsDir()
                val expected = factory.expectedExtensionsHint(backend)
                val existing = runCatching { dir.listFiles()?.map { it.name } ?: emptyList() }
                    .getOrDefault(emptyList())
                val listing = if (existing.isEmpty()) "(empty)" else existing.joinToString(", ")
                send(AsrEvent.Failed(
                    "No model file for ${backend.name}.\n" +
                        "Expected: $expected\n" +
                        "Found in ${dir.absolutePath}: $listing\n" +
                        "Use Settings → Import model to add one."
                ))
                return@channelFlow
            }
            modelFile = resolved
            if (backend == AsrBackendKind.Gemma4) {
                checkGemmaE4BMemory()?.let { send(it); return@channelFlow }
            }
            asr = factory.create(backend)
            send(AsrEvent.Stage("Loading model", 0.05f))
            val loadRes = asr.load(modelFile.absolutePath)
            if (loadRes.isFailure) {
                send(AsrEvent.Failed(loadRes.exceptionOrNull()?.message ?: "model load failed"))
                asr.release()
                return@channelFlow
            }
        }

        // Snapshot whatever transcript already exists BEFORE this run
        // overwrites it — once per run, not once per incremental save (the
        // Gemma streaming loop calls replaceSegments many times per chunk),
        // and only now that pre-flight (models present, engine loaded) has
        // passed: a run that fails before touching the transcript must not
        // leave a duplicate version behind. Labeled with whichever engine
        // actually PRODUCED those existing segments
        // (recording.transcribedWithBackend/Model — set at the end of the
        // PREVIOUS run, see below), not the one about to run; a no-op when
        // there's nothing to snapshot yet (first-ever run).
        val snapshotEngineId = recording.transcribedWithBackend ?: "unknown"
        val snapshotEngineLabel = recording.transcribedWithModel ?: snapshotEngineId
        runCatching { repository.snapshotCurrentTranscript(recordingId, snapshotEngineId, snapshotEngineLabel) }
            .onFailure { Log.w(TAG, "pre-run transcript snapshot failed (non-fatal)", it) }

        send(AsrEvent.Stage("Reading audio", 0.1f))
        // For Gemma we stream-decode: a 1-hour MP3 is ~230 MB as float32 and
        // doubling-grow allocators push peak heap well past device limits.
        // Streaming holds only ~6 MB at a time. For Whisper we still need the
        // full buffer (whisper.cpp's main API is full-file).
        val gemma = asr as? Gemma4Backend
        val rawSegments: List<RawSegment>
        val mono: FloatArray
        // Set by the long-file non-Gemma path: the file was too big to hold
        // as one buffer for ASR, so diarization must use the windowed
        // (file-streaming) path too instead of decoding it whole after all.
        var diarizeWindowed = false
        // Sherpa pre-pass results, populated inside the Gemma branch when
        // hybrid diarization is on. Reused by the reconcile block below.
        var sherpaSegmentsFromPrepass: List<DiarizationRunner.SpeakerSegment>? = null
        // Absolute (post-recap) start time of each new chunk's content,
        // populated inside the Gemma chunk loop. Reused by both the
        // incremental and final dedupChunkBoundaries calls so only true
        // chunk boundaries are dedup candidates. Empty in the Whisper
        // path (whisper.cpp does a single full-file pass — no chunk
        // boundaries to dedup).
        val chunkBoundaryStartSeconds = mutableListOf<Double>()
        if (gemma != null) {
            // Hybrid diarization pre-pass. When the user asked for diarize +
            // hybrid + the embedding model is installed, we run sherpa
            // voice-clustering on the full waveform BEFORE streaming Gemma.
            // Sherpa gives us globally-consistent (start, end, speakerId)
            // tuples; we feed the relevant slice into each chunk as hints
            // so Gemma's inline labels match sherpa's numbering by
            // construction, and we use sherpa as the source of truth for
            // identity at reconcile time. Falls back to pure-Gemma diar
            // on any failure (no embedding model, OOM, sherpa exception)
            // with a warning Stage event.
            val wantHybrid = diarize && hybridDiarize
            if (wantHybrid) {
                if (!diarizer.isEmbeddingModelPresent()) {
                    send(AsrEvent.Stage("Hybrid diarization unavailable; using Gemma-only", 0.12f))
                } else {
                    // Memory pre-check. Sherpa needs the full mono-float
                    // audio buffer in heap at once (its API takes a
                    // FloatArray, not a stream). Decoding a 60-min file
                    // at 16 kHz mono = 3.84 M floats = ~15 MB raw; but
                    // the MediaCodec decode keeps codec buffers + a
                    // doubling growth allocator alive simultaneously,
                    // so peak is closer to 3–4× the raw size. Plus the
                    // Gemma engine is already pinning ~150 MB. If the
                    // expected peak exceeds 70% of free heap we skip
                    // sherpa and fall back to Gemma-only diar rather
                    // than risking an OOM in mid-decode.
                    val rt = Runtime.getRuntime()
                    val maxHeapMb = rt.maxMemory() / 1024 / 1024
                    val currentUsedMb = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024
                    val budgetMb = (maxHeapMb - currentUsedMb).coerceAtLeast(0)
                    val rawFloatMb = recording.durationSeconds.toLong() *
                        AudioDecoder.TARGET_SR / 1024 / 1024 * 4
                    // 3× factor accounts for the doubling growth allocator
                    // in AudioDecoder.decode + codec frame buffers held
                    // alive concurrently during the decode loop.
                    val expectedPeakMb = rawFloatMb * 3
                    Log.i(TAG, "memory pre-check: heap=${currentUsedMb}/${maxHeapMb}MB " +
                        "budget=${budgetMb}MB, sherpa-decode-peak=${expectedPeakMb}MB " +
                        "(raw=${rawFloatMb}MB)")
                    if (expectedPeakMb > budgetMb * 0.7) {
                        // Too big to decode whole — but instead of skipping
                        // diarization, run the WINDOWED path. It streams the
                        // file in bounded windows (~22 MB each) and stitches
                        // speaker ids across windows, so file length stops
                        // mattering for memory.
                        send(AsrEvent.Stage(
                            "Long recording — diarizing in windows", 0.10f,
                        ))
                        Log.i(TAG, "using chunked diarization: full decode " +
                            "would need ${expectedPeakMb}MB peak, only ${budgetMb}MB free")
                        val chunkedResult = runCatching {
                            diarizer.runChunked(
                                file = File(audioPath),
                                numClusters = expectedSpeakers,
                                threshold = clusterThreshold,
                                minDurationOn = minDurationOn,
                                minDurationOff = minDurationOff,
                                durationSec = recording.durationSeconds,
                                onProgress = { fraction ->
                                    val pct = (fraction * 100f).toInt().coerceIn(0, 99)
                                    trySend(AsrEvent.Stage(
                                        "Identifying speakers · $pct%",
                                        (0.10f + 0.04f * fraction).coerceAtMost(0.14f),
                                    ))
                                },
                                onOverSegmented = {
                                    trySend(AsrEvent.Stage(
                                        "Lots of speakers detected — if you know how many, " +
                                            "set Expected Speakers in the RUN sheet",
                                        0.14f,
                                    ))
                                },
                            ).getOrThrow()
                        }
                        sherpaSegmentsFromPrepass = chunkedResult.getOrNull()
                        if (sherpaSegmentsFromPrepass == null) {
                            send(AsrEvent.Stage(
                                "Windowed clustering failed; using Gemma-only labels: " +
                                    (chunkedResult.exceptionOrNull()?.message ?: "unknown"),
                                0.13f,
                            ))
                        } else {
                            send(AsrEvent.Stage(
                                "Speaker clustering complete (${sherpaSegmentsFromPrepass.size} turns, windowed)",
                                0.14f,
                            ))
                        }
                    } else {
                        send(AsrEvent.Stage("Decoding for speaker clustering", 0.10f))
                        val sherpaResult = runCatching {
                            val full = AudioDecoder.decode(File(audioPath))
                            send(AsrEvent.Stage("Identifying speakers", 0.12f))
                            // Sherpa doesn't expose real progress (we used
                            // to try processWithCallback for that but it
                            // crashed the JNI on capturing Kotlin
                            // lambdas — see DiarizationRunner). Instead
                            // a heartbeat fires every 5 s with the
                            // elapsed-time-encoded fraction we map to
                            // a wall-clock string so the user knows the
                            // process is alive.
                            diarizer.run(
                                samples = full.samples,
                                numClusters = expectedSpeakers,
                                threshold = clusterThreshold,
                                minDurationOn = minDurationOn,
                                minDurationOff = minDurationOff,
                                onProgress = { fraction ->
                                    val seconds = (fraction / 0.01f).toInt()
                                    // Bar clamped to the sherpa-reserved
                                    // 10–14% range, but the visible "Ns
                                    // elapsed" label keeps counting up
                                    // past 95s — only the bar plateaus,
                                    // the heartbeat stays alive.
                                    val barFraction = (0.10f + 0.04f * fraction)
                                        .coerceAtMost(0.14f)
                                    trySend(AsrEvent.Stage(
                                        "Identifying speakers · ${seconds}s elapsed",
                                        barFraction,
                                    ))
                                },
                            ).getOrThrow()
                        }
                        sherpaSegmentsFromPrepass = sherpaResult.getOrNull()
                        if (sherpaSegmentsFromPrepass == null) {
                            // Catch any OOM that slipped through the heuristic
                            // — better to surface it as a warning than crash
                            // the run. The OutOfMemoryError class hierarchy
                            // is caught by the broader Throwable in runCatching.
                            val cause = sherpaResult.exceptionOrNull()
                            val isOom = cause is OutOfMemoryError ||
                                cause?.cause is OutOfMemoryError ||
                                cause?.message?.contains("OutOfMemory", ignoreCase = true) == true
                            val msg = if (isOom)
                                "Speaker clustering ran out of memory; using Gemma-only labels"
                            else
                                "Speaker clustering failed; using Gemma-only: " +
                                    (cause?.message ?: "unknown")
                            send(AsrEvent.Stage(msg, 0.13f))
                        } else {
                            send(AsrEvent.Stage(
                                "Speaker clustering complete (${sherpaSegmentsFromPrepass.size} turns)",
                                0.14f,
                            ))
                        }
                    }
                }
            }

            // Best-effort Gemma cleanup pass over the sherpa clustering:
            // ask whether any of the clusters it found are actually the
            // same speaker. Never blocks or fails the run — see
            // Gemma4Backend.suggestSpeakerMergeMap's own doc comment.
            val prepassClusters = sherpaSegmentsFromPrepass
            val prepassGemma = gemma
            if (prepassGemma != null && prepassClusters != null) {
                val summaries = prepassClusters.groupBy { it.speakerId }.map { (id, segs) ->
                    ClusterSummary(
                        id = id,
                        durationSeconds = segs.sumOf { (it.end - it.start).toDouble() },
                        confidence = segs.map { it.confidence }.average().toFloat(),
                    )
                }
                if (summaries.size >= 2) {
                    val mergePairs = runCatching { prepassGemma.suggestSpeakerMergeMap(summaries) }.getOrDefault(emptyList())
                    if (mergePairs.isNotEmpty()) {
                        val mapping = applyMergeMap(summaries.map { it.id }.toSet(), mergePairs)
                        val merged = prepassClusters.map { it.copy(speakerId = mapping[it.speakerId] ?: it.speakerId) }
                        val renumbered = DiarizationRunner.renumberByFirstAppearance(merged)
                        sherpaSegmentsFromPrepass = renumbered
                        val mergedCount = summaries.size - renumbered.map { it.speakerId }.distinct().size
                        if (mergedCount > 0) {
                            send(AsrEvent.Stage(
                                "Gemma merged $mergedCount over-split speaker cluster(s)",
                                0.145f,
                            ))
                        }
                    }
                }
            }

            // Estimated chunk count is now driven by the VAD-snapped cut
            // points (computed a few lines below) rather than ceil(dur /
            // CHUNK_SEC). We don't know it yet at this point, so seed
            // with a rough estimate; the chunk-loop's per-chunk Stage
            // emits update the bar correctly once chunks start landing.
            var expectedChunks = if (recording.durationSeconds > 0) {
                kotlin.math.ceil(recording.durationSeconds / Gemma4Backend.CHUNK_SECONDS)
                    .toInt().coerceAtLeast(1)
            } else 0
            send(AsrEvent.Stage(
                label = if (expectedChunks > 0) "Transcribing 0/$expectedChunks" else "Transcribing",
                fraction = 0.15f,
            ))
            val out = mutableListOf<RawSegment>()
            val chunkSamples = Gemma4Backend.CHUNK_SECONDS * AudioDecoder.TARGET_SR
            val overlapSamples = CHUNK_OVERLAP_SECONDS * AudioDecoder.TARGET_SR
            // Gemma-based diarization is on whenever the user requested
            // diarization with a Gemma backend (no separate model download,
            // good enough for short recordings). For Whisper we'd fall
            // through to the sherpa-onnx path below.
            val useGemmaDiar = diarize
            // Tail of the previous chunk's transcript — passed forward as
            // continuation context. Helps Gemma carry named entities and
            // pronouns across the chunk boundary instead of restarting cold.
            var previousContext: String? = null
            var chunkIdx = 0
            // VAD-aligned chunking. Pre-pass scans the file for ≥250 ms
            // silence regions; we then snap each chunk's nominal 28-sec
            // boundary to the nearest silence within ±2 sec. Words no
            // longer get cut mid-syllable at chunk boundaries, and the
            // recap-overlap can stay tiny (1 sec) because the cut point
            // itself is already silent. If no silence falls in the flex
            // window — long monologues, music, dense conversation — we
            // fall back to a hard cut at the nominal boundary (still
            // bridged by the recap). Skipped for very short audio.
            val audioFile = File(audioPath)
            val cutPoints: List<AudioDecoder.CutPoint> = if (recording.durationSeconds > Gemma4Backend.CHUNK_SECONDS + CHUNK_ALIGN_FLEX_SECONDS) {
                send(AsrEvent.Stage("Scanning for sentence boundaries", 0.12f))
                val silences = runCatching {
                    AudioDecoder.scanSilences(
                        audioFile,
                        minSilenceMs = CHUNK_ALIGN_MIN_SILENCE_MS,
                        rmsThreshold = CHUNK_ALIGN_RMS_THRESHOLD,
                    )
                }.getOrElse {
                    Log.w(TAG, "silence scan failed; falling back to fixed-time chunks", it)
                    emptyList()
                }
                AudioDecoder.computeCutPoints(
                    silences = silences,
                    durationSec = recording.durationSeconds,
                    targetChunkSec = Gemma4Backend.CHUNK_SECONDS.toDouble(),
                    flexSec = CHUNK_ALIGN_FLEX_SECONDS,
                )
            } else emptyList()
            // Update progress estimate with the real chunk count.
            expectedChunks = cutPoints.size + 1
            val hardCutCount = cutPoints.count { !it.silenceAligned }
            Log.i(TAG, "chunking plan: $expectedChunks chunks, " +
                "first=${if (cutPoints.isEmpty()) "EOF" else "%.1fs".format(cutPoints[0].timeSec)}, " +
                "silence-aligned=${cutPoints.size - hardCutCount}, hard-cut=$hardCutCount")
            // Surface a hint when the silence scan returned nothing useful on
            // a long recording. The chunker still works (falls back to hard
            // cuts every CHUNK_SECONDS) but cross-boundary text quality drops
            // because cuts land mid-word. Worth telling the user so they
            // don't blame the model for boundary artifacts.
            if (cutPoints.isEmpty() && recording.durationSeconds > 60) {
                send(AsrEvent.Stage(
                    "No clear pauses found — chunks will hard-cut every " +
                        "${Gemma4Backend.CHUNK_SECONDS}s",
                    0.13f,
                ))
            }
            try {
                AudioDecoder.decodeAtCutPoints(
                    audioFile,
                    cutPoints = cutPoints,
                    // Hard cuts (no silence in flex window) get the recap
                    // overlap so Gemma can bridge a mid-word break.
                    hardCutOverlapSamples = overlapSamples,
                    // Silence-aligned cuts get ZERO overlap — the audio is
                    // already split cleanly, re-feeding the recap would
                    // double-transcribe (the visible failure mode from
                    // user-reported boundary duplicates) and provide no
                    // benefit since no word was sliced. WhisperX-style:
                    // trust VAD where it succeeded.
                    silenceCutOverlapSamples = 0,
                ).collect { chunk ->
                    // Build per-chunk speaker hints from sherpa's globally
                    // clustered turns. Filter to those overlapping this
                    // chunk and remap timestamps to chunk-relative.
                    val perChunkHints: List<Gemma4Backend.SpeakerHint>? = sherpaSegmentsFromPrepass?.let { all ->
                        val chunkStart = chunk.startSeconds
                        val chunkEnd = chunkStart + chunk.samples.size.toDouble() / chunk.sampleRate
                        // Skip hints that fall entirely in the recap region —
                        // Gemma is told NOT to transcribe that audio, so a
                        // hint pointing at it would describe output that
                        // doesn't exist and just confuse the labeller.
                        val effectiveStart = chunkStart + chunk.overlapSeconds
                        val raw = all.mapNotNull { sp ->
                            val s = sp.start.toDouble().coerceAtLeast(effectiveStart)
                            val e = sp.end.toDouble().coerceAtMost(chunkEnd)
                            if (e <= s) null
                            else Gemma4Backend.SpeakerHint(
                                // Chunk-relative timestamps: Gemma sees the
                                // whole chunk including recap, so we stay
                                // relative to chunkStart (not effectiveStart)
                                // so hint times line up with the audio Gemma
                                // hears.
                                startSec = s - chunkStart,
                                endSec = e - chunkStart,
                                speakerId = sp.speakerId,
                            )
                        }
                        // Coalesce consecutive same-speaker hints into one
                        // span. Sherpa can over-segment on noisy audio (we
                        // saw a 28-sec chunk produce 80+ turns once),
                        // ballooning the user-message prompt to thousands
                        // of tokens of "Speaker N at X-Y s" lines. Gemma
                        // then either hangs on chunk 1 or returns garbage.
                        // Merging adjacent runs keeps the prompt small
                        // without losing identity information — the only
                        // thing that matters per turn is "which speaker
                        // starts here".
                        val merged = mutableListOf<Gemma4Backend.SpeakerHint>()
                        for (h in raw) {
                            val last = merged.lastOrNull()
                            if (last != null && last.speakerId == h.speakerId &&
                                h.startSec - last.endSec < 0.5
                            ) {
                                merged[merged.lastIndex] = last.copy(endSec = h.endSec)
                            } else {
                                merged.add(h)
                            }
                        }
                        // Hard cap as a final safety net. Lowered 12 → 6:
                        // a long hint block bloats the user-message prompt
                        // (each turn is a "Speaker N at X–Y s" line), and
                        // large prompts correlate strongly with the Gemma
                        // prefill silent-wedge (a ~1320-char prompt is right
                        // at the edge). 6 turns per 28-sec chunk is already
                        // a fast back-and-forth; beyond that the hints add
                        // prompt weight + wedge risk for little labelling
                        // gain.
                        val capped = if (merged.size > MAX_HINTS_PER_CHUNK)
                            merged.take(MAX_HINTS_PER_CHUNK) else merged
                        capped.takeIf { it.isNotEmpty() }
                    }
                    // Hard per-chunk timeout. Gemma 4 audio inference is
                    // typically 5–60 seconds per ~28-second chunk; anything
                    // over 5 minutes is a hang (LiteRT-LM issue #2202:
                    // Conversation wedges silently with no onError/onDone,
                    // and the wedge propagates to the next chunk via the
                    // shared engine). On timeout we release the engine,
                    // reload it from the same model file, and retry the
                    // chunk once — that salvages the run when the wedge
                    // is transient (vs failing immediately and losing the
                    // remaining 10+ chunks of audio).
                    val baseLabel = if (expectedChunks > 0)
                        "Transcribing ${chunkIdx + 1}/$expectedChunks"
                    else "Transcribing (chunk ${chunkIdx + 1})"
                    val baseFraction = if (expectedChunks > 0)
                        (0.15f + 0.80f * chunkIdx / expectedChunks).coerceAtMost(0.95f)
                    else 0.5f
                    Log.i(TAG, "→ chunk ${chunkIdx + 1}/$expectedChunks " +
                        "(${chunk.samples.size / chunk.sampleRate}s audio, " +
                        "hybrid=${perChunkHints != null}) calling Gemma…")
                    // Silence pre-skip. If the chunk audio is below the
                    // RMS floor, do NOT send it to Gemma — feeding the
                    // model silence triggers prompt-content echoing
                    // (the user-reported "Yuri, Len, ABCI, SNB, FAB"
                    // vocabulary leak landing in the transcript with
                    // no audio justification). Skip the chunk entirely
                    // and continue to the next. The user gets a small
                    // silent gap in their transcript rather than 80
                    // chars of noise.
                    val chunkRms = computeRms(chunk.samples)
                    if (chunkRms < SILENCE_PRE_SKIP_RMS) {
                        Log.i(TAG, "  chunk ${chunkIdx + 1} pre-skipped: " +
                            "RMS=${"%.5f".format(chunkRms)} below ${SILENCE_PRE_SKIP_RMS} " +
                            "(silent audio — would risk vocabulary echo)")
                        // Still bump the chunk index + progress so the
                        // bar advances; we just don't add any segments.
                        chunkIdx++
                        val advanceLabel = if (expectedChunks > 0)
                            "Transcribing $chunkIdx/$expectedChunks · silent" else baseLabel
                        val advanceFraction = if (expectedChunks > 0)
                            (0.15f + 0.80f * chunkIdx / expectedChunks).coerceAtMost(0.95f)
                        else 0.5f
                        send(AsrEvent.Stage(advanceLabel, advanceFraction))
                        return@collect
                    }
                    // Streaming progress. Two-coroutine fan-out so we
                    // never call emit() from a wrong context:
                    //   1. Inference coroutine runs gemma.transcribeChunk
                    //      and writes the running partial to a non-
                    //      suspending AtomicReference sink.
                    //   2. Sibling "poller" coroutine launched in the
                    //      flow body's own context wakes every 250 ms,
                    //      reads the AtomicReference, and emits a Stage
                    //      event with the last 80 chars as a snippet.
                    // Without this split, calling emit() inside Gemma's
                    // Dispatchers.Default-scoped callback would crash with
                    // "Flow invariant is violated."
                    val partialSink = AtomicReference("")
                    val tStart = System.currentTimeMillis()
                    var text = withTimeoutOrNull(CHUNK_TIMEOUT_MS) {
                        coroutineScope {
                            val poller = launch {
                                while (isActive) {
                                    delay(250L)
                                    val p = partialSink.get()
                                    if (p.isNotEmpty()) {
                                        val snippet = p.takeLast(80)
                                            .replace('\n', ' ').trim()
                                        send(AsrEvent.Stage(
                                            if (snippet.isEmpty()) baseLabel
                                            else "$baseLabel · …$snippet",
                                            baseFraction,
                                        ))
                                    }
                                }
                            }
                            try {
                                gemma.transcribeChunk(
                                    chunkSamples = chunk.samples,
                                    sampleRate = chunk.sampleRate,
                                    languages = languages,
                                    translateTo = translateTo,
                                    diarize = useGemmaDiar,
                                    previousContext = previousContext,
                                    speakerHints = perChunkHints,
                                    onPartialText = { p -> partialSink.set(p) },
                                    overlapSeconds = chunk.overlapSeconds,
                                )
                            } finally {
                                poller.cancel()
                            }
                        }
                    }
                    if (text == null) {
                        Log.w(TAG, "chunk ${chunkIdx + 1} timed out — releasing " +
                            "engine, reloading, and retrying once")
                        send(AsrEvent.Stage(
                            "$baseLabel · stalled, retrying with fresh engine",
                            baseFraction,
                        ))
                        asr.release()
                        val reloadRes = asr.load(modelFile.absolutePath)
                        if (reloadRes.isFailure) {
                            // Can't `return@channelFlow` from inside a
                            // `collect { }` lambda — Kotlin disallows non-
                            // local returns through the (crossinline)
                            // collect call. Throw a sentinel instead; the
                            // outer try/catch around this whole collect
                            // turns it into the appropriate Failed event
                            // plus a clean channelFlow exit.
                            throw TranscriptionBailout(AsrEvent.Failed(
                                "Chunk ${chunkIdx + 1} timed out and engine " +
                                    "reload failed: ${reloadRes.exceptionOrNull()?.message ?: "unknown"}"
                            ))
                        }
                        partialSink.set("")
                        // SHRINK THE INPUT on retry. The wedge was deterministic
                        // for the original audio/prompt combination (saw this
                        // repeatedly at chunk 6 of a 19-min file: 30 s audio,
                        // 1255-char prompt with diarize+previousContext → no
                        // deltas, full timeout, retry wedges again, full
                        // timeout, total 10 min wasted). Cutting the audio
                        // to the first 24 s and dropping the per-chunk
                        // diarize block + previousContext gives Gemma a
                        // strictly simpler input that's very unlikely to
                        // hit the same attractor. We sacrifice the last
                        // few seconds of this chunk's audio (the next
                        // chunk will overlap-pick-it-up where possible)
                        // and per-chunk speaker continuity rather than
                        // losing the entire chunk's transcription.
                        val retrySampleCount = minOf(
                            chunk.samples.size,
                            (RETRY_AUDIO_SECONDS * chunk.sampleRate).toInt(),
                        )
                        val retrySamples = if (retrySampleCount >= chunk.samples.size) {
                            chunk.samples
                        } else {
                            chunk.samples.copyOfRange(0, retrySampleCount)
                        }
                        Log.w(TAG, "  retrying with reduced input: " +
                            "audio ${chunk.samples.size}→${retrySamples.size} samples, " +
                            "diarize off, no previousContext")
                        text = withTimeoutOrNull(CHUNK_TIMEOUT_MS) {
                            coroutineScope {
                                val poller = launch {
                                    while (isActive) {
                                        delay(250L)
                                        val p = partialSink.get()
                                        if (p.isNotEmpty()) {
                                            val snippet = p.takeLast(80)
                                                .replace('\n', ' ').trim()
                                            send(AsrEvent.Stage(
                                                if (snippet.isEmpty()) baseLabel
                                                else "$baseLabel · …$snippet",
                                                baseFraction,
                                            ))
                                        }
                                    }
                                }
                                try {
                                    gemma.transcribeChunk(
                                        chunkSamples = retrySamples,
                                        sampleRate = chunk.sampleRate,
                                        languages = languages,
                                        translateTo = translateTo,
                                        diarize = false,
                                        previousContext = null,
                                        speakerHints = null,
                                        onPartialText = { p -> partialSink.set(p) },
                                        overlapSeconds = chunk.overlapSeconds,
                                    )
                                } finally {
                                    poller.cancel()
                                }
                            }
                        }
                        if (text == null) {
                            Log.e(TAG, "chunk ${chunkIdx + 1} timed out twice — bailing")
                            asr.release()
                            throw TranscriptionBailout(AsrEvent.Failed(
                                "Transcription stalled twice on chunk ${chunkIdx + 1} " +
                                    "(${CHUNK_TIMEOUT_MS / 60_000} min each). The Gemma " +
                                    "engine appears stuck on this audio — try a different " +
                                    "audio segment, switch to the CPU backend in Settings " +
                                    "→ Gemma 4 compute, or fall back to Whisper."
                            ))
                        }
                    }
                    val elapsedMs = System.currentTimeMillis() - tStart
                    Log.i(TAG, "← chunk ${chunkIdx + 1} done in ${elapsedMs}ms " +
                        "(${text.length} chars)")
                    if (text.isNotEmpty()) {
                        val endSec = chunk.startSeconds + chunk.samples.size.toDouble() / chunk.sampleRate
                        // Skip the overlap (recap) seconds when stamping
                        // segment times — Gemma was told NOT to transcribe
                        // those, so the returned text covers the post-recap
                        // window only. Without this, every chunk after the
                        // first would map its text into a time range that
                        // includes the previous chunk's last 3 sec, and the
                        // resulting timeline would double-count those bars.
                        val newContentStart = chunk.startSeconds + chunk.overlapSeconds
                        // Mark this chunk's content start as a boundary
                        // candidate IFF the chunk carries a recap overlap.
                        // Silence-aligned cuts emit overlapSamples=0
                        // (audio already split cleanly, no duplicate
                        // transcription possible) — registering them as
                        // boundaries would only invite false-positive
                        // dedup on legitimate cross-chunk content. Only
                        // hard-cut boundaries (overlapSamples>0) need the
                        // dedup safety net.
                        if (chunkIdx > 0 && out.isNotEmpty() && chunk.overlapSamples > 0) {
                            chunkBoundaryStartSeconds.add(newContentStart)
                        }
                        if (useGemmaDiar) {
                            // Parse "Speaker N:" prefixes into separate
                            // RawSegments so the persisted DB rows carry
                            // speaker metadata. Cross-chunk speaker IDs are
                            // not guaranteed to line up — that's why this
                            // path is labelled experimental in the UI.
                            out += parseGemmaDiarSegments(
                                text = text,
                                chunkStart = newContentStart,
                                chunkEnd = endSec,
                            )
                        } else {
                            out += RawSegment(
                                startSeconds = newContentStart,
                                endSeconds = endSec,
                                text = text,
                            )
                        }
                        // Carry the tail forward as continuation context.
                        // When diarization is on, keep the trailing
                        // "Speaker N:" marker so the NEXT chunk knows who
                        // was speaking last — this anchors cross-chunk
                        // speaker numbering, which is otherwise the single
                        // biggest weakness of the Gemma-only diar path
                        // (a name introduced in chunk 1 as Speaker 1 may
                        // arbitrarily flip to Speaker 2 in chunk 2 without
                        // this anchor). We grab the last labelled span:
                        // everything from the FINAL "Speaker N:" forward,
                        // capped at ~250 chars.
                        previousContext = if (useGemmaDiar) {
                            val markerRe = Regex(
                                "\\bSpeaker\\s+\\d+\\s*:",
                                RegexOption.IGNORE_CASE,
                            )
                            val tail = text.takeLast(250)
                            if (markerRe.containsMatchIn(tail)) {
                                // Tail already shows who was speaking last —
                                // good, pass it through as-is.
                                tail
                            } else {
                                // The tail dropped past the last marker
                                // (chunk ended with a long monologue). Stitch
                                // the marker label back on so Gemma can keep
                                // the speaker numbering anchored. Without this
                                // anchor speaker IDs flip arbitrarily across
                                // chunk boundaries even with cross-chunk
                                // context — the single biggest weakness of
                                // the Gemma-only diar path.
                                val last = markerRe.findAll(text).lastOrNull()
                                if (last != null) "[continuing as ${last.value}] $tail"
                                else tail
                            }
                        } else {
                            text.takeLast(250)
                        }

                        // Incremental save: write what we have so the Detail
                        // screen renders text as Gemma chews through chunks.
                        // Without this the user stares at "Transcribing 15%"
                        // for the whole multi-minute run while the phone
                        // gets warm — looks like a hang even though ASR is
                        // actively running. Cheap: writing 50 segments to
                        // Room is sub-millisecond, dwarfed by Gemma's
                        // per-chunk seconds.
                        // Sherpa-authoritative identity in hybrid mode:
                        // override Gemma's parsed Speaker N labels with
                        // the cluster ID from sherpa at that segment's
                        // timestamp. In pure-Gemma mode the parsed Gemma
                        // ID stands (with all its cross-chunk-consistency
                        // caveats).
                        val sherpaForChunk = sherpaSegmentsFromPrepass
                        val perChunkAssignedRaw: List<Pair<RawSegment, Int?>> =
                            if (useGemmaDiar && sherpaForChunk != null) {
                                assignSpeakers(out, sherpaForChunk)
                            } else if (useGemmaDiar) {
                                out.map { raw ->
                                    val (_, gid) = stripGemmaSpeakerMarker(raw.text)
                                    raw to gid
                                }
                            } else {
                                out.map { it to null }
                            }
                        // Strip Gemma's inline "Speaker N:" marker from the
                        // TEXT (independent of where the speaker id itself
                        // came from — sherpa or the marker) before any
                        // text-merging step below, so a turn merge never
                        // leaves a stray marker stitched mid-sentence.
                        val markerFreeRaw = if (useGemmaDiar) {
                            perChunkAssignedRaw.map { (raw, spkId) ->
                                raw.copy(text = stripGemmaSpeakerMarker(raw.text).first) to spkId
                            }
                        } else perChunkAssignedRaw
                        // Merge short isolated backchannels ("yeah", "mhm")
                        // into the surrounding speaker's turn and drop pure-
                        // filler noise. Cheap, runs on each incremental save
                        // so the UI shows the cleaned-up attribution as
                        // Gemma streams chunks. General turn coalescing
                        // (same-speaker merge across a tunable gap) runs
                        // further down, AFTER dedupChunkBoundaries — it
                        // would otherwise merge two chunks' segments into
                        // one BEFORE the boundary-seam dedup pass gets a
                        // chance to see them as separate, letting a
                        // duplicated recap phrase slip through un-trimmed.
                        val perChunkAssigned = dropPureFillerSegments(coalesceBackchannels(markerFreeRaw))
                        val partialRows = perChunkAssigned.map { (raw, spkId) ->
                            Segment(
                                recordingId = recordingId,
                                startSeconds = raw.startSeconds,
                                endSeconds = raw.endSeconds,
                                text = raw.text.trim(),
                                language = translateTo ?: primaryLanguage,
                                speaker = spkId?.let { "SPEAKER_%02d".format(it) },
                            )
                        }
                        // Auto-detect speaker names from intro phrases
                        // ("Hi, I'm Ahmed" → name SPEAKER_01 "Ahmed" everywhere
                        // they appear). Applied incrementally so prose mode
                        // shows the discovered name as soon as the relevant
                        // chunk lands, not only at the end. Cheap regex scan;
                        // adds well under a millisecond per save.
                        val dedupedPartial = dedupChunkBoundaries(
                            partialRows,
                            chunkBoundaryStartSeconds = chunkBoundaryStartSeconds,
                        )
                        // De-chunk: the ~28s chunk windows are an implementation
                        // detail — the transcript should read as speaker TURNS.
                        // Ports the Mac app's coalesceBySpeaker (default 30s gap).
                        // Diarized runs only, as on the Mac: without speakers
                        // every row has speaker == null and the whole
                        // transcript would merge into one row.
                        val coalescedPartial =
                            if (diarize) coalesceTurnSegments(dedupedPartial, turnCoalesceGapSec) else dedupedPartial
                        val rowsWithNames = applyInferredSpeakerNames(coalescedPartial)
                        repository.replaceSegments(recordingId, rowsWithNames)
                    }
                    // The streaming onPartialText callback already emitted
                    // per-token Stage events during this chunk. Bump the
                    // chunk counter and emit one final Stage at the chunk's
                    // post-completion fraction so the progress bar lands
                    // exactly on the chunk-boundary tick before the next
                    // chunk starts mid-streaming again.
                    chunkIdx++
                    val finalLabel = if (expectedChunks > 0)
                        "Transcribing $chunkIdx/$expectedChunks"
                    else "Transcribing (chunk $chunkIdx)"
                    val finalFraction = if (expectedChunks > 0)
                        (0.15f + 0.80f * chunkIdx / expectedChunks).coerceAtMost(0.95f)
                    else 0.5f
                    send(AsrEvent.Stage(finalLabel, finalFraction))
                }
            } catch (b: TranscriptionBailout) {
                // Specific catch must come before the generic Throwable
                // catch — the bail-out path inside collect throws this to
                // hop the (non-inline) collect boundary without an illegal
                // non-local return. The Failed event was already prepared
                // by the throw site; just relay it and exit the flow.
                send(b.failure)
                return@channelFlow
            } catch (t: Throwable) {
                asr.release()
                send(AsrEvent.Failed("Failed to decode/transcribe $audioPath: ${t.message}"))
                return@channelFlow
            }
            rawSegments = out
            // Gemma did the diarization inline; we don't run sherpa-onnx.
            mono = FloatArray(0)
        } else {
            // Whisper path.
            // Whisper.cpp's JNI API takes a single language. For constrained
            // auto with Whisper we'd need to add detect+filter logic — for now
            // multi-select degrades to full auto (null) when more than one is
            // selected. Future work: port the Mac-app's allowed_languages logic.
            // Whisper.cpp's translate flag only goes to English. If the user
            // asked for a non-English target with the Whisper backend, fall
            // back to "transcribe in source" — better than silently producing
            // English when they asked for Arabic. The UI will warn about this
            // mismatch up front.
            val whisperTranslate = translateTo == "en"
            val audioFile = File(audioPath)
            if (recording.durationSeconds <= WHISPER_CHUNK_THRESHOLD_SECONDS) {
                // Short file — single-shot full-buffer decode+transcribe,
                // same shape this app has always used. The decoded buffer
                // doubles as `mono` for diarization below.
                val decoded = runCatching { AudioDecoder.decode(audioFile) }
                    .getOrElse {
                        send(AsrEvent.Failed("Failed to decode $audioPath: ${it.message}"))
                        asr.release()
                        return@channelFlow
                    }
                mono = decoded.samples
                send(AsrEvent.Stage("Transcribing", 0.2f))
                val tx = asr.transcribe(
                    samples = mono,
                    sampleRate = decoded.sampleRate,
                    language = primaryLanguage,
                    translate = whisperTranslate,
                    progress = null,
                )
                if (tx.isFailure) {
                    asr.release()
                    send(AsrEvent.Failed(tx.exceptionOrNull()?.message ?: "transcription failed"))
                    return@channelFlow
                }
                rawSegments = tx.getOrThrow()
            } else {
                // Long file — stream-decode and chunk at VAD cut points
                // instead of loading the whole clip into one FloatArray
                // (doubled again by jni_whisper.cpp's own native-side
                // copy) — that full-buffer load was the actual OOM driver
                // on multi-hour audio. whisper.cpp's C API needs the whole
                // buffer PER CALL, but nothing stops calling it once per
                // chunk and stitching with a time offset, same shape as
                // SherpaOfflineBackend already does for Parakeet/
                // Omnilingual. Reuses the exact scanSilences/
                // computeCutPoints/decodeAtCutPoints pipeline the Gemma
                // branch above already exercises.
                send(AsrEvent.Stage("Scanning for sentence boundaries", 0.12f))
                val silences = runCatching {
                    AudioDecoder.scanSilences(
                        audioFile,
                        minSilenceMs = CHUNK_ALIGN_MIN_SILENCE_MS,
                        rmsThreshold = CHUNK_ALIGN_RMS_THRESHOLD,
                    )
                }.getOrElse {
                    Log.w(TAG, "silence scan failed; falling back to fixed-time chunks", it)
                    emptyList()
                }
                val cutPoints = AudioDecoder.computeCutPoints(
                    silences = silences,
                    durationSec = recording.durationSeconds,
                    targetChunkSec = WHISPER_CHUNK_TARGET_SECONDS,
                    flexSec = CHUNK_ALIGN_FLEX_SECONDS,
                )
                val expectedWhisperChunks = cutPoints.size + 1
                val whisperOverlapSamples = CHUNK_OVERLAP_SECONDS * AudioDecoder.TARGET_SR
                val out = mutableListOf<RawSegment>()
                var chunkIdx = 0
                try {
                    AudioDecoder.decodeAtCutPoints(
                        audioFile,
                        cutPoints = cutPoints,
                        hardCutOverlapSamples = whisperOverlapSamples,
                        silenceCutOverlapSamples = 0,
                    ).collect { chunk ->
                        send(AsrEvent.Stage(
                            "Transcribing ${chunkIdx + 1}/$expectedWhisperChunks",
                            (0.15f + 0.75f * chunkIdx / expectedWhisperChunks).coerceAtMost(0.95f),
                        ))
                        val tx = asr.transcribe(
                            samples = chunk.samples,
                            sampleRate = chunk.sampleRate,
                            language = primaryLanguage,
                            translate = whisperTranslate,
                            progress = null,
                        )
                        if (tx.isFailure) {
                            throw TranscriptionBailout(AsrEvent.Failed(
                                "Chunk ${chunkIdx + 1} failed: " +
                                    (tx.exceptionOrNull()?.message ?: "unknown")
                            ))
                        }
                        val offset = chunk.startSeconds
                        for (seg in tx.getOrThrow()) {
                            out += RawSegment(
                                startSeconds = seg.startSeconds + offset,
                                endSeconds = seg.endSeconds + offset,
                                text = seg.text,
                                words = seg.words?.map { w ->
                                    w.copy(start = w.start + offset, end = w.end + offset)
                                },
                            )
                        }
                        // Unlike Gemma, whisper.cpp has no "skip the recap"
                        // prompting — it transcribes the whole chunk
                        // including the overlap, so a hard-cut chunk
                        // boundary genuinely produces duplicate text at the
                        // seam. Register it for dedupChunkBoundaries below
                        // (same "only hard cuts need dedup" rule as the
                        // Gemma branch — silence-aligned cuts carry zero
                        // overlap, nothing to dedup).
                        if (chunkIdx > 0 && chunk.overlapSamples > 0) {
                            chunkBoundaryStartSeconds.add(chunk.startSeconds)
                        }
                        chunkIdx++
                    }
                } catch (b: TranscriptionBailout) {
                    asr.release()
                    send(b.failure)
                    return@channelFlow
                } catch (t: Throwable) {
                    asr.release()
                    send(AsrEvent.Failed("Failed to decode/transcribe $audioPath: ${t.message}"))
                    return@channelFlow
                }
                rawSegments = out
                // Diarization (if requested) goes through the windowed,
                // file-streaming path below — decoding the whole file into
                // one buffer here would reintroduce exactly the OOM this
                // chunked ASR pass exists to avoid.
                mono = FloatArray(0)
                diarizeWindowed = diarize
            }
        }

        // Optional diarization. Three flavours:
        //   1. Gemma + hybrid (sherpa pre-pass already ran). Use sherpa's
        //      global clusters as the source of truth for identity; Gemma's
        //      inline labels are kept aligned by the hints we sent.
        //   2. Gemma + pure (no sherpa). Trust Gemma's parsed Speaker N labels
        //      as best-effort; cross-chunk consistency is not guaranteed.
        //   3. Whisper + sherpa. Run sherpa post-ASR on the full mono buffer.
        val gemmaDiarized = gemma != null && diarize
        val assignedRaw: List<Pair<RawSegment, Int?>> = if (gemmaDiarized && sherpaSegmentsFromPrepass != null) {
            // Hybrid reconcile. Each parsed Gemma piece has chunk-relative
            // (start, end) from parseGemmaDiarSegments + an encoded
            // Gemma-numbered speaker id. Run assignSpeakers to look up the
            // sherpa cluster id by max-overlap; that's the global answer.
            // Log how often Gemma disagreed with sherpa so we have a signal
            // about how well the hints are sticking.
            var conflicts = 0
            val sherpaAssigned = assignSpeakers(rawSegments, sherpaSegmentsFromPrepass)
            sherpaAssigned.map { (raw, sherpaId) ->
                val (cleanText, gemmaId) = stripGemmaSpeakerMarker(raw.text)
                // Gemma id is 1-based ("Speaker 1"); sherpa id is 0-based.
                if (gemmaId != null && sherpaId != null && (gemmaId - 1) != sherpaId) {
                    conflicts++
                }
                raw.copy(text = cleanText) to sherpaId
            }.also {
                android.util.Log.i(
                    "TxRunner",
                    "hybrid diar reconcile: $conflicts conflicts / ${it.size} segments " +
                        "(sherpa won; conflicts mean Gemma ignored a hint)"
                )
            }
        } else if (gemmaDiarized) {
            rawSegments.map { seg ->
                val (cleanText, speakerId) = stripGemmaSpeakerMarker(seg.text)
                seg.copy(text = cleanText) to speakerId
            }
        } else if (diarize && rawSegments.isNotEmpty()) {
            send(AsrEvent.Stage("Identifying speakers", 0.7f))
            val diarRes = if (diarizeWindowed) {
                runCatching {
                    diarizer.runChunked(
                        file = File(audioPath),
                        numClusters = expectedSpeakers,
                        threshold = clusterThreshold,
                        minDurationOn = minDurationOn,
                        minDurationOff = minDurationOff,
                        durationSec = recording.durationSeconds,
                        onProgress = { fraction ->
                            val pct = (fraction * 100f).toInt().coerceIn(0, 99)
                            trySend(AsrEvent.Stage(
                                "Identifying speakers · $pct%",
                                (0.7f + 0.25f * fraction).coerceAtMost(0.95f),
                            ))
                        },
                        onOverSegmented = {
                            trySend(AsrEvent.Stage(
                                "Lots of speakers detected — if you know how many, " +
                                    "set Expected Speakers in the RUN sheet",
                                0.9f,
                            ))
                        },
                    ).getOrThrow()
                }
            } else diarizer.run(
                samples = mono,
                numClusters = expectedSpeakers,
                threshold = clusterThreshold,
                minDurationOn = minDurationOn,
                minDurationOff = minDurationOff,
            )
            if (diarRes.isFailure) {
                // Don't fail the whole run — transcription succeeded. Surface a
                // warning by way of an empty speaker column.
                send(AsrEvent.Stage(
                    "Diarization failed: ${diarRes.exceptionOrNull()?.message}",
                    0.9f,
                ))
                rawSegments.map { it to null }
            } else {
                assignSpeakers(rawSegments, diarRes.getOrThrow())
            }
        } else {
            rawSegments.map { it to null }
        }
        // Backchannel coalescing + filler-drop on the FINAL assignment. The
        // incremental chunk-loop already runs both per-batch, but only the
        // full-file pass has both the left AND right neighbour of every
        // interior segment available, so this catches a few the partial
        // pass couldn't sandwich-detect at chunk boundaries.
        val assigned = dropPureFillerSegments(coalesceBackchannels(assignedRaw))

        send(AsrEvent.Stage("Saving", 0.97f))
        // Effective output language per segment. If we translated, that's
        // the target language (or "en" when Whisper degraded a non-English
        // target). Otherwise it's the source language we transcribed in.
        val effectiveOutputLang = when {
            translateTo != null && backend == AsrBackendKind.WhisperCpp ->
                if (translateTo == "en") "en" else primaryLanguage
            translateTo != null -> translateTo
            else -> primaryLanguage
        }
        val rows = assigned.map { (raw, spkId) ->
            Segment(
                recordingId = recordingId,
                startSeconds = raw.startSeconds,
                endSeconds = raw.endSeconds,
                text = raw.text.trim(),
                language = effectiveOutputLang,
                speaker = spkId?.let { "SPEAKER_%02d".format(it) },
            )
        }
        val dedupedRows = dedupChunkBoundaries(
            rows,
            chunkBoundaryStartSeconds = chunkBoundaryStartSeconds,
        )
        // De-chunk: merge same-speaker turns across the tunable gap, same
        // as the incremental path — this final pass also coalesces
        // whatever the per-chunk passes couldn't (e.g. Whisper's single
        // full-file run never went through the incremental path at all).
        // Diarized runs only (see the incremental call for why).
        val coalescedRows =
            if (diarize) coalesceTurnSegments(dedupedRows, turnCoalesceGapSec) else dedupedRows
        val rowsWithNames = applyInferredSpeakerNames(coalescedRows)
        repository.replaceSegments(recordingId, rowsWithNames)
        // Re-read the row: the user may have re-titled, re-filed
        // (folderId) or categorised the recording while this run was in
        // flight, and Room's @Update replaces the WHOLE row — writing back
        // the copy we fetched at the start would silently revert that.
        val fresh = repository.get(recordingId) ?: recording
        // The Recording row keeps a boolean "was translated?" — the actual
        // target lives in Segment.language. Avoids a Room migration while
        // still letting the UI surface "Translated to {target}" by looking
        // at the segments. See RecordingsListScreen / TranscriptExporter.
        var updated = fresh.copy(
            transcribedWithBackend = asr.id,
            transcribedWithModel = modelFile.name,
            translateToEnglish = translateTo != null,
            sourceLanguage = fresh.sourceLanguage ?: primaryLanguage,
        )

        // Text work (auto-title, auto-classify) is Gemma's job regardless
        // of which engine transcribed — with Parakeet as the default ASR
        // engine, gating this on `gemma != null` would mean it almost never
        // runs. Reuse the ASR engine when it IS Gemma; otherwise release
        // the ASR engine first (memory) and load a standalone Gemma for
        // the two short prompts, if one is installed and fits.
        val wantsTitle = rows.isNotEmpty() && looksLikeDefaultTitle(updated.title)
        val wantsCategory = rows.isNotEmpty() && updated.category == null
        var asrReleased = false
        var standaloneTextGemma: Gemma4Backend? = null
        val textGemma: Gemma4Backend? = gemma ?: run {
            if (!wantsTitle && !wantsCategory) return@run null
            val gModel = factory.resolveModel(AsrBackendKind.Gemma4) ?: return@run null
            if (checkGemmaE4BMemory() != null) return@run null
            asr.release()
            asrReleased = true
            send(AsrEvent.Stage("Loading Gemma for naming", 0.975f))
            val g = factory.create(AsrBackendKind.Gemma4) as? Gemma4Backend ?: return@run null
            val res = g.load(gModel.absolutePath)
            if (res.isFailure) {
                Log.w(TAG, "standalone Gemma for text work failed to load; skipping title/classify", res.exceptionOrNull())
                g.release()
                null
            } else {
                standaloneTextGemma = g
                g
            }
        }

        // Auto-title: if the recording still has the default timestamp-style
        // title, ask Gemma for a short descriptive title. This is the
        // biggest single Library improvement — "Recording_2026-05-17_14-23-30"
        // is useless, "Q3 planning with Ahmed and Sara" is actually findable.
        if (textGemma != null && wantsTitle) {
            send(AsrEvent.Stage("Naming the recording", 0.98f))
            val title = generateTitle(textGemma, rows)
            if (!title.isNullOrBlank()) {
                updated = updated.copy(title = title)
            }
        }
        // Auto-classify: Meeting/Interview/Note/Idea. Unlike the title
        // heuristic above, this never clobbers an existing value — once a
        // recording has a category (from this or a future manual choice),
        // a re-transcription leaves it alone rather than silently
        // re-guessing. New to the Android app; see RecordingCategory's doc
        // comment.
        if (textGemma != null && wantsCategory) {
            val category = classifyRecording(textGemma, rows)
            if (category != null) {
                updated = updated.copy(category = category.id)
            }
        }
        standaloneTextGemma?.release()

        repository.update(updated)
        if (!asrReleased) asr.release()

        // Sidecar files (.txt / .srt / .json) next to the audio. Cheap to
        // write, lets the user share the transcript via the OS share sheet
        // and keeps a non-Room copy in case the database ever resets.
        runCatching { TranscriptExporter.writeSidecars(updated, rows) }

        send(AsrEvent.Done(rawSegments))
    }

    /**
     * Heuristic: does this title look like one the user hasn't customized?
     * If so we'll ask Gemma to suggest a better one after transcription.
     *
     * Patterns recognised:
     *   - "Recording_…"                         our own Record-screen format
     *   - "1716992345678_voice memo.m4a"        Android import (millis prefix)
     *   - "Voice 001", "Voice memo 17"          generic dictaphone defaults
     *   - "New Recording 12-05-2025 14:23"      Samsung Voice Recorder default
     *   - "Voice Memo 2024-01-15 3:45 PM"       iOS Voice Memos default share
     *   - "audio_record_2024-01-15"             OpenRecorder / similar
     *   - "REC_…", "MEMO_…", "AUD_…"            common dashcam/dictaphone prefixes
     *
     * We deliberately keep the list specific — a too-aggressive heuristic
     * would rename files the user did want to keep (e.g. "Coffee chat May 3").
     */
    private fun looksLikeDefaultTitle(title: String): Boolean {
        val t = title.trim()
        if (t.isEmpty()) return true
        if (t.startsWith("Recording_", ignoreCase = true)) return true
        if (Regex("^\\d{13}_").containsMatchIn(t)) return true
        // iOS Voice Memos: "Voice Memo 2024-01-15 3:45 PM" or "New Recording 12".
        if (Regex("^(voice memo|voice|new recording)\\b", RegexOption.IGNORE_CASE).containsMatchIn(t)) return true
        // OpenRecorder + similar: "audio_record_…" / "audio-…"
        if (Regex("^audio[_\\- ]", RegexOption.IGNORE_CASE).containsMatchIn(t)) return true
        // Short uppercase prefixes commonly used by recorder apps.
        if (Regex("^(REC|MEMO|AUD)[_\\- ]", RegexOption.IGNORE_CASE).containsMatchIn(t)) return true
        return false
    }

    private suspend fun generateTitle(
        gemma: Gemma4Backend,
        segments: List<Segment>,
    ): String? {
        // Grab the first ~500 chars — plenty for a model to pick a topic.
        val excerpt = buildString {
            for (s in segments) {
                if (length > 1500) break
                append(s.text).append(' ')
            }
        }.trim().take(2000)
        if (excerpt.isEmpty()) return null

        val systemPrompt = """
            You are naming an audio recording based on a short excerpt of its
            transcript. Reply with exactly one short descriptive title:
            - 3 to 8 words
            - Title Case
            - No quotation marks, no preamble, no trailing punctuation
            - No commentary, no explanation
            - Capture the topic, not the speakers
            If the excerpt is unintelligible, reply with "Untitled Recording".
        """.trimIndent()
        val res = gemma.generateText(systemPrompt, "Transcript excerpt:\n$excerpt")
        return res.getOrNull()
            ?.trim()
            ?.removePrefix("\"")?.removeSuffix("\"")
            ?.take(80)
            ?.takeIf { it.isNotBlank() }
    }

    /** Same excerpt-building approach as [generateTitle], reused independently so a title-generation failure never blocks classification. */
    private suspend fun classifyRecording(
        gemma: Gemma4Backend,
        segments: List<Segment>,
    ): RecordingCategory? {
        val excerpt = buildString {
            for (s in segments) {
                if (length > 1500) break
                append(s.text).append(' ')
            }
        }.trim().take(2000)
        if (excerpt.isEmpty()) return null
        return gemma.suggestCategory(excerpt)
    }
}

/**
 * Parse Gemma's diarized output back into segments. The model is asked to
 * emit lines like `Speaker 1: text...` and switch on speaker changes. We
 * split by these markers, attribute each piece to a speaker, and stamp
 * start/end times by lerping the chunk's overall time range over the
 * proportion of characters each speaker's piece takes.
 *
 * Imperfect (we don't have token-level timestamps from Gemma) but accurate
 * enough for the Library timestamp gutter. Each piece is a single RawSegment
 * with the speaker id encoded into its text via a marker that
 * [stripGemmaSpeakerMarker] reads later.
 */
private fun parseGemmaDiarSegments(
    text: String,
    chunkStart: Double,
    chunkEnd: Double,
): List<RawSegment> {
    // Split on `Speaker N:` markers wherever they appear in the text. The
    // previous implementation anchored at line-start; in practice Gemma
    // sometimes packs two speakers onto one line ("Speaker 1: Hi. Speaker
    // 2: Hello.") and the trailing marker got swallowed into Speaker 1's
    // body. In prose mode this showed up as one block containing the
    // literal string "Speaker 2:" followed by another properly-split
    // turn, looking like the chunks duplicated themselves.
    //
    // \b lets us match anywhere while keeping us out of words like
    // "loudspeaker" (the case-insensitive flag still finds "speaker" but
    // the trailing `\s+\d+\s*:` filters out non-markers).
    val re = Regex("\\bSpeaker\\s+(\\d+)\\s*:\\s*", RegexOption.IGNORE_CASE)
    val matches = re.findAll(text).toList()
    val pieces = mutableListOf<Pair<Int?, String>>()
    if (matches.isEmpty()) {
        // No markers — single piece with null speaker. Sherpa-authoritative
        // mode will assign it via timestamp overlap downstream.
        val body = text.trim()
        if (body.isNotEmpty()) pieces.add(null to body)
    } else {
        // Pre-marker prefix. Two flavours we see in practice:
        //   (a) A stray preamble like "Two speakers detected:" or "Continuing
        //       from where it stopped, here's the next part..." — usually
        //       Gemma narrating the prompt back at us, not real audio. These
        //       are short.
        //   (b) Actual unlabeled speech before the first labelled turn —
        //       longer, ends mid-sentence right where the speaker label
        //       picks up.
        // We can't perfectly distinguish without semantic understanding,
        // but Gemma's narration is always short and ends in a colon/comma
        // or starts with a giveaway lead-in. Strip those; keep longer
        // prefixes as a null-speaker piece so we don't drop real words.
        val firstStart = matches.first().range.first
        if (firstStart > 0) {
            val prefix = text.substring(0, firstStart).trim()
            val looksLikeNarration = prefix.length < 80 || Regex(
                "^(continuing|here(?:'s| is| are)|the (?:transcription|translation)|" +
                    "okay|sure|of course|in this segment|this segment|" +
                    "two speakers|multiple speakers|the speakers)\\b",
                RegexOption.IGNORE_CASE,
            ).containsMatchIn(prefix)
            if (prefix.isNotEmpty() && !looksLikeNarration) {
                pieces.add(null to prefix)
            }
        }
        // Each match captures the body up to the next match (or end of
        // text). Empty bodies are dropped — "Speaker 1: Speaker 2: hi" is
        // one Speaker 2 turn, not a phantom Speaker 1 one.
        for ((i, m) in matches.withIndex()) {
            val speakerNum = m.groupValues[1].toIntOrNull()
            val bodyStart = m.range.last + 1
            val bodyEnd = if (i + 1 < matches.size) matches[i + 1].range.first else text.length
            val body = text.substring(bodyStart, bodyEnd).trim()
            if (body.isNotEmpty()) pieces.add(speakerNum to body)
        }
    }

    if (pieces.isEmpty()) return emptyList()
    val totalChars = pieces.sumOf { it.second.length }.coerceAtLeast(1)
    val span = chunkEnd - chunkStart
    var charsSoFar = 0
    return pieces.mapNotNull { (spk, body) ->
        if (body.isBlank()) return@mapNotNull null
        val start = chunkStart + (charsSoFar.toDouble() / totalChars) * span
        charsSoFar += body.length
        val end = chunkStart + (charsSoFar.toDouble() / totalChars) * span
        // We encode the speaker id into the segment text with a sentinel
        // prefix that stripGemmaSpeakerMarker reads later, then strips before
        // persistence. Avoids growing RawSegment's shape just for this path.
        val encoded = if (spk != null) " SPK${spk} $body" else body
        RawSegment(startSeconds = start, endSeconds = end, text = encoded)
    }
}

/**
 * Pulls the speaker-id marker added by [parseGemmaDiarSegments] back out
 * and returns (cleanText, speakerId). If no marker, returns (text, null).
 */
private fun stripGemmaSpeakerMarker(text: String): Pair<String, Int?> {
    val re = Regex("^ SPK(\\d+) ")
    val match = re.find(text) ?: return text to null
    val id = match.groupValues[1].toIntOrNull()
    val rest = text.substring(match.range.last + 1)
    return rest to id
}

/**
 * [coalesceTurns] for already-persisted-shape [Segment] rows — used here
 * instead of the [RawSegment] version because turn coalescing has to run
 * AFTER [dedupChunkBoundaries]: merging two chunks' segments into one
 * BEFORE the boundary-seam dedup pass would hide the seam from it, letting
 * a duplicated recap phrase slip through un-trimmed. Same merge rule:
 * same speaker + gap below [gapSec] → concatenate into one row.
 */
internal fun coalesceTurnSegments(rows: List<Segment>, gapSec: Double): List<Segment> {
    val sorted = rows.sortedBy { it.startSeconds }
    val out = mutableListOf<Segment>()
    for (seg in sorted) {
        val last = out.lastOrNull()
        if (last != null && last.speaker == seg.speaker && seg.startSeconds - last.endSeconds < gapSec) {
            out[out.size - 1] = last.copy(
                endSeconds = maxOf(last.endSeconds, seg.endSeconds),
                text = last.text + " " + seg.text,
            )
        } else {
            out.add(seg)
        }
    }
    return out
}

/**
 * Scan transcript text for self-introduction phrases ("Hi, I'm Ahmed",
 * "My name is Sara", "This is Yuri speaking", plus Ukrainian/Dutch/Arabic
 * equivalents — see [findIntroducedName]) and propagate the detected name
 * to every segment with the same speaker key. First match wins per
 * speaker — once a key gets a name, subsequent intros for that key are
 * ignored (real conversations have one canonical name per voice).
 *
 * Also applies the addressee rule, ported from the Mac app
 * (`Transcriberr/ASR/DiarizationRunner.swift` `inferSpeakerNames`): in a
 * TWO-person conversation, greeting someone by name ("Hi, Lana") implies
 * the OTHER speaker's name — the one inference self-introductions alone
 * can't make.
 *
 * Conservative by design: a false positive ("I'm tired") would be visible
 * in the UI and require manual cleanup, so we only match capitalized
 * names following a small set of high-precision lead-ins, and skip a
 * stoplist of common words that look like names after "I'm". Arabic has
 * no letter case, so its stoplist carries more of the precision burden.
 *
 * Runs on the full row set on every chunk save so a name found in chunk N
 * back-fills earlier rows for the same speaker as soon as it's discovered.
 */
internal fun applyInferredSpeakerNames(rows: List<Segment>): List<Segment> {
    if (rows.isEmpty()) return rows
    val inferred = mutableMapOf<String, String>()
    for (seg in rows) {
        val key = seg.speaker ?: continue
        if (key in inferred) continue
        val cand = findIntroducedName(seg.text) ?: continue
        inferred[key] = cand
    }

    val keys = rows.mapNotNull { it.speaker }.distinct()
    if (keys.size == 2) {
        val (a, b) = keys
        for (seg in rows) {
            val key = seg.speaker ?: continue
            val other = if (key == a) b else a
            if (other in inferred) continue
            val m = GREETING_NAME_PATTERN.find(seg.text) ?: continue
            val cand = m.groupValues[1].trim()
            if (isStoplistedName(cand) || cand == inferred[key]) continue
            inferred[other] = cand
        }
    }

    if (inferred.isEmpty()) return rows
    return rows.map { seg ->
        val key = seg.speaker
        val name = if (key != null) inferred[key] else null
        // Preserve any user-edited name. In practice rows reaching this
        // function are fresh-built and always have speakerName == null,
        // but guard defensively in case a future caller passes
        // pre-populated rows.
        if (name != null && seg.speakerName == null) seg.copy(speakerName = name) else seg
    }
}

/**
 * Common false-positive words that look like a capitalized first name when
 * Gemma capitalizes the word after "I'm". Lower-case (matched against the
 * candidate's lowercased first word). Add new entries when you spot a real
 * recording producing a wrong auto-name.
 */
private val SPEAKER_NAME_STOPLIST = setOf(
    "going", "trying", "thinking", "sure", "fine", "okay", "good", "great",
    "sorry", "here", "back", "done", "ready", "happy", "looking", "saying",
    "telling", "the", "a", "an", "not", "really", "just", "still", "from",
    "in", "on", "at", "with", "about", "also", "afraid", "tired", "hungry",
    "right", "left", "up", "down", "very", "pretty", "kind", "sort",
    // "Hi, I'm Ahmed" also matches the ADDRESSEE pattern (greeting word +
    // capitalized token) with "I'm" misread as the greeted name — block it
    // here rather than special-casing the addressee regex. isStoplistedName
    // only looks at the first word, so "i'm"/"i" cover "I'm"/"I am" both.
    "i'm", "i", "im",
    // Words that follow a greeting far more often than a name does —
    // "hello, my name is", "hi there", "hey guys/everyone/all/again".
    "my", "there", "guys", "everyone", "everybody", "all", "again", "you",
    "team", "folks", "friends", "dear", "and", "so", "yes", "no", "hi",
    "hello", "hey", "welcome", "thanks", "thank",
    // Dutch — same role as the English list above, for "Ik ben X" etc.
    "moe", "hier", "daar", "klaar", "blij", "boos", "bang", "trots",
    "verdrietig", "zeker", "best", "nu", "nog", "ook", "zo", "sorry",
)

/** Arabic has no case signal to lean on, so this list is doing more precision work per hit. */
private val ARABIC_SPEAKER_NAME_STOPLIST = setOf(
    "متأكد", "متأكدة", "بخير", "هنا", "جاهز", "جاهزة", "آسف", "آسفة",
    "سعيد", "سعيدة", "متعب", "متعبة", "خايف", "خائف", "عارف", "عارفة",
)

private fun isStoplistedName(candidate: String): Boolean {
    val firstWord = candidate.substringBefore(' ').lowercase()
    return firstWord in SPEAKER_NAME_STOPLIST || candidate in ARABIC_SPEAKER_NAME_STOPLIST
}

/**
 * Greeting-by-name pattern for the addressee rule: "Hi/Hello/Привіт/Hoi/
 * مرحبا NAME". Deliberately separate from [findIntroducedName]'s
 * self-introduction patterns — this one captures the person being
 * addressed, not the speaker.
 */
// The greeting word is case-insensitive (inline `(?iu:…)`); the captured
// NAME is not — a whole-pattern IGNORE_CASE would let "hello, my name is…"
// hand "my" to the other speaker as their name. Arabic has no case, so
// its branch relies on the stoplist instead.
private val GREETING_NAME_PATTERN = Regex(
    "(?iu:привіт|вітаю|добрий день|здравствуй|привет|hi|hello|hey|hoi|hallo" +
        "|مرحبا|أهلا|السلام عليكم|صباح الخير|مساء الخير)[,!]?\\s+" +
        "([A-ZА-ЯІЇЄҐ][a-zа-яіїєґё'’\\-]{1,30}|[؀-ۿ]{2,30})",
)

internal fun findIntroducedName(text: String): String? {
    if (text.isBlank()) return null
    val intros = listOf(
        // "Hi/Hello/Hey, I'm/I am/my name is/this is NAME"
        Regex(
            "\\b(?:hi|hello|hey)[!,.\\s]+(?:i'?m|i am|my name is|this is)\\s+" +
                "([A-Z][a-z][a-z'\\-]+(?:\\s+[A-Z][a-z][a-z'\\-]+)?)",
            RegexOption.IGNORE_CASE,
        ),
        // "My name is/my name's NAME"
        Regex(
            "\\bmy name(?:'s| is)\\s+" +
                "([A-Z][a-z][a-z'\\-]+(?:\\s+[A-Z][a-z][a-z'\\-]+)?)",
            RegexOption.IGNORE_CASE,
        ),
        // "This is NAME speaking/here/calling"
        Regex(
            "\\bthis is\\s+([A-Z][a-z][a-z'\\-]+(?:\\s+[A-Z][a-z][a-z'\\-]+)?)" +
                "\\s+(?:speaking|here|calling)",
            RegexOption.IGNORE_CASE,
        ),
        // "I'm NAME, ..." — the comma / following conjunction is the key
        // signal that distinguishes "I'm Ahmed, the engineer" (introduction)
        // from "I'm tired" (state). The cap-letter filter + stoplist catch
        // most other cases.
        Regex(
            "\\bi'?m\\s+([A-Z][a-z][a-z'\\-]+(?:\\s+[A-Z][a-z][a-z'\\-]+)?)" +
                "(?=\\s*[,.]|\\s+(?:and|the|a|an|from|with|at|here|speaking|calling)\\b)",
            RegexOption.IGNORE_CASE,
        ),
        // Ukrainian: "мене звати / мене звуть NAME" ("my name is").
        Regex(
            "(?:мене звати|мене звуть)\\s+([А-ЯІЇЄҐ][а-яіїєґ'’\\-]{1,30})",
            RegexOption.IGNORE_CASE,
        ),
        // Dutch: "Ik ben / Mijn naam is / Dit is NAME".
        Regex(
            "\\b(?:ik ben|mijn naam is|dit is)\\s+([A-Z][a-z][a-z'\\-]+(?:\\s+[A-Z][a-z][a-z'\\-]+)?)",
            RegexOption.IGNORE_CASE,
        ),
        // Arabic: "اسمي NAME" (my name is), "أنا NAME" (I am),
        // "معك NAME" (phone-style "this is" — "you're with NAME"). No case
        // signal in Arabic script, so the stoplist below does more work.
        Regex("(?:اسمي|أنا|معك)\\s+([؀-ۿ]{2,30})"),
    )
    for (re in intros) {
        val m = re.find(text) ?: continue
        val cand = m.groupValues[1].trim()
        if (cand.length !in 2..40) continue
        if (isStoplistedName(cand)) continue
        return cand
    }
    return null
}

/**
 * Internal control-flow sentinel for the chunk loop. Kotlin disallows
 * non-local returns (`return@channelFlow`) from inside a `Flow.collect { }`
 * lambda, so failure paths inside the loop throw this instead; the outer
 * try/catch around the collect handles it by sending the wrapped Failed
 * event and exiting the channelFlow cleanly.
 *
 * Note: extends RuntimeException so it propagates through any non-suspend
 * helper calls inside the loop; the matching catch must be declared
 * BEFORE the generic `catch (t: Throwable)` so it isn't swallowed by the
 * fallback error handler.
 */
private class TranscriptionBailout(val failure: AsrEvent.Failed) : RuntimeException()

/**
 * Trim duplicate text where chunk N's last segment's tail and chunk
 * N+1's first segment's head describe the same words. Happens because
 * we feed each Gemma chunk a small recap overlap from the previous
 * chunk — when Gemma's recap-skipping is imperfect (and per
 * arxiv:2509.09715 it often is), the overlapping audio gets
 * transcribed twice and ends up duplicated across the segment
 * boundary.
 *
 * Algorithm: token-level longest-common-suffix-prefix match. For each
 * consecutive pair, find the largest k such that the LAST k tokens of
 * the prior segment match the FIRST k tokens of the current segment
 * (case- and punctuation-insensitive). If k ≥ [MIN_DEDUP_TOKENS],
 * strip those tokens from the start of the current segment's text.
 *
 * Tokens, not characters, because a character-level match can chop
 * mid-word. Tokens, not full text, because:
 *   - 28-sec chunks rarely produce identical multi-sentence blocks
 *   - We want to catch "you're improving" being repeated, not a single
 *     repeated word
 *
 * Conservative: 3-token minimum. Two-token matches happen incidentally
 * in real speech ("you know", "I mean") and would chew real content.
 */
/**
 * Length of the matched run (in tokens) required to trim a boundary
 * duplicate. Two-token runs ("refused to", "you know") happen too often
 * by chance to be safe; three is the empirical floor where false-
 * positives drop to near-zero in the recordings we tested.
 */
private const val MIN_DEDUP_RUN_TOKENS = 3

/**
 * Maximum tokens at the start of the NEXT chunk that we'll skip over
 * when hunting for a match. The classic boundary failure (per the
 * "literally"/"Really" user report) is: chunk N ended on a homophone
 * variant ("literally refused to") of audio that chunk N+1 also heard
 * (transcribed as "Really refused to see him anymore"). The matching
 * run "refused to" is at the END of prev but the MIDDLE of next —
 * the first token in next ("Really") is a different transcription of
 * the same audio. Allowing 0–3 mismatch tokens at the start of next
 * lets us still align on the run.
 */
private const val MAX_NEXT_PREFIX_SKIP = 3

/** Window of tokens at the boundary we'll search for an overlap match. */
private const val MAX_BOUNDARY_LOOKAHEAD_TOKENS = 15

/**
 * Tolerance (seconds) for matching a segment's startSeconds against a
 * recorded chunk-boundary time. parseGemmaDiarSegments lerps timestamps
 * across the chunk based on character proportions, so a chunk-boundary
 * segment's start can land well after the actual cut point when the
 * boundary segment is short relative to the chunk's full output. 2 s
 * is wide enough to catch that drift in practice; narrower windows
 * (we tried 0.5 s) silently disabled dedup on recap-heavy chunks.
 */
private const val CHUNK_BOUNDARY_MATCH_TOLERANCE_SEC = 2.0

internal fun dedupChunkBoundaries(
    rows: List<Segment>,
    /**
     * Absolute start times of new-chunk content that needs dedup
     * (hard-cut boundaries — silence-aligned cuts emit overlap=0 and
     * are not registered here). Only segments whose `startSeconds`
     * falls within [CHUNK_BOUNDARY_MATCH_TOLERANCE_SEC] of one of these
     * times are dedup candidates. Empty list = no dedup attempted
     * (Whisper path or all-silence-aligned Gemma path).
     */
    chunkBoundaryStartSeconds: List<Double> = emptyList(),
): List<Segment> {
    if (rows.size < 2) return rows
    if (chunkBoundaryStartSeconds.isEmpty()) return rows
    val out = mutableListOf<Segment>()
    out.add(rows[0])
    for (i in 1 until rows.size) {
        val prev = out.last()
        val curr = rows[i]
        // Skip non-boundary pairs. Within-chunk segments can legitimately
        // repeat phrases ("right, right, that's exactly it") and dedupping
        // them silently eats real content.
        val isBoundary = chunkBoundaryStartSeconds.any {
            kotlin.math.abs(curr.startSeconds - it) <= CHUNK_BOUNDARY_MATCH_TOLERANCE_SEC
        }
        if (!isBoundary) {
            out.add(curr)
            continue
        }
        // Cross-speaker guard. If two consecutive segments belong to
        // DIFFERENT speakers, never dedup — they might genuinely echo
        // each other's words ("…and that's the situation." / "And
        // that's the situation we should fix."). Treat null speakers
        // as matching either side so the un-diarized path still
        // dedups normally.
        val sameSpeaker = prev.speaker == null ||
            curr.speaker == null ||
            prev.speaker == curr.speaker
        if (!sameSpeaker) {
            out.add(curr)
            continue
        }
        val prevTokens = tokenize(prev.text)
        val currTokens = tokenize(curr.text)
        val dropCount = findBoundaryOverlapDrop(prevTokens, currTokens)
        if (dropCount > 0) {
            val trimmed = currTokens.drop(dropCount).joinToString(" ").trim()
            if (trimmed.isEmpty()) {
                // runCatching: this function is exercised directly by plain
                // JVM unit tests (no Android framework, no Robolectric) —
                // android.util.Log's real implementation is native and
                // throws when unmocked outside an instrumented run. A
                // logging call must never be able to crash the dedup pass
                // either way, so swallow-on-failure is correct in
                // production too, not just a test workaround.
                runCatching {
                    android.util.Log.d(
                        TAG, "dedup: dropped fully-duplicate segment ($dropCount tokens) " +
                            "at ${curr.startSeconds}s"
                    )
                }
                continue
            }
            runCatching {
                android.util.Log.d(
                    TAG, "dedup: trimmed $dropCount overlapping tokens from segment " +
                        "at ${curr.startSeconds}s"
                )
            }
            out.add(curr.copy(text = trimmed))
        } else {
            out.add(curr)
        }
    }
    return out
}

/**
 * Find the boundary-overlap drop count.
 *
 * Returns the number of tokens to remove from the START of [currTokens]
 * to eliminate a duplicate at the chunk boundary. 0 = no overlap found,
 * leave [currTokens] alone.
 *
 * Algorithm: search for a contiguous run of tokens that is identical
 * (case- and punctuation-insensitive) between:
 *   - the END of prevTokens (last [MAX_BOUNDARY_LOOKAHEAD_TOKENS])
 *   - some position near the START of currTokens (0..MAX_NEXT_PREFIX_SKIP)
 *
 * The "prefix skip" tolerance is the key insight: when the same audio
 * gets transcribed differently on each side of a chunk cut (Gemma can
 * pick a different homophone depending on surrounding context), the
 * matching run sits AFTER the divergent words on the next side. For
 * the user-reported boundary `"...literally refused to"` /
 * `"Really refused to see him anymore"` the run `refused to` is at
 * prev[-2..] and next[1..3) — a prefix skip of 1 ("Really") on the
 * next side. Walking k downward and trying each prefix-skip position
 * picks the longest valid match.
 *
 * Returns the total drop count (prefix-skip + run length) so the
 * caller removes both the divergent words and the duplicate run.
 */
internal fun findBoundaryOverlapDrop(
    prevTokens: List<String>,
    currTokens: List<String>,
): Int {
    val prevTail = prevTokens.takeLast(MAX_BOUNDARY_LOOKAHEAD_TOKENS)
    val currHead = currTokens.take(MAX_BOUNDARY_LOOKAHEAD_TOKENS)
    if (prevTail.size < MIN_DEDUP_RUN_TOKENS) return 0
    if (currHead.size < MIN_DEDUP_RUN_TOKENS) return 0
    // Walk run lengths from large to small; first hit wins (longest
    // wins because we always end the run at the very end of prevTail).
    val maxRun = minOf(prevTail.size, currHead.size, MAX_BOUNDARY_LOOKAHEAD_TOKENS)
    for (k in maxRun downTo MIN_DEDUP_RUN_TOKENS) {
        val prevRun = prevTail.subList(prevTail.size - k, prevTail.size)
        // Try every legal prefix-skip position on the next side. Cap
        // at MAX_NEXT_PREFIX_SKIP so we don't trim arbitrary chunks
        // of legitimate cross-chunk content.
        val maxSkip = minOf(MAX_NEXT_PREFIX_SKIP, currHead.size - k)
        for (skip in 0..maxSkip) {
            var match = true
            for (j in 0 until k) {
                if (!tokensEqual(prevRun[j], currHead[skip + j])) {
                    match = false; break
                }
            }
            if (match) return skip + k
        }
    }
    return 0
}

private fun tokenize(text: String): List<String> = text
    .split(Regex("\\s+"))
    .map { it.trim() }
    .filter { it.isNotEmpty() }

private fun tokensEqual(a: String, b: String): Boolean {
    // Strip surrounding punctuation + lowercase for comparison so
    // "improving" matches "improving," and "Improving" matches "improving".
    val ca = a.trim('.', ',', '!', '?', ';', ':', '"', '\'', '(', ')').lowercase()
    val cb = b.trim('.', ',', '!', '?', ';', ':', '"', '\'', '(', ')').lowercase()
    return ca == cb && ca.isNotEmpty()
}
