package nl.ihnatov.transcriber.asr

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationSegment
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
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
 *   - embedding.onnx (3D-Speaker CAM++ VoxCeleb) — downloaded via Settings, ~28 MB.
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

    init {
        // sherpa-onnx 8.5.1's bundled `com.bihe0832.android:lib-onnx`
        // ships its own libonnxruntime.so that exports `OrtGetApiBase`
        // exactly the way libsherpa-onnx-jni.so expects. No explicit
        // preload needed — sherpa's class-init does the right thing on
        // its own when we don't substitute the ORT lib. Previous
        // commits attempted preloads (System.loadLibrary("onnxruntime")
        // and OrtEnvironment.getEnvironment()) to compensate for the
        // Microsoft-ORT substitution; both are removed now that we're
        // back on the bundled lib-onnx (see build.gradle.kts).
    }

    data class SpeakerSegment(val start: Float, val end: Float, val speakerId: Int)

    /**
     * Run diarization on a 16 kHz mono float buffer. Returns a list of
     * (start, end, speaker_id) tuples, or fails with [missingEmbedding] etc.
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
        onProgress: ((fraction: Float) -> Unit)? = null,
    ): Result<List<SpeakerSegment>> = withContext(Dispatchers.Default) {
        val embeddingFile = embeddingModelFile()
            ?: return@withContext Result.failure(missingEmbeddingException())
        Log.i(TAG, "diarization: embedding model = " +
            "${embeddingModelDisplayName() ?: "unknown"} (${embeddingFile.name}, " +
            "${embeddingFile.length() / 1024 / 1024} MB)")
        val segFile = ensureSegmentationModelOnDisk()

        try {
            val config = OfflineSpeakerDiarizationConfig(
                segmentation = OfflineSpeakerSegmentationModelConfig(
                    pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(segFile.absolutePath),
                    numThreads = 2,
                ),
                embedding = SpeakerEmbeddingExtractorConfig(
                    model = embeddingFile.absolutePath,
                    numThreads = 2,
                ),
                clustering = FastClusteringConfig(numClusters = numClusters, threshold = threshold),
                minDurationOn = 0.2f,
                minDurationOff = 0.5f,
            )
            val diar = OfflineSpeakerDiarization(assetManager = null, config = config)
            // Cancellation watcher. Sherpa's blocking native `process(samples)`
            // doesn't respond to Kotlin coroutine cancellation — the call
            // sits on the JNI thread for minutes. Instead of routing
            // through `processWithCallback` (which crashes with a JNI
            // method-lookup error on capturing Kotlin lambdas — sherpa-onnx
            // 8.5.1's binding to `Function3.invoke(IIJ)Integer;` is broken
            // for synthetic lambdas that R8/D8 produces), we run a sibling
            // coroutine that calls `diar.release()` the moment the parent
            // coroutine is cancelled. The native processing then errors
            // out instead of hanging.
            val cancelWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    runCatching { diar.release() }
                }
            }
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
                    diar.process(samples)
                runCatching { diar.release() }
                val mapped = segs.map {
                    SpeakerSegment(start = it.start, end = it.end, speakerId = it.speaker)
                }
                // Auto mode only: collapse over-segmentation. Forced-count
                // mode (numClusters>0) already controls the speaker count.
                val out = if (numClusters <= 0) consolidateSpeakers(mapped) else mapped
                Result.success(out)
            } finally {
                progressTimer?.cancel()
                cancelWatcher.cancel()
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
     * diarizes each window independently, and stitches each window's LOCAL
     * speaker ids into globally-consistent ids by matching who speaks in the
     * overlap zone shared with the previous window.
     *
     * Why this exists: sherpa's `process()` needs the WHOLE audio buffer in
     * heap at once (its API takes a FloatArray, not a stream). A 1-hour file
     * is ~220 MB raw + codec overhead, which blows the heap budget — so the
     * caller used to skip diarization entirely on long files. Here memory is
     * bounded by one window (~22 MB) regardless of total length.
     *
     * Stitching is embedding-free on purpose: it uses only the already-
     * working `process()` JNI. Sherpa's standalone SpeakerEmbeddingExtractor
     * would give cleaner global matching but adds two more JNI-bound classes
     * to vendor (and the processWithCallback crash showed how fragile that
     * binding is). Temporal-overlap stitching is robust as long as each
     * speaker says SOMETHING in the 45-sec overlap zone; a speaker silent
     * for a full overlap window gets a duplicate id (rare, acceptable —
     * mergeable later by a manual rename).
     *
     * @param windowSec content seconds per window (excludes overlap).
     * @param overlapSec lookback shared with the previous window, for
     *                   speaker-id stitching.
     */
    suspend fun runChunked(
        file: File,
        windowSec: Double = 300.0,
        overlapSec: Double = 45.0,
        /**
         * -1 = auto-cluster per window via [threshold]; >0 = force this
         * many speakers per window. Forcing is the deterministic fix for
         * the over-segmentation we see on Arabic / multilingual audio
         * (CAM++ splits one voice into many at the auto threshold). Only
         * pass a forced count when the user actually knows it — a window
         * containing fewer than N speakers will be over-split if forced.
         */
        numClusters: Int = -1,
        threshold: Float = DEFAULT_CLUSTER_THRESHOLD,
        durationSec: Double = 0.0,
        onProgress: ((fraction: Float) -> Unit)? = null,
    ): Result<List<SpeakerSegment>> = withContext(Dispatchers.Default) {
        val embeddingFile = embeddingModelFile()
            ?: return@withContext Result.failure(missingEmbeddingException())
        Log.i(TAG, "chunked diarization: model=${embeddingModelDisplayName() ?: "?"} " +
            "window=${windowSec}s overlap=${overlapSec}s threshold=$threshold " +
            "clusters=${if (numClusters > 0) numClusters.toString() else "auto"}")
        val segFile = ensureSegmentationModelOnDisk()
        val windowSamples = (windowSec * nl.ihnatov.transcriber.audio.AudioDecoder.TARGET_SR).toInt()
        val overlapSamples = (overlapSec * nl.ihnatov.transcriber.audio.AudioDecoder.TARGET_SR).toInt()

        try {
            val config = OfflineSpeakerDiarizationConfig(
                segmentation = OfflineSpeakerSegmentationModelConfig(
                    pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(segFile.absolutePath),
                    numThreads = 2,
                ),
                embedding = SpeakerEmbeddingExtractorConfig(
                    model = embeddingFile.absolutePath,
                    numThreads = 2,
                ),
                // Auto (numClusters=-1) lets the global speaker count emerge
                // from cross-window stitching; a forced count (numClusters>0)
                // pins N speakers per window — the deterministic fix for
                // over-segmentation when the user knows the speaker count.
                clustering = FastClusteringConfig(numClusters = numClusters, threshold = threshold),
                minDurationOn = 0.2f,
                minDurationOff = 0.5f,
            )
            val diar = OfflineSpeakerDiarization(assetManager = null, config = config)
            val cancelWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { runCatching { diar.release() } }
            }
            try {
                val globals = mutableListOf<SpeakerSegment>()
                var nextGlobalId = 0
                var windowIdx = 0
                nl.ihnatov.transcriber.audio.AudioDecoder.decodeChunked(
                    file,
                    chunkSamples = windowSamples,
                    overlapSamples = overlapSamples,
                ).collect { chunk ->
                    val winStart = chunk.startSeconds
                    val ov = chunk.overlapSeconds          // 0 for the first window
                    val newContentStart = winStart + ov    // emit only segments at/after this
                    val local = diar.process(chunk.samples)
                        .map { SpeakerSegment(
                            start = (winStart + it.start).toFloat(),
                            end = (winStart + it.end).toFloat(),
                            speakerId = it.speaker,
                        ) }
                    if (windowIdx == 0 || ov <= 0.0) {
                        // First window: local ids become global ids directly.
                        val remap = HashMap<Int, Int>()
                        for (s in local) {
                            val g = remap.getOrPut(s.speakerId) { nextGlobalId++ }
                            globals.add(s.copy(speakerId = g))
                        }
                    } else {
                        val mapping = stitchLocalToGlobal(
                            local = local,
                            globals = globals,
                            overlapStart = winStart.toFloat(),
                            overlapEnd = newContentStart.toFloat(),
                            allocNewId = { nextGlobalId++ },
                        )
                        // Emit only NEW-content segments (the overlap zone was
                        // already emitted by the previous window).
                        for (s in local) {
                            if (s.start >= newContentStart.toFloat() - 0.001f) {
                                val g = mapping[s.speakerId] ?: nextGlobalId++
                                globals.add(s.copy(speakerId = g))
                            }
                        }
                    }
                    windowIdx++
                    if (durationSec > 0) {
                        onProgress?.invoke((chunk.startSeconds / durationSec).toFloat().coerceIn(0f, 1f))
                    } else {
                        onProgress?.invoke(0f)
                    }
                    Log.i(TAG, "  window $windowIdx @ ${"%.0f".format(winStart)}s → " +
                        "${local.size} local segs, $nextGlobalId global speakers so far")
                }
                runCatching { diar.release() }
                val sorted = globals.sortedBy { it.start }
                // Auto mode only: collapse over-segmentation (the windowed
                // path is especially prone to it — every window can spawn
                // spurious speakers that the overlap stitch can't merge).
                val out = if (numClusters <= 0) consolidateSpeakers(sorted) else sorted
                Log.i(TAG, "chunked diarization complete: ${globals.size} raw → " +
                    "${out.map { it.speakerId }.distinct().size} speakers " +
                    "(was $nextGlobalId) across $windowIdx windows")
                Result.success(out)
            } finally {
                cancelWatcher.cancel()
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
     * closest in time to the spurious one). Speaker ids are then
     * renumbered 0..K-1 in first-appearance order.
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
        // Renumber to contiguous 0..K-1 in first-appearance order.
        val order = LinkedHashMap<Int, Int>()
        for (s in reassigned.sortedBy { it.start }) {
            if (s.speakerId !in order) order[s.speakerId] = order.size
        }
        val result = reassigned.map { it.copy(speakerId = order[it.speakerId] ?: 0) }
        Log.i(TAG, "consolidateSpeakers: ${totalBySpeaker.size} → ${order.size} " +
            "(floor=${"%.1f".format(floor)}s, dropped ${totalBySpeaker.size - realSpeakers.size} minor)")
        return result
    }

    /**
     * Map a window's local speaker ids to global ids by temporal overlap in
     * the shared zone [overlapStart, overlapEnd). For each local speaker we
     * find the previously-emitted global speaker it overlaps most with in
     * that zone and bind them; ties and double-binds are resolved greedily
     * (highest-overlap pair first). Local speakers with no meaningful
     * overlap-zone presence get a fresh global id from [allocNewId].
     */
    private fun stitchLocalToGlobal(
        local: List<SpeakerSegment>,
        globals: List<SpeakerSegment>,
        overlapStart: Float,
        overlapEnd: Float,
        allocNewId: () -> Int,
    ): Map<Int, Int> {
        fun overlapDur(s1: Float, e1: Float, s2: Float, e2: Float): Float =
            (minOf(e1, e2) - maxOf(s1, s2)).coerceAtLeast(0f)

        // Per (localId, globalId) total overlapping duration inside the zone.
        val pair = HashMap<Pair<Int, Int>, Float>()
        val localIds = local.map { it.speakerId }.toMutableSet()
        for (ls in local) {
            val lsS = maxOf(ls.start, overlapStart); val lsE = minOf(ls.end, overlapEnd)
            if (lsE <= lsS) continue
            for (gs in globals) {
                if (gs.end <= overlapStart || gs.start >= overlapEnd) continue
                val d = overlapDur(lsS, lsE, gs.start, gs.end)
                if (d > 0f) {
                    val k = ls.speakerId to gs.speakerId
                    pair[k] = (pair[k] ?: 0f) + d
                }
            }
        }
        // Greedy: bind highest-overlap pairs first, one global per local.
        val result = HashMap<Int, Int>()
        val usedGlobals = HashSet<Int>()
        val sorted = pair.entries.sortedByDescending { it.value }
        val MIN_OVERLAP = 0.3f
        for (e in sorted) {
            val (lId, gId) = e.key
            if (e.value < MIN_OVERLAP) break
            if (result.containsKey(lId) || gId in usedGlobals) continue
            result[lId] = gId
            usedGlobals.add(gId)
            localIds.remove(lId)
        }
        // Any local speaker not matched in the zone is genuinely new.
        for (lId in localIds) result[lId] = allocNewId()
        return result
    }

    /**
     * Resolve the best installed embedding model. Order of preference:
     *   1. WeSpeaker ResNet221-LM (`embedding-wespeaker.onnx`, ~95 MB) —
     *      ~25–30% lower EER than CAM++, recommended for mis-attribution
     *      cases.
     *   2. 3D-Speaker CAM++ multilingual zh+en (`embedding-multilingual.onnx`,
     *      ~28 MB) — same size as the compact model, modestly better on
     *      code-switched audio.
     *   3. 3D-Speaker CAM++ VoxCeleb English (`embedding.onnx`, ~28 MB) —
     *      compact baseline.
     *
     * Users can have multiple installed simultaneously; the highest-
     * preference one wins. Returning null means none are installed.
     */
    /**
     * Friendly display name for the currently-active embedding model, or
     * null if none is installed. UI shows this on the HYBRID row of the
     * RUN options sheet so the user can see which embedding is in use
     * (priority cascade: WeSpeaker > multilingual > compact-English).
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
        val candidates = listOf(
            "embedding-wespeaker.onnx",
            "embedding-multilingual.onnx",
            "embedding.onnx",
        )
        for (name in candidates) {
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
        val candidates = listOf(
            "embedding-wespeaker.onnx",
            "embedding-multilingual.onnx",
            "embedding.onnx",
        )
        return candidates.filter {
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
         * Cosine-similarity threshold for FastClustering. Higher = merge
         * more aggressively (fewer speakers); lower = split more (more
         * speakers). sherpa's stock default is 0.5, tuned for English
         * VoxCeleb. On Arabic / Ukrainian / multilingual speech, intra-
         * speaker embedding variance exceeds inter-speaker at 0.5, so one
         * voice fractures into many (observed: 16 speakers in a 5-min
         * window of a 2-3 person conversation). 0.7 merges those back
         * without collapsing genuinely distinct voices in testing. Tune
         * here if a clean English meeting starts under-segmenting.
         */
        const val DEFAULT_CLUSTER_THRESHOLD = 0.7f

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
