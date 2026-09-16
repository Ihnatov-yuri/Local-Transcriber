package nl.ihnatov.transcriber.asr

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationSegment
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Speaker diarization using sherpa-onnx on-device.
 *
 * Two model files are required:
 *   - segmentation.onnx (pyannote 3.0) — bundled in app/src/main/assets/, ~5.7 MB.
 *   - embedding.onnx (3D-Speaker CAM++ VoxCeleb, or WeSpeaker) — downloaded via Settings.
 *
 * On first use we extract the bundled segmentation model to `filesDir/diar/`
 * because sherpa-onnx's `newFromFile` API takes filesystem paths, not asset
 * paths. The embedding model is already in `filesDir/models/` after download.
 *
 * Performance on Snapdragon 8 Gen 3 (S24 Ultra): roughly 0.1× realtime — a
 * 60-second recording diarizes in ~6 seconds.
 */
class DiarizationRunner(
    private val context: Context,
    /**
     * Optional preference store. When non-null and the user has picked
     * a specific embedding filename via Settings, that file overrides
     * the built-in priority cascade. Kept optional so unit tests and
     * test fixtures can construct without wiring full app state.
     */
    private val uiPrefs: UiPrefs? = null,
) {

    data class SpeakerSegment(
        val start: Float,
        val end: Float,
        val speakerId: Int,
        /** sherpa-onnx 1.13.8+ per-segment confidence, 0..1. 1f when unavailable. */
        val confidence: Float = 1f,
    )

    /**
     * Run diarization on a 16 kHz mono float buffer. Returns a list of
     * (start, end, speaker_id) tuples, or fails with [missingEmbedding] etc.
     *
     * A single `process()` call already sees the whole file, so speaker ids
     * come out globally consistent with no stitching needed — unlike
     * [runChunked], which has to reconcile ids across independent windows.
     *
     * @param numClusters  -1 = auto-detect via clustering threshold; >0 = force
     *                     this exact speaker count (when caller already knows).
     * @param threshold    cosine-similarity threshold for speaker clustering;
     *                     0.5 is the sherpa-onnx default. Lower = more clusters.
     */
    suspend fun run(
        samples: FloatArray,
        numClusters: Int = -1,
        threshold: Float = DEFAULT_CLUSTER_THRESHOLD,
        minDurationOn: Float = DEFAULT_MIN_DURATION_ON,
        minDurationOff: Float = DEFAULT_MIN_DURATION_OFF,
        onProgress: ((fraction: Float) -> Unit)? = null,
    ): Result<List<SpeakerSegment>> = withContext(Dispatchers.Default) {
        val embeddingFile = embeddingModelFile()
            ?: return@withContext Result.failure(missingEmbeddingException())
        Log.i(TAG, "diarization: embedding model = " +
            "${embeddingModelDisplayName() ?: "unknown"} (${embeddingFile.name}, " +
            "${embeddingFile.length() / 1024 / 1024} MB)")
        val segFile = ensureSegmentationModelOnDisk()

        try {
            val threads = diarizationThreads()
            val config = OfflineSpeakerDiarizationConfig(
                segmentation = OfflineSpeakerSegmentationModelConfig(
                    pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(segFile.absolutePath),
                    numThreads = threads,
                ),
                embedding = SpeakerEmbeddingExtractorConfig(
                    model = embeddingFile.absolutePath,
                    numThreads = threads,
                ),
                clustering = FastClusteringConfig(numClusters = numClusters, threshold = threshold),
                minDurationOn = minDurationOn,
                minDurationOff = minDurationOff,
            )
            val diar = OfflineSpeakerDiarization(assetManager = null, config = config)
            // Sherpa's blocking native `process(samples)` doesn't respond to
            // coroutine cancellation and `processWithCallback` crashes the
            // JNI on capturing Kotlin lambdas, so the call goes through a
            // DetachedNativeSession: Stop returns at once, the native pass
            // finishes in the background, and the release waits for it.
            // (Releasing from a sibling coroutine mid-call, as before,
            // aborted the whole app — see DetachedNativeSession.)
            val session = DetachedNativeSession("diarization") { diar.release() }
            // Coarse-grained progress: sherpa doesn't expose total duration
            // synchronously, so we emit a heartbeat from a sibling timer
            // and let the UI show "Identifying speakers · still working"
            // every 5 s. Real percentage would require processWithCallback
            // which is the crash path we're avoiding.
            val onProgressCb = onProgress
            val progressTimer = if (onProgressCb != null) {
                launch(Dispatchers.Default) {
                    val started = System.currentTimeMillis()
                    while (isActive) {
                        delay(5_000L)
                        val seconds = (System.currentTimeMillis() - started) / 1000L
                        // Pass elapsed-seconds-as-fraction with NO cap.
                        // Previously capped at 0.95 to keep the bar from
                        // overshooting, but the same value is how the UI
                        // derives "Ns elapsed" — capping made the counter
                        // freeze at "95s elapsed" after 95 seconds. The
                        // UI clamps its own bar separately.
                        onProgressCb(seconds * 0.01f)
                    }
                }
            } else null
            try {
                val segs: Array<OfflineSpeakerDiarizationSegment> =
                    session.call { diar.process(samples) }
                val mapped = segs.map {
                    SpeakerSegment(start = it.start, end = it.end, speakerId = it.speaker, confidence = it.confidence)
                }
                // Auto mode only: collapse over-segmentation. Forced-count
                // mode (numClusters>0) already controls the speaker count.
                val consolidated = if (numClusters <= 0) consolidateSpeakers(mapped) else mapped
                Result.success(renumberByFirstAppearance(consolidated))
            } finally {
                progressTimer?.cancel()
                // Immediate when idle; deferred to the worker thread when a
                // cancelled call is still running natively.
                session.release()
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            Log.i(TAG, "diarization cancelled: ${ce.message}")
            throw ce
        } catch (t: Throwable) {
            Log.e(TAG, "diarization failed", t)
            Result.failure(t)
        }
    }

    /**
     * Windowed diarization for files too large to decode whole. Streams the
     * audio in overlapping windows (default 5-min content + 45-sec overlap),
     * diarizes each window independently for memory, then reconciles
     * identity globally: one canonical embedding per (window, local
     * speaker) — extracted from that speaker's longest segment in the
     * window, via a standalone [SpeakerEmbeddingExtractor] — pooled and run
     * through ONE agglomerative clustering pass ([SpeakerClustering]) over
     * the whole file. That's a few dozen vectors even for an hour-long
     * recording, so the pass is fast despite the audio being long.
     *
     * This replaces the previous temporal-overlap-only stitching, which
     * could only reconcile identity between ADJACENT windows and produced
     * a duplicate id for any speaker silent through a whole overlap zone.
     *
     * Loading a second, standalone embedding extractor alongside
     * [OfflineSpeakerDiarization]'s own internal one means the embedding
     * model sits in memory twice (once per user-facing API surface sherpa
     * exposes — the all-in-one class doesn't expose its internal
     * embeddings). Models here are 28-95 MB, not the multi-GB range that
     * needed [MemoryGuard], so the doubled footprint is an acceptable
     * trade for genuinely global identity.
     *
     * @param windowSec content seconds per window (excludes overlap).
     * @param overlapSec lookback shared with the previous window. No longer
     *   used for stitching (that's embedding-based now), but still shapes
     *   how much of each window's start is "recap" content the segmentation
     *   model has context for.
     */
    suspend fun runChunked(
        file: File,
        windowSec: Double = 300.0,
        overlapSec: Double = 45.0,
        /**
         * -1 = auto-cluster; >0 = force this many speakers GLOBALLY across
         * the whole file (bound to the RUN sheet's "Expected speakers").
         * Per-window local clustering always runs in auto mode — over-
         * segmenting within a window is harmless now, since the global
         * pass merges same-speaker clusters back together regardless of
         * which window they came from.
         */
        numClusters: Int = -1,
        threshold: Float = DEFAULT_CLUSTER_THRESHOLD,
        minDurationOn: Float = DEFAULT_MIN_DURATION_ON,
        minDurationOff: Float = DEFAULT_MIN_DURATION_OFF,
        durationSec: Double = 0.0,
        onProgress: ((fraction: Float) -> Unit)? = null,
        /** Fired at most once, if the final speaker count looks like over-segmentation. */
        onOverSegmented: (() -> Unit)? = null,
    ): Result<List<SpeakerSegment>> = withContext(Dispatchers.Default) {
        val embeddingFile = embeddingModelFile()
            ?: return@withContext Result.failure(missingEmbeddingException())
        Log.i(TAG, "chunked diarization: model=${embeddingModelDisplayName() ?: "?"} " +
            "window=${windowSec}s overlap=${overlapSec}s threshold=$threshold " +
            "clusters=${if (numClusters > 0) numClusters.toString() else "auto"}")
        val segFile = ensureSegmentationModelOnDisk()
        val windowSamples = (windowSec * nl.ihnatov.transcriber.audio.AudioDecoder.TARGET_SR).toInt()
        val overlapSamples = (overlapSec * nl.ihnatov.transcriber.audio.AudioDecoder.TARGET_SR).toInt()
        val sampleRate = nl.ihnatov.transcriber.audio.AudioDecoder.TARGET_SR

        try {
            val threads = diarizationThreads()
            val config = OfflineSpeakerDiarizationConfig(
                segmentation = OfflineSpeakerSegmentationModelConfig(
                    pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(segFile.absolutePath),
                    numThreads = threads,
                ),
                embedding = SpeakerEmbeddingExtractorConfig(
                    model = embeddingFile.absolutePath,
                    numThreads = threads,
                ),
                // Always auto per-window — see the numClusters kdoc above.
                clustering = FastClusteringConfig(numClusters = -1, threshold = threshold),
                minDurationOn = minDurationOn,
                minDurationOff = minDurationOff,
            )
            val diar = OfflineSpeakerDiarization(assetManager = null, config = config)
            val extractor = SpeakerEmbeddingExtractor(
                assetManager = null,
                config = SpeakerEmbeddingExtractorConfig(model = embeddingFile.absolutePath, numThreads = threads),
            )
            // Both native objects share one session: neither may be released
            // while a window is being processed (see DetachedNativeSession
            // for the crash this prevents), and Stop still returns promptly.
            val session = DetachedNativeSession("chunked diarization") {
                runCatching { diar.release() }
                runCatching { extractor.release() }
            }
            // Sherpa reports nothing while it works on a window, and one
            // window can take minutes on a heavy embedding model, so the
            // bar interpolates inside the current window from the previous
            // window's wall time and ticks every 5 s.
            val heartbeat = WindowHeartbeat(durationSec)
            val heartbeatJob = if (onProgress != null && durationSec > 0) {
                launch(Dispatchers.Default) {
                    while (isActive) {
                        delay(5_000L)
                        onProgress(heartbeat.fraction(System.currentTimeMillis()))
                    }
                }
            } else null
            try {
                // One entry per (window, local speaker id) that actually
                // produced a usable embedding.
                data class LocalCluster(val windowIdx: Int, val localId: Int, val embedding: FloatArray)
                val clusters = mutableListOf<LocalCluster>()
                // Raw per-window segments, absolute file time, keyed by
                // (windowIdx, localId) so they can be remapped once global
                // labels are known. Local speakers with no usable embedding
                // go straight into `unmergeable` with their own final id.
                data class PendingSegment(val windowIdx: Int, val localId: Int, val start: Float, val end: Float, val confidence: Float)
                // Everything the native thread produces for one window, so
                // the coroutine side only touches Kotlin state afterwards.
                class WindowResult(
                    val byLocalId: Map<Int, List<OfflineSpeakerDiarizationSegment>>,
                    val embeddings: Map<Int, FloatArray?>,
                )
                val pending = mutableListOf<PendingSegment>()
                var windowIdx = 0
                var nextFallbackId = -1 // counts down; never collides with cluster-label ids (>=0)
                val unmergeableIds = HashMap<Pair<Int, Int>, Int>()
                var lastWindowWallMs = -1L

                nl.ihnatov.transcriber.audio.AudioDecoder.decodeChunked(
                    file,
                    chunkSamples = windowSamples,
                    overlapSamples = overlapSamples,
                ).collect { chunk ->
                    val winStart = chunk.startSeconds
                    val winLenSec = chunk.samples.size.toDouble() / sampleRate
                    heartbeat.beginWindow(winStart, winLenSec, lastWindowWallMs)
                    val startedAt = System.currentTimeMillis()
                    val recapSec = chunk.overlapSeconds
                    val thisWindow = windowIdx
                    val result = session.call {
                        val localSegs = diar.process(chunk.samples).toList()
                        // The first `overlapSec` of every window after the
                        // first is a replay of the previous window's tail,
                        // which that window already labelled. Keep only
                        // what reaches past the recap so the pooled segment
                        // list doesn't carry a duplicate of every seam.
                        val fresh = if (thisWindow == 0) localSegs else dropRecapZone(localSegs, recapSec) { it.end }
                        val byLocalId = fresh.groupBy { it.speaker }
                        WindowResult(
                            byLocalId = byLocalId,
                            embeddings = byLocalId.mapValues { (_, segs) ->
                                extractCanonicalEmbedding(extractor, chunk.samples, segs, sampleRate)
                            },
                        )
                    }
                    lastWindowWallMs = System.currentTimeMillis() - startedAt
                    for ((localId, segs) in result.byLocalId) {
                        for (s in segs) {
                            pending.add(PendingSegment(
                                windowIdx = windowIdx,
                                localId = localId,
                                start = (winStart + s.start).toFloat(),
                                end = (winStart + s.end).toFloat(),
                                confidence = s.confidence,
                            ))
                        }
                        val embedding = result.embeddings[localId]
                        if (embedding != null) {
                            clusters.add(LocalCluster(windowIdx, localId, embedding))
                        } else {
                            unmergeableIds[windowIdx to localId] = nextFallbackId--
                        }
                    }
                    windowIdx++
                    if (durationSec > 0) {
                        // Report the END of the window just finished — the
                        // start of it sat at "0%" for the whole first window.
                        onProgress?.invoke(((winStart + winLenSec) / durationSec).toFloat().coerceIn(0f, 0.99f))
                    } else {
                        onProgress?.invoke(0f)
                    }
                    Log.i(TAG, "  window $windowIdx @ ${"%.0f".format(winStart)}s → " +
                        "${result.byLocalId.size} local speakers (${clusters.size} embedded so far, " +
                        "${lastWindowWallMs / 1000}s wall for ${"%.0f".format(winLenSec)}s audio)")
                }
                // Idle here, so this releases right away; the finally below
                // is then a no-op.
                session.release()

                val labels = if (clusters.isNotEmpty()) {
                    SpeakerClustering.cluster(
                        embeddings = clusters.map { it.embedding },
                        numClusters = numClusters,
                        distanceThreshold = 1f - threshold,
                    )
                } else IntArray(0)
                val globalIdByKey = HashMap<Pair<Int, Int>, Int>()
                for ((i, c) in clusters.withIndex()) {
                    globalIdByKey[c.windowIdx to c.localId] = labels[i]
                }
                // Fallback (unmergeable) ids come after every real cluster
                // label so they don't collide; they were never going to be
                // merged with anything anyway.
                val labelCount = (labels.maxOrNull() ?: -1) + 1
                var nextAfterClusters = labelCount
                val fallbackRemap = HashMap<Int, Int>()
                for (negId in unmergeableIds.values.toSortedSet(compareByDescending { it })) {
                    fallbackRemap[negId] = nextAfterClusters++
                }

                val globals = pending.map { p ->
                    val key = p.windowIdx to p.localId
                    val g = globalIdByKey[key] ?: fallbackRemap[unmergeableIds[key]] ?: 0
                    SpeakerSegment(start = p.start, end = p.end, speakerId = g, confidence = p.confidence)
                }.sortedBy { it.start }

                // Auto mode only: collapse any remaining over-segmentation
                // (a genuinely spurious cluster the embedding pass didn't
                // merge, e.g. a two-word aside that got its own cluster).
                val consolidated = if (numClusters <= 0) consolidateSpeakers(globals) else globals
                val out = renumberByFirstAppearance(consolidated)
                val speakerCount = out.map { it.speakerId }.distinct().size
                Log.i(TAG, "chunked diarization complete: ${globals.size} raw segs → " +
                    "$speakerCount speakers across $windowIdx windows")
                if (numClusters <= 0 && speakerCount > OVER_SEGMENTATION_WARNING_THRESHOLD) {
                    onOverSegmented?.invoke()
                }
                Result.success(out)
            } finally {
                heartbeatJob?.cancel()
                session.release()
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            Log.i(TAG, "chunked diarization cancelled: ${ce.message}")
            throw ce
        } catch (t: Throwable) {
            Log.e(TAG, "chunked diarization failed", t)
            Result.failure(t)
        }
    }

    /**
     * Canonical embedding for one local speaker in one window: feed the
     * speaker's single longest segment (capped at [MAX_EMBEDDING_SECONDS])
     * through [extractor]. A longer, cleaner slice gives a more robust
     * vector than averaging many short ones, and matches the "longest
     * segment as canonical vector" approach noted for this exact problem
     * in NEXT_STEPS.md before this rewrite existed.
     *
     * Returns null if the extractor isn't ready on that slice (segment too
     * short for the model's minimum window) — callers give such speakers
     * their own un-mergeable id rather than dropping them.
     */
    private fun extractCanonicalEmbedding(
        extractor: SpeakerEmbeddingExtractor,
        windowSamples: FloatArray,
        localSegs: List<OfflineSpeakerDiarizationSegment>,
        sampleRate: Int,
    ): FloatArray? {
        val longest = localSegs.maxByOrNull { it.end - it.start } ?: return null
        val startIdx = (longest.start * sampleRate).toInt().coerceIn(0, windowSamples.size)
        val maxEndIdx = (startIdx + MAX_EMBEDDING_SECONDS * sampleRate).toInt().coerceAtMost(windowSamples.size)
        val endIdx = (longest.end * sampleRate).toInt().coerceIn(startIdx, maxEndIdx)
        if (endIdx - startIdx < sampleRate / 4) return null // under 250ms — not enough signal
        val slice = windowSamples.copyOfRange(startIdx, endIdx)
        val stream = extractor.createStream()
        return try {
            stream.acceptWaveform(slice, sampleRate)
            stream.inputFinished()
            if (!extractor.isReady(stream)) null else extractor.compute(stream)
        } catch (t: Throwable) {
            Log.w(TAG, "embedding extraction failed for a ${endIdx - startIdx} sample slice", t)
            null
        } finally {
            runCatching { stream.release() }
        }
    }

    /**
     * Collapse over-segmentation in AUTO clustering. CAM++ on Arabic /
     * Ukrainian / multilingual speech routinely fractures one voice into
     * many spurious speakers (observed: 43 "speakers" in a ~38-min
     * 2-3 person conversation). Those spurious speakers almost always
     * have tiny total talk-time — a few 1-2 second segments — while
     * the real speakers dominate.
     *
     * Heuristic: a speaker is "real" if its total duration is at least
     * [MIN_SPEAKER_SECONDS] AND at least [MINOR_SPEAKER_FRACTION] of the
     * largest speaker's total. Everything else is reassigned to the
     * temporally-nearest real speaker (the real speaker whose segment is
     * closest in time to the spurious one).
     *
     * No-op when there are ≤2 speakers (nothing to collapse) or no
     * speaker clears the "real" bar (degenerate — keep original rather
     * than merge everything into one).
     */
    private fun consolidateSpeakers(segs: List<SpeakerSegment>): List<SpeakerSegment> {
        if (segs.isEmpty()) return segs
        val totalBySpeaker = segs.groupBy { it.speakerId }
            .mapValues { (_, v) -> v.sumOf { (it.end - it.start).toDouble() } }
        if (totalBySpeaker.size <= 2) return segs
        val maxDur = totalBySpeaker.values.maxOrNull() ?: 0.0
        val floor = maxOf(MIN_SPEAKER_SECONDS, MINOR_SPEAKER_FRACTION * maxDur)
        val realSpeakers = totalBySpeaker.filter { it.value >= floor }.keys
        if (realSpeakers.isEmpty() || realSpeakers.size == totalBySpeaker.size) return segs

        // Sorted real-speaker segments for nearest-time lookup.
        val realSegs = segs.filter { it.speakerId in realSpeakers }.sortedBy { it.start }
        if (realSegs.isEmpty()) return segs
        fun nearestRealSpeaker(s: SpeakerSegment): Int {
            val mid = (s.start + s.end) / 2f
            var best = realSegs.first().speakerId
            var bestGap = Float.MAX_VALUE
            for (r in realSegs) {
                val gap = when {
                    mid < r.start -> r.start - mid
                    mid > r.end -> mid - r.end
                    else -> 0f
                }
                if (gap < bestGap) { bestGap = gap; best = r.speakerId }
            }
            return best
        }
        val reassigned = segs.map { s ->
            if (s.speakerId in realSpeakers) s
            else s.copy(speakerId = nearestRealSpeaker(s))
        }
        Log.i(TAG, "consolidateSpeakers: ${totalBySpeaker.size} → ${realSpeakers.size} " +
            "(floor=${"%.1f".format(floor)}s, dropped ${totalBySpeaker.size - realSpeakers.size} minor)")
        return reassigned
    }

    /**
     * Resolve the best installed embedding model. Order of preference:
     *   1. WeSpeaker ResNet221-LM (`embedding-wespeaker.onnx`, ~95 MB) —
     *      ~25–30% lower EER than CAM++, recommended for mis-attribution
     *      cases, including non-English audio.
     *   2. 3D-Speaker CAM++ multilingual zh+en (`embedding-multilingual.onnx`,
     *      ~28 MB) — same size as the compact model, modestly better on
     *      code-switched audio.
     *   3. 3D-Speaker CAM++ VoxCeleb English (`embedding.onnx`, ~28 MB) —
     *      compact baseline.
     *
     * The 2026-09 plan's catalog addition — WeSpeaker SimAM-ResNet34
     * trained on VoxBlink2, the best documented result on non-English
     * cross-lingual speech (2026 TidyVoice challenge) — turned out not to
     * exist as a published ONNX export anywhere: sherpa-onnx's
     * `speaker-recongition-models` release has no VoxBlink2/SimAM variant
     * (checked directly via `gh release view`), only the WeSpeaker
     * VoxCeleb/CN-Celeb ones already listed above. WeSpeaker publishes the
     * PyTorch checkpoint, but exporting + wiring a new architecture
     * through sherpa's fixed WeSpeaker loader is real native-adjacent work,
     * not a catalog entry — out of scope here. WeSpeaker ResNet221-LM
     * stays the recommended default per the plan's own fallback for this
     * exact case.
     *
     * Users can have multiple installed simultaneously; the highest-
     * preference one wins. Returning null means none are installed.
     */
    fun embeddingModelDisplayName(): String? =
        displayNameFor(embeddingModelFile()?.name)

    /** Short display name for an embedding-model filename. */
    fun displayNameFor(filename: String?): String? = when (filename) {
        "embedding-wespeaker.onnx" -> "WeSpeaker"
        "embedding-multilingual.onnx" -> "CAM++ multilingual"
        "embedding.onnx" -> "CAM++ English"
        else -> null
    }

    fun embeddingModelFile(): File? {
        val modelsDir = File(context.filesDir, "models")
        // User-picked preference wins if the file actually exists. If
        // the user installed e.g. WeSpeaker, then uninstalled it
        // without clearing the preference, the file is gone and we
        // fall through to the cascade.
        val pref = uiPrefs?.preferredEmbedding?.value
        if (!pref.isNullOrBlank()) {
            val f = File(modelsDir, pref)
            if (f.exists() && f.length() > 0L) return f
        }
        for (name in EMBEDDING_MODEL_CANDIDATES) {
            val f = File(modelsDir, name)
            if (f.exists() && f.length() > 0L) return f
        }
        return null
    }

    /**
     * Filenames of every embedding model currently installed on disk.
     * Used by the Settings UI to render a "pick which one is active"
     * radio selector — only files that exist show up, so the user
     * doesn't pick a model they haven't downloaded.
     */
    fun installedEmbeddingFilenames(): List<String> {
        val modelsDir = File(context.filesDir, "models")
        return EMBEDDING_MODEL_CANDIDATES.filter {
            val f = File(modelsDir, it)
            f.exists() && f.length() > 0L
        }
    }

    fun isEmbeddingModelPresent(): Boolean = embeddingModelFile() != null

    /** Copy assets/segmentation.onnx to filesDir/diar/segmentation.onnx once. */
    private fun ensureSegmentationModelOnDisk(): File {
        val dir = File(context.filesDir, "diar").apply { mkdirs() }
        val out = File(dir, "segmentation.onnx")
        if (!out.exists() || out.length() == 0L) {
            context.assets.open("segmentation.onnx").use { input ->
                FileOutputStream(out).use { input.copyTo(it) }
            }
            Log.i(TAG, "extracted segmentation.onnx to ${out.absolutePath} (${out.length()} bytes)")
        }
        return out
    }

    private fun missingEmbeddingException(): Throwable = IllegalStateException(
        "Speaker embedding model not installed. Open Settings → Download 'Speaker embedding'."
    )

    companion object {
        private const val TAG = "DiarizationRunner"

        /**
         * ONNX Runtime intra-op threads for the segmentation and embedding
         * sessions. Was hard-coded to 2; the embedding pass dominates wall
         * time (WeSpeaker ResNet221 runs once per segmentation window per
         * speaker, hundreds of times per 5-minute window) and scales with
         * threads on the 8-core phones this runs on. Capped at 4: the two
         * sessions run back to back, and beyond the big cores the little
         * ones only add contention.
         */
        internal fun diarizationThreads(cores: Int = Runtime.getRuntime().availableProcessors()): Int =
            cores.coerceIn(2, 4)

        /**
         * Drop segments that lie entirely inside the first [recapSec] of a
         * window — audio the previous window already covered. Segments
         * that start in the recap but reach past it are kept: the previous
         * window saw them truncated at its own end.
         */
        internal fun <T> dropRecapZone(segs: List<T>, recapSec: Double, end: (T) -> Float): List<T> =
            if (recapSec <= 0.0) segs else segs.filter { end(it).toDouble() > recapSec }

        private val EMBEDDING_MODEL_CANDIDATES = listOf(
            "embedding-wespeaker.onnx",
            "embedding-multilingual.onnx",
            "embedding.onnx",
        )

        /**
         * Cosine-similarity threshold for FastClustering. Higher = merge
         * more aggressively (fewer speakers); lower = split more (more
         * speakers). sherpa's stock default is 0.5, tuned for English
         * VoxCeleb. On Arabic / Ukrainian / multilingual speech, intra-
         * speaker embedding variance exceeds inter-speaker at 0.5, so one
         * voice fractures into many spurious speakers. 0.7 merges those
         * back without collapsing genuinely distinct voices in testing.
         *
         * User-overridable in Settings; see [defaultClusterThreshold] for
         * the language-aware default when the user hasn't set one.
         */
        const val DEFAULT_CLUSTER_THRESHOLD = 0.7f
        const val DEFAULT_MIN_DURATION_ON = 0.2f
        const val DEFAULT_MIN_DURATION_OFF = 0.5f

        /** Global speaker count above which we surface an "over-segmented?" hint in auto mode. */
        const val OVER_SEGMENTATION_WARNING_THRESHOLD = 6

        /** Cap on how much of a speaker's longest segment we feed the embedding extractor. */
        private const val MAX_EMBEDDING_SECONDS = 8.0

        /**
         * Language-aware default threshold: 0.5 (sherpa's stock English-
         * tuned default) when the selected language set is English-only,
         * 0.7 everywhere else — auto/empty, multilingual, or any other
         * single language. Only used when the user hasn't set an explicit
         * override in Settings.
         */
        fun defaultClusterThreshold(languages: List<String>): Float =
            if (languages.map { it.lowercase() } == listOf("en")) 0.5f else DEFAULT_CLUSTER_THRESHOLD

        /**
         * Renumber speaker ids to 0..K-1 in order of first appearance —
         * clustering-internal ids are otherwise meaningless labels that can
         * come out in any order. Applied unconditionally at the end of both
         * [run] and [runChunked] so downstream numbering ("SPEAKER_00",
         * "SPEAKER_01"...) is deterministic for the same audio regardless
         * of which path produced it.
         */
        fun renumberByFirstAppearance(segs: List<SpeakerSegment>): List<SpeakerSegment> {
            if (segs.isEmpty()) return segs
            val order = LinkedHashMap<Int, Int>()
            for (s in segs.sortedBy { it.start }) {
                if (s.speakerId !in order) order[s.speakerId] = order.size
            }
            return segs.map { it.copy(speakerId = order[it.speakerId] ?: 0) }
        }

        /**
         * A speaker must talk at least this many seconds total to survive
         * auto-mode consolidation. Below this it's treated as spurious
         * over-segmentation and merged into the nearest real speaker.
         */
        private const val MIN_SPEAKER_SECONDS = 6.0

        /**
         * …and at least this fraction of the most-talkative speaker's
         * total. Guards against keeping a dozen speakers who each clear
         * the absolute floor but are tiny relative to the real
         * participants.
         */
        private const val MINOR_SPEAKER_FRACTION = 0.08
    }
}

/**
 * Progress interpolation for [DiarizationRunner.runChunked]. Sherpa exposes
 * no progress inside a window, so between per-window updates the bar
 * advances on the assumption that this window takes as long as the last
 * one (1x realtime before the first one has finished), never past 95% of
 * the window until it really completes.
 */
internal class WindowHeartbeat(private val durationSec: Double) {
    @Volatile private var winStartSec = 0.0
    @Volatile private var winLenSec = 0.0
    @Volatile private var startedAtMs = 0L
    @Volatile private var expectedWallMs = 0L

    fun beginWindow(startSec: Double, lenSec: Double, previousWallMs: Long, nowMs: Long = System.currentTimeMillis()) {
        winStartSec = startSec
        winLenSec = lenSec
        startedAtMs = nowMs
        expectedWallMs = if (previousWallMs > 0) previousWallMs else (lenSec * 1000).toLong()
    }

    fun fraction(nowMs: Long): Float {
        if (durationSec <= 0.0) return 0f
        val within = if (expectedWallMs > 0) {
            ((nowMs - startedAtMs).toDouble() / expectedWallMs).coerceIn(0.0, 0.95)
        } else 0.0
        return ((winStartSec + within * winLenSec) / durationSec).toFloat().coerceIn(0f, 0.99f)
    }
}

/**
 * Assign speaker labels to transcription segments by max temporal overlap with
 * the diarization output. Mirrors the Mac app's `_assign_speakers_segment_level`
 * heuristic in `transcriber/pipeline.py` — keep them in sync if either changes.
 */
fun assignSpeakers(
    transcript: List<RawSegment>,
    speakers: List<DiarizationRunner.SpeakerSegment>,
): List<Pair<RawSegment, Int?>> {
    if (speakers.isEmpty()) return transcript.map { it to null }
    return transcript.map { seg ->
        var bestOverlap = 0.0
        var bestSpeaker: Int? = null
        for (sp in speakers) {
            val overlap = (minOf(seg.endSeconds, sp.end.toDouble()) -
                maxOf(seg.startSeconds, sp.start.toDouble())).coerceAtLeast(0.0)
            if (overlap > bestOverlap) {
                bestOverlap = overlap
                bestSpeaker = sp.speakerId
            }
        }
        seg to bestSpeaker
    }
}

/**
 * Common short interjections that real speakers commonly utter inside
 * another speaker's turn ("yeah", "mhm", "right"). When a tiny segment
 * matching one of these is sandwiched between two segments of the SAME
 * other speaker, it almost always belongs to that other speaker — the
 * sherpa/pyannote clusterer just mis-attributed a short voiced
 * exhalation. Reassign such segments to the surrounding speaker.
 *
 * Multi-language list: Arabic / Ukrainian / Dutch entries cover our
 * built-in language presets (the app targets EN + AR + UK + NL). The
 * matcher case-insensitives + strips trailing punctuation before
 * compare, so "yeah." and "Mhm!" both hit.
 */
private val BACKCHANNEL_WORDS = setOf(
    // English
    "yeah", "yep", "yup", "yes", "mhm", "mm", "mmm", "hm", "uh-huh", "uhuh",
    "okay", "ok", "right", "sure", "totally", "exactly", "got it", "true",
    "no", "nope", "nah", "alright", "k", "uh", "huh",
    // Arabic (Gulf/MSA mix)
    "ايوه", "أيوه", "تمام", "اوكي", "أوكي", "صح", "ايه", "إيه", "أيوا",
    // Ukrainian
    "так", "ага", "угу", "добре", "ясно", "зрозуміло",
    // Dutch
    "ja", "oké", "klopt", "juist", "inderdaad",
)

private const val BACKCHANNEL_MAX_DURATION_SEC = 2.0

/**
 * Post-process speaker assignments to merge short isolated backchannel
 * interjections into the surrounding speaker's turn. Mirrors the Mac
 * app's coalesce step (`pipeline.py` `_merge_backchannels`).
 *
 * A segment is merged if all three hold:
 *   1. Its duration is < [BACKCHANNEL_MAX_DURATION_SEC]
 *   2. Its text (trimmed + lower-cased + de-punctuated) matches a
 *      known backchannel word
 *   3. The previous AND next segments belong to the SAME different
 *      speaker (sandwich pattern)
 *
 * Edge segments (first / last) are never merged — we can't sandwich-
 * detect without two neighbors. Cheap: O(n) single pass, no allocs
 * beyond the result list.
 */
fun coalesceBackchannels(
    assigned: List<Pair<RawSegment, Int?>>,
): List<Pair<RawSegment, Int?>> {
    if (assigned.size < 3) return assigned
    val result = assigned.toMutableList()
    for (i in 1 until result.size - 1) {
        val (seg, currentSpeaker) = result[i]
        val prevSpeaker = result[i - 1].second
        val nextSpeaker = result[i + 1].second
        val duration = seg.endSeconds - seg.startSeconds
        if (duration >= BACKCHANNEL_MAX_DURATION_SEC) continue
        if (prevSpeaker == null || prevSpeaker != nextSpeaker) continue
        if (prevSpeaker == currentSpeaker) continue
        val cleaned = seg.text.trim().lowercase()
            .trimEnd('.', '!', '?', ',', ';', ':', '·')
            .trim()
        if (cleaned !in BACKCHANNEL_WORDS) continue
        // Reassign this segment to the surrounding speaker. Keeps the
        // text/timing intact so the transcript reads naturally — only
        // the speaker label changes.
        result[i] = seg to prevSpeaker
    }
    return result.toList()
}

/** Pure-filler words that carry no content on their own (a breath that tripped VAD). One list, shared with [TextDestutter]. */
private val FILLER_ONLY_WORDS: Set<String> get() = TextDestutter.FILLERS
private val FILLER_TOKEN_SPLIT = Regex("[^\\p{L}]+")

/**
 * Drop segments whose text is nothing but filler words (and no digits —
 * "1, 2, 3" isn't filler even if short). Ported from the Mac app's
 * pre-coalesce cleanup in `TranscriptionRunner.swift`: these exist only
 * because a breath or mouth click tripped VAD, and coalescing them in
 * would otherwise stitch two real turns together with a meaningless
 * word in between.
 */
fun dropPureFillerSegments(
    assigned: List<Pair<RawSegment, Int?>>,
): List<Pair<RawSegment, Int?>> = assigned.filterNot { (seg, _) ->
    val hasDigits = seg.text.any { it.isDigit() }
    if (hasDigits) return@filterNot false
    val tokens = seg.text.lowercase().split(FILLER_TOKEN_SPLIT).filter { it.isNotEmpty() }
    tokens.isEmpty() || tokens.all { it in FILLER_ONLY_WORDS }
}

/**
 * Merge adjacent same-speaker segments into one continuous turn — the
 * chunk/window boundaries used during transcription are an implementation
 * detail, not conversational structure. Ports `Transcriberr/ASR/
 * TranscriptionRunner.swift`'s `coalesceBySpeaker` verbatim (default gap
 * 30s — "smooth blocks"; Settings can tune it down to ~2s for "fine
 * Samsung-style turns"). Run [dropPureFillerSegments] first, same as the
 * Mac pipeline, so a stray "um" between two turns doesn't itself become
 * the coalescing anchor.
 *
 * @param gapSec speakers separated by less than this many seconds of
 *   silence merge into one segment.
 */
fun coalesceTurns(
    assigned: List<Pair<RawSegment, Int?>>,
    gapSec: Double = DEFAULT_TURN_COALESCE_GAP_SEC,
): List<Pair<RawSegment, Int?>> {
    val sorted = assigned.sortedBy { it.first.startSeconds }
    val out = mutableListOf<Pair<RawSegment, Int?>>()
    for ((seg, speakerId) in sorted) {
        val last = out.lastOrNull()
        if (last != null && last.second == speakerId && seg.startSeconds - last.first.endSeconds < gapSec) {
            val merged = last.first.copy(
                endSeconds = maxOf(last.first.endSeconds, seg.endSeconds),
                text = last.first.text + " " + seg.text,
            )
            out[out.size - 1] = merged to speakerId
        } else {
            out.add(seg to speakerId)
        }
    }
    return out
}

/** Default turn-coalescing gap, ported from the Mac app's `ui.turnCoalesceGapSeconds` default. */
const val DEFAULT_TURN_COALESCE_GAP_SEC = 30.0
