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
        threshold: Float = 0.5f,
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
                Result.success(
                    segs.map { SpeakerSegment(start = it.start, end = it.end, speakerId = it.speaker) }
                )
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
