package nl.ihnatov.transcriber.asr

import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineOmnilingualAsrCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * ASR backend over sherpa-onnx's [OfflineRecognizer] — covers [AsrBackendKind.Parakeet]
 * (offline transducer) and [AsrBackendKind.Omnilingual] (offline CTC). Both are
 * "directory" models: [modelPath] passed to [load] is a directory containing
 * `tokens.txt` plus either an encoder/decoder/joiner .onnx trio (transducer)
 * or a single model .onnx (CTC) — see [AsrFactory.resolveModel] for how that
 * directory gets there. File-matching is prefix-based rather than an exact
 * name so it doesn't break if a future catalog bump changes a quantization
 * suffix (`encoder.onnx` vs `encoder.int8.onnx`).
 *
 * Chunking: sherpa's offline recognizers decode one [OfflineRecognizer.createStream]
 * per call, same shape as whisper.cpp's single-buffer API — but unlike
 * whisper.cpp, transducer/CTC encoders aren't meant to eat an hour of audio
 * in one utterance (quality degrades badly well before any crash). This
 * backend chunks internally at silence-aligned ~30-45s boundaries using a
 * compact in-memory RMS scan (same shape as `AudioDecoder.scanSilences` /
 * `computeCutPoints`, but over the FloatArray already in hand rather than
 * re-decoding from a File) instead of taking an external File dependency.
 */
class SherpaOfflineBackend(private val kind: AsrBackendKind) : AsrBackend {

    init {
        require(kind == AsrBackendKind.Parakeet || kind == AsrBackendKind.Omnilingual) {
            "SherpaOfflineBackend only supports Parakeet/Omnilingual, got $kind"
        }
    }

    private val mutex = Mutex()
    private var recognizer: OfflineRecognizer? = null

    override val id: String get() = "sherpa-onnx / ${kind.name}"
    override val isReady: Boolean get() = recognizer != null

    override suspend fun load(modelPath: String): Result<Unit> = withContext(Dispatchers.Default) {
        mutex.withLock {
            try {
                runCatching { recognizer?.release() }
                recognizer = null
                val dir = File(modelPath)
                if (!dir.isDirectory) {
                    return@withContext Result.failure(
                        IllegalStateException("Expected a model directory, got: $modelPath")
                    )
                }
                val modelConfig = when (kind) {
                    AsrBackendKind.Parakeet -> {
                        val files = findTransducerFiles(dir)
                        OfflineModelConfig(
                            transducer = OfflineTransducerModelConfig(
                                encoder = files.encoder.absolutePath,
                                decoder = files.decoder.absolutePath,
                                joiner = files.joiner.absolutePath,
                            ),
                            tokens = files.tokens.absolutePath,
                            numThreads = 2,
                            provider = "cpu",
                        )
                    }
                    AsrBackendKind.Omnilingual -> {
                        val tokens = dir.listFiles()?.firstOrNull { it.name == "tokens.txt" }
                            ?: error("tokens.txt not found in $modelPath")
                        val model = findByPrefix(dir, "model")
                            ?: error("Omnilingual model .onnx not found in $modelPath")
                        OfflineModelConfig(
                            omnilingual = OfflineOmnilingualAsrCtcModelConfig(model = model.absolutePath),
                            tokens = tokens.absolutePath,
                            numThreads = 2,
                            provider = "cpu",
                        )
                    }
                    else -> error("unreachable — checked in init")
                }
                val config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80, dither = 0f),
                    modelConfig = modelConfig,
                )
                recognizer = OfflineRecognizer(null, config)
                Result.success(Unit)
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                Log.e(TAG, "load failed for $kind at $modelPath", t)
                Result.failure(t)
            }
        }
    }

    /**
     * [language] and [translate] are accepted but not applied: Parakeet and
     * Omnilingual are transcription-only (no built-in translate mode like
     * Whisper's, no runtime language-forcing option exposed on
     * [com.k2fsa.sherpa.onnx.OfflineStream] the way the online/streaming
     * API has `setOption("language", ...)`). A `translate=true` request
     * silently falls back to source-language transcription — same
     * established behavior as [WhisperCppBackend] for unsupported target
     * languages, not a new inconsistency.
     */
    override suspend fun transcribe(
        samples: FloatArray,
        sampleRate: Int,
        language: String?,
        translate: Boolean,
        progress: ((Float) -> Unit)?,
    ): Result<List<RawSegment>> = withContext(Dispatchers.Default) {
        mutex.withLock {
            val r = recognizer ?: return@withContext Result.failure(IllegalStateException("$kind not loaded"))
            try {
                val cuts = computeInMemoryCutPoints(samples, sampleRate)
                val segments = mutableListOf<RawSegment>()
                for ((i, cut) in cuts.withIndex()) {
                    val slice = samples.copyOfRange(cut.startSample, cut.endSample)
                    val stream = r.createStream()
                    try {
                        stream.acceptWaveform(slice, sampleRate)
                        r.decode(stream)
                        val result = r.getResult(stream)
                        val offsetSec = cut.startSample.toDouble() / sampleRate
                        val words = reconstructWords(result.tokens, result.timestamps, result.durations, offsetSec)
                        val text = result.text.trim()
                        if (text.isNotEmpty()) {
                            segments.add(RawSegment(
                                startSeconds = offsetSec,
                                endSeconds = cut.endSample.toDouble() / sampleRate,
                                text = text,
                                words = words.ifEmpty { null },
                            ))
                        }
                    } finally {
                        runCatching { stream.release() }
                    }
                    progress?.invoke((i + 1).toFloat() / cuts.size)
                }
                Result.success(segments)
            } catch (t: Throwable) {
                Log.e(TAG, "$kind transcribe failed", t)
                Result.failure(t)
            }
        }
    }

    override suspend fun release() {
        mutex.withLock {
            runCatching { recognizer?.release() }
            recognizer = null
        }
    }

    /**
     * Word reconstruction from subword tokens. The models' SentencePiece
     * vocabularies mark a word start with a leading `▁`, but sherpa-onnx
     * hands result tokens back with that marker already turned into a
     * plain space (verified on-device with Parakeet TDT v3: matching only
     * `▁` merged every segment into one giant "word", which quietly
     * reduced per-word speaker attribution and the Super-mode word vote
     * to segment granularity). Accept either spelling.
     */
    internal fun reconstructWords(
        tokens: Array<String>,
        timestamps: FloatArray,
        durations: FloatArray,
        offsetSec: Double,
    ): List<Word> {
        if (tokens.isEmpty()) return emptyList()
        val words = mutableListOf<Word>()
        var buf = StringBuilder()
        var wordStart = 0.0
        var wordEnd = 0.0
        fun flush() {
            val text = buf.toString()
            if (text.isNotEmpty()) words.add(Word(wordStart, wordEnd, text))
            buf = StringBuilder()
        }
        for (i in tokens.indices) {
            val raw = tokens[i]
            val isNewWord = i == 0 || raw.startsWith(SENTENCEPIECE_WORD_MARKER) || raw.startsWith(" ")
            val piece = raw.removePrefix(SENTENCEPIECE_WORD_MARKER).trim()
            if (piece.isEmpty()) {
                // A bare marker token: the NEXT piece starts a new word.
                if (isNewWord && buf.isNotEmpty()) flush()
                continue
            }
            val start = offsetSec + (timestamps.getOrElse(i) { 0f }).toDouble()
            val dur = durations.getOrElse(i) { 0.08f }.toDouble()
            if (isNewWord && buf.isNotEmpty()) flush()
            if (buf.isEmpty()) wordStart = start
            buf.append(piece)
            wordEnd = start + dur
        }
        flush()
        return words
    }

    companion object {
        private const val TAG = "SherpaOfflineBackend"
        private const val SAMPLE_RATE = 16_000
        private const val SENTENCEPIECE_WORD_MARKER = "▁" // '▁'

        /** Not private: [LiveTranscriber] reuses this for NemotronStream, which shares
         *  Parakeet's encoder/decoder/joiner directory shape (see [AsrFactory.isCompleteModelDir]). */
        fun findByPrefix(dir: File, prefix: String): File? =
            dir.listFiles()?.firstOrNull { it.isFile && it.name.startsWith(prefix) && it.extension == "onnx" }

        /** The 4 files a transducer-shaped sherpa-onnx model directory needs. */
        internal data class TransducerFiles(
            val tokens: File,
            val encoder: File,
            val decoder: File,
            val joiner: File,
        )

        /**
         * Resolves [TransducerFiles] from [dir], or throws [IllegalStateException]
         * naming every missing file in one combined message. Shared between this
         * class's own Parakeet path (above) and [LiveTranscriber]'s Nemotron path
         * — same directory shape (encoder/decoder/joiner + tokens.txt), see
         * [AsrFactory.isCompleteModelDir] — so a future change to that shape (a
         * new required file, a naming exception) only needs updating here.
         */
        internal fun findTransducerFiles(dir: File): TransducerFiles {
            val tokens = dir.listFiles()?.firstOrNull { it.name == "tokens.txt" }
            val encoder = findByPrefix(dir, "encoder")
            val decoder = findByPrefix(dir, "decoder")
            val joiner = findByPrefix(dir, "joiner")
            if (tokens == null || encoder == null || decoder == null || joiner == null) {
                error(
                    "Model incomplete in ${dir.absolutePath} (tokens=${tokens?.name}, " +
                        "encoder=${encoder?.name}, decoder=${decoder?.name}, joiner=${joiner?.name})"
                )
            }
            return TransducerFiles(tokens, encoder, decoder, joiner)
        }

        /** One silence-aligned (or hard-cut) slice of the buffer, in sample indices. */
        internal data class CutRange(val startSample: Int, val endSample: Int)

        // Same defaults as AudioDecoder's file-based scan, reused here for a
        // FloatArray already in memory rather than re-decoding from disk.
        internal const val TARGET_CHUNK_SEC = 30.0
        /** A cut may land up to this many seconds BEFORE the target, never after, so chunks are at most target + flex long. */
        internal const val FLEX_SEC = 4.0
        private const val WINDOW_MS = 25
        private const val MIN_SILENCE_MS = 250
        private const val RMS_FLOOR = 0.005f
        private const val RMS_CEIL = 0.02f

        /**
         * Silence-aligned cut points over an in-memory buffer: walk forward in
         * [TARGET_CHUNK_SEC] increments, and if a quiet-enough window exists in
         * `[target - FLEX_SEC, target]`, cut there instead of at the exact
         * target — same asymmetric-flex idea as `AudioDecoder.computeCutPoints`
         * (never cuts PAST the nominal target, only earlier, to avoid growing
         * unboundedly on a long silence-free stretch).
         */
        internal fun computeInMemoryCutPoints(
            samples: FloatArray,
            sampleRate: Int,
            /** Nominal chunk length; every chunk is at most this + [FLEX_SEC] long. */
            targetChunkSec: Double = TARGET_CHUNK_SEC,
        ): List<CutRange> {
            if (samples.isEmpty()) return emptyList()
            val totalSec = samples.size.toDouble() / sampleRate
            if (totalSec <= targetChunkSec + FLEX_SEC) {
                return listOf(CutRange(0, samples.size))
            }
            val windowSamples = (WINDOW_MS / 1000.0 * sampleRate).toInt().coerceAtLeast(1)
            val windowRms = FloatArray(samples.size / windowSamples + 1)
            var w = 0
            var idx = 0
            while (idx < samples.size) {
                val end = (idx + windowSamples).coerceAtMost(samples.size)
                var sumSq = 0.0
                for (s in idx until end) sumSq += samples[s].toDouble() * samples[s].toDouble()
                windowRms[w++] = sqrt(sumSq / (end - idx)).toFloat()
                idx = end
            }
            val sorted = windowRms.copyOf(w).also { it.sort() }
            val p20 = if (sorted.isNotEmpty()) sorted[(sorted.size * 0.2).toInt().coerceIn(0, sorted.size - 1)] else 0f
            val threshold = (p20 * 2).coerceIn(RMS_FLOOR, RMS_CEIL)
            val minSilenceWindows = (MIN_SILENCE_MS / WINDOW_MS.toDouble()).toInt().coerceAtLeast(1)

            val cuts = mutableListOf<Int>()
            var targetSec = targetChunkSec
            while (targetSec < totalSec) {
                val targetWindow = (targetSec * sampleRate / windowSamples).toInt()
                val flexWindows = (FLEX_SEC * sampleRate / windowSamples).toInt()
                var bestQuietStart = -1
                var runStart = -1
                var runLen = 0
                var scanFrom = (targetWindow - flexWindows).coerceAtLeast(0)
                for (i in scanFrom..targetWindow.coerceAtMost(w - 1)) {
                    if (windowRms[i] < threshold) {
                        if (runLen == 0) runStart = i
                        runLen++
                        // Only a run that reaches MIN_SILENCE_MS qualifies;
                        // keep scanning — a later qualifying run closer to
                        // the target wins.
                        if (runLen == minSilenceWindows) bestQuietStart = runStart
                    } else {
                        runLen = 0
                    }
                }
                val cutWindow = if (bestQuietStart >= 0) {
                    // Snap to the midpoint of the LAST qualifying quiet run found.
                    (bestQuietStart + minSilenceWindows / 2).coerceAtMost(targetWindow)
                } else targetWindow
                val cutSample = (cutWindow * windowSamples).coerceIn(0, samples.size)
                if (cuts.isEmpty() || cutSample > cuts.last()) cuts.add(cutSample)
                targetSec = (cutSample.toDouble() / sampleRate) + targetChunkSec
            }
            val ranges = mutableListOf<CutRange>()
            var prev = 0
            for (c in cuts) {
                if (c > prev) ranges.add(CutRange(prev, c))
                prev = c
            }
            if (prev < samples.size) ranges.add(CutRange(prev, samples.size))
            return ranges
        }
    }
}
