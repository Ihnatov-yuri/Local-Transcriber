package nl.ihnatov.transcriber.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Decode any audio file Android can play (MP3, M4A/AAC, OGG/Vorbis, FLAC, WAV)
 * into a mono float32 buffer at 16 kHz — the format Whisper/Gemma expect.
 *
 * Implementation: MediaExtractor pulls the encoded packets out of the
 * container, MediaCodec decodes them to 16-bit PCM, we downmix to mono and
 * resample to 16 kHz with simple linear interpolation. Linear is good enough
 * for ASR; anything fancier (sinc, polyphase) would be premature for our use
 * case and adds dependencies.
 *
 * Memory: a 30-minute mp3 → 30 * 60 * 16000 = ~28.8 M float = 115 MB heap.
 * Comfortable on the S24 Ultra. Future improvement: decode in 30-second
 * windows and feed them to the ASR pipeline streaming, so we never hold the
 * whole clip in memory.
 */
object AudioDecoder {

    private const val TAG = "AudioDecoder"
    const val TARGET_SR = 16_000

    /** What gets handed to the transcription pipeline. */
    data class Decoded(
        val samples: FloatArray,
        val sampleRate: Int,
    ) {
        fun durationSeconds(): Double = samples.size.toDouble() / sampleRate
    }

    /**
     * One chunk in a streaming decode.
     *
     * [startSeconds] is the absolute timeline offset of [samples][0]. With
     * overlap enabled, consecutive chunks share their boundary audio: the
     * first [overlapSamples] of chunk N+1 are byte-identical to the last
     * [overlapSamples] of chunk N. Set [overlapSamples] = 0 for the first
     * chunk (nothing to overlap with) and for all chunks when the consumer
     * doesn't ask for overlap.
     *
     * Why: hard chunk cuts at, e.g., 28 s slice words in half. Without
     * overlap the model needs to recognise a half-syllable cold; with a
     * few seconds of recap it sees the full word in context and the
     * "first part of the chunk is missing from the transcript" failure
     * mode disappears. The consumer is expected to either tell the model
     * to skip the recap (Gemma4Backend does this) or de-dupe the
     * resulting transcript text.
     */
    data class Chunk(
        val samples: FloatArray,
        val startSeconds: Double,
        val sampleRate: Int = TARGET_SR,
        val overlapSamples: Int = 0,
    ) {
        val overlapSeconds: Double get() = overlapSamples.toDouble() / sampleRate
    }

    /**
     * Stream the audio file as fixed-size mono-float32 chunks at [TARGET_SR]
     * Hz. Each chunk holds [chunkSamples] samples (the tail may be shorter).
     *
     * Use this instead of [decode] for Gemma's chunked transcription path —
     * Gemma processes 25-second chunks anyway, so there's no reason to hold
     * an entire 1-hour MP3 (~230 MB) in heap before the first inference. The
     * Flow lazy-produces chunks; the consumer collects one, transcribes it,
     * discards it before the next decode pass writes more PCM.
     *
     * Strategy: keep a small `srcBuf` of decoded source-rate samples. Each
     * time it grows large enough to produce one output chunk, resample to
     * mono-float32@TARGET_SR, emit, drop the consumed prefix. Peak heap per
     * chunk is bounded by `chunkSamples * (inSR/outSR + 1)` floats — a few
     * MB even for very long files.
     */
    fun decodeChunked(
        file: File,
        chunkSamples: Int,
        /**
         * Sliding-window overlap in target-SR samples. Each chunk after the
         * first re-includes its leading [overlapSamples] from the previous
         * chunk's tail, so word boundaries that straddle the chunk cut are
         * fully audible on the second pass. Set to 0 (default) to keep the
         * original contiguous-chunks behaviour.
         */
        overlapSamples: Int = 0,
    ): Flow<Chunk> = flow {
        require(chunkSamples > 0) { "chunkSamples must be positive" }
        require(overlapSamples in 0 until chunkSamples) {
            "overlapSamples must be in [0, chunkSamples)"
        }
        val ext = MediaExtractor()
        try {
            ext.setDataSource(file.absolutePath)
            val trackIndex = (0 until ext.trackCount).firstOrNull { i ->
                val fmt = ext.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                mime.startsWith("audio/")
            } ?: error("No audio track in ${file.name}")
            ext.selectTrack(trackIndex)
            val inFormat = ext.getTrackFormat(trackIndex)
            val mime = inFormat.getString(MediaFormat.KEY_MIME)!!
            val inSampleRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val inChannels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            Log.i(TAG, "stream-decoding $mime sr=$inSampleRate ch=$inChannels chunk=$chunkSamples")

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inFormat, null, null, 0)
            codec.start()

            val ratio = inSampleRate.toDouble() / TARGET_SR
            var chunksEmitted = 0
            var srcAppended = 0L
            val startedAt = System.currentTimeMillis()
            // How many source samples we need to produce one full output chunk.
            // +1 so the last interp can reach `srcBuf[i0+1]`.
            val srcPerChunk = (chunkSamples * ratio).toInt() + 1
            // Linear FloatArray + head/tail indexes. No autoboxing (4 B/sample
            // vs ArrayList<Float>'s 16 B) and no O(n) removeAt — emitting a
            // chunk just advances `head`, and we compact only when needed.
            var srcBuf = FloatArray(srcPerChunk * 2)
            var head = 0
            var tail = 0
            var samplesEmittedAtTarget = 0L

            fun append(v: Float) {
                if (tail == srcBuf.size) {
                    // Compact: shift unread samples to the front. If still
                    // full after compaction, grow.
                    if (head > 0) {
                        System.arraycopy(srcBuf, head, srcBuf, 0, tail - head)
                        tail -= head
                        head = 0
                    }
                    if (tail == srcBuf.size) {
                        srcBuf = srcBuf.copyOf(srcBuf.size * 2)
                    }
                }
                srcBuf[tail++] = v
                srcAppended++
            }

            // How far the source-rate read head advances per emitted chunk.
            // Without overlap this equals `consumed` (= chunkSamples in
            // target rate). With overlap, we leave the trailing `overlap`
            // samples behind in srcBuf so the next emit re-reads them as
            // its leading recap. Track separately because `samplesEmitted-
            // AtTarget` (the timeline cursor) also needs to advance by the
            // shorter step — otherwise chunk N+1's startSec would over-
            // count by `overlap` and timestamps would drift forward
            // chunk-by-chunk.
            val srcAdvancePerChunk = ((chunkSamples - overlapSamples).toLong() * inSampleRate / TARGET_SR).toInt()
            val advanceAtTarget = chunkSamples - overlapSamples

            suspend fun maybeEmitChunks() {
                while (tail - head >= srcPerChunk) {
                    val out = FloatArray(chunkSamples)
                    for (i in 0 until chunkSamples) {
                        val pos = i * ratio
                        val i0 = pos.toInt()
                        val frac = (pos - i0).toFloat()
                        // Always safe because (tail - head) >= srcPerChunk
                        // == (chunkSamples * ratio).toInt() + 1.
                        out[i] = srcBuf[head + i0] * (1 - frac) +
                            srcBuf[head + i0 + 1] * frac
                    }
                    val startSec = samplesEmittedAtTarget.toDouble() / TARGET_SR
                    // First chunk has no preceding audio to recap, so it
                    // emits with overlap=0 even when subsequent chunks
                    // will have it.
                    val emittedOverlap = if (chunksEmitted == 0) 0 else overlapSamples
                    samplesEmittedAtTarget += advanceAtTarget
                    emit(Chunk(
                        samples = out,
                        startSeconds = startSec,
                        sampleRate = TARGET_SR,
                        overlapSamples = emittedOverlap,
                    ))
                    chunksEmitted++
                    // Advance the read cursor by the post-overlap step so
                    // the next emit overlaps. Drift from integer truncation
                    // on `srcAdvancePerChunk` is at most 1 source sample
                    // per chunk; for 44.1k/48k → 16k it's exactly 0.
                    head = (head + srcAdvancePerChunk).coerceAtMost(tail)
                }
            }

            val info = MediaCodec.BufferInfo()
            var sawInputEOS = false
            var sawOutputEOS = false

            while (!sawOutputEOS) {
                if (!sawInputEOS) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        val n = ext.readSampleData(inBuf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEOS = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, ext.sampleTime, 0)
                            ext.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx >= 0 -> {
                        if (info.size > 0) {
                            val outBuf = codec.getOutputBuffer(outIdx)!!.duplicate()
                            outBuf.order(ByteOrder.LITTLE_ENDIAN)
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            appendDownmixed(outBuf, inChannels) { v -> append(v) }
                            maybeEmitChunks()
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            sawOutputEOS = true
                        }
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { /* PCM format settled */ }
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> { /* spin again */ }
                }
            }
            // EOS: emit the trailing partial chunk if any source samples remain.
            // With overlap enabled, `head` was deliberately left `overlap`
            // samples BEFORE the previous chunk's end, so the remaining
            // buffer naturally includes the recap audio at its start —
            // we just tag it with overlapSamples so the consumer can skip
            // re-transcribing the recap.
            val remaining = tail - head
            if (remaining > 1) {
                val outSamples = ((remaining - 1) / ratio).toInt().coerceAtLeast(1)
                val out = FloatArray(outSamples)
                for (i in 0 until outSamples) {
                    val pos = i * ratio
                    val i0 = pos.toInt().coerceAtMost(remaining - 1)
                    val i1 = (i0 + 1).coerceAtMost(remaining - 1)
                    val frac = (pos - i0).toFloat()
                    out[i] = srcBuf[head + i0] * (1 - frac) + srcBuf[head + i1] * frac
                }
                val startSec = samplesEmittedAtTarget.toDouble() / TARGET_SR
                val trailingOverlap = if (chunksEmitted == 0) 0
                    else minOf(overlapSamples, outSamples - 1).coerceAtLeast(0)
                emit(Chunk(
                    samples = out,
                    startSeconds = startSec,
                    sampleRate = TARGET_SR,
                    overlapSamples = trailingOverlap,
                ))
                chunksEmitted++
            }

            codec.stop()
            codec.release()
            val durMs = System.currentTimeMillis() - startedAt
            val srcSec = srcAppended.toDouble() / inSampleRate
            Log.i(
                TAG,
                "stream done: $chunksEmitted chunks, " +
                    "${"%.1f".format(srcSec)}s of source audio, " +
                    "${durMs}ms wall time",
            )
        } finally {
            ext.release()
        }
    }.flowOn(Dispatchers.IO)

    suspend fun decode(file: File): Decoded = withContext(Dispatchers.IO) {
        val ext = MediaExtractor()
        try {
            ext.setDataSource(file.absolutePath)
            val trackIndex = (0 until ext.trackCount).firstOrNull { i ->
                val fmt = ext.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                mime.startsWith("audio/")
            } ?: error("No audio track in ${file.name}")
            ext.selectTrack(trackIndex)
            val inFormat = ext.getTrackFormat(trackIndex)
            val mime = inFormat.getString(MediaFormat.KEY_MIME)!!
            val inSampleRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val inChannels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            Log.i(TAG, "decoding $mime  sr=$inSampleRate  ch=$inChannels  file=${file.name}")

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inFormat, null, null, 0)
            codec.start()

            val pcmMono = decodeToMonoPcm(ext, codec, inChannels)
            codec.stop()
            codec.release()

            val finalSamples = if (inSampleRate == TARGET_SR) {
                pcmMono
            } else {
                resampleLinear(pcmMono, inSampleRate, TARGET_SR)
            }
            Decoded(samples = finalSamples, sampleRate = TARGET_SR)
        } finally {
            ext.release()
        }
    }

    /**
     * Pump packets from MediaExtractor into MediaCodec, pull decoded PCM out,
     * downmix to mono on the fly. Returns float samples in [-1, 1].
     *
     * Storage is a resizable raw FloatArray (not ArrayList<Float>) — boxing
     * every sample would cost ~16 bytes/Float and ~120 MB heap for a 30-min
     * MP3. Doubling-grow keeps amortized append cost O(1) without the box.
     */
    private fun decodeToMonoPcm(
        ext: MediaExtractor,
        codec: MediaCodec,
        channels: Int,
    ): FloatArray {
        val info = MediaCodec.BufferInfo()
        var sawInputEOS = false
        var sawOutputEOS = false
        var buf = FloatArray(1_024 * 64)
        var size = 0

        fun appendOne(v: Float) {
            if (size == buf.size) buf = buf.copyOf(buf.size * 2)
            buf[size++] = v
        }

        while (!sawOutputEOS) {
            if (!sawInputEOS) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val inBuf = codec.getInputBuffer(inIdx)!!
                    val n = ext.readSampleData(inBuf, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEOS = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, n, ext.sampleTime, 0)
                        ext.advance()
                    }
                }
            }
            val outIdx = codec.dequeueOutputBuffer(info, 10_000)
            when {
                outIdx >= 0 -> {
                    if (info.size > 0) {
                        val outBuf = codec.getOutputBuffer(outIdx)!!.duplicate()
                        outBuf.order(ByteOrder.LITTLE_ENDIAN)
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        appendDownmixed(outBuf, channels, ::appendOne)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sawOutputEOS = true
                    }
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // PCM format. The codec just told us its actual PCM layout;
                    // channel count can stay the same as the input (we still
                    // downmix it to mono either way).
                }
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // No data ready yet — loop.
                }
            }
        }
        return if (size == buf.size) buf else buf.copyOf(size)
    }

    /**
     * Read a buffer of 16-bit PCM (interleaved per [channels]), downmix to
     * mono float in [-1, 1], hand each sample to [emit].
     */
    private inline fun appendDownmixed(
        buf: ByteBuffer,
        channels: Int,
        emit: (Float) -> Unit,
    ) {
        val total = (buf.limit() - buf.position()) / 2
        val frames = total / channels
        if (channels == 1) {
            for (i in 0 until frames) emit(buf.short.toInt() / 32768f)
        } else {
            for (i in 0 until frames) {
                var sum = 0
                for (c in 0 until channels) sum += buf.short.toInt()
                emit((sum.toFloat() / channels) / 32768f)
            }
        }
    }

    /** A contiguous region of low-energy ("silence") audio. Seconds, absolute timeline. */
    data class SilenceRegion(val startSec: Double, val endSec: Double) {
        val midSec: Double get() = (startSec + endSec) / 2.0
        val durationSec: Double get() = endSec - startSec
    }

    /**
     * Streaming energy-VAD silence scan over the full audio file. Returns
     * silence regions that are at least [minSilenceMs] long with windowed
     * RMS below an ADAPTIVE threshold derived from the file's own noise
     * floor (with [rmsThreshold] as the minimum floor).
     *
     * Used by the transcription pipeline to align chunk cuts on natural
     * pauses so mid-word boundary cuts (and the "first part of the chunk
     * is lost" failure mode) disappear. One MediaCodec pass; on an S24
     * Ultra a 30-min file scans in ~3 sec.
     *
     * Why adaptive (was static 0.005f / ~-46 dBFS): noisy recordings —
     * cafe, car, ambient music — have no windows that quiet, so the
     * static threshold found zero silences and every cut hard-cut at
     * exactly 28s. The fix: collect per-window RMS first, take the
     * 20th-percentile RMS as "noise floor," set threshold = 2× that.
     * Clamped between [rmsThreshold] (the old static floor) and 0.02
     * (above ~0.02 you'd be calling quiet speech "silence" and
     * cutting mid-sentence).
     *
     * Why energy VAD and not a proper neural one (silero, etc): we
     * already have sherpa-onnx but adding a second VAD model just for
     * chunk alignment is overkill. Adaptive energy catches real pauses
     * cleanly on most recording conditions; only constant-loud audio
     * (live concert, noise floor near speech volume) still falls back
     * to the [rmsThreshold] floor.
     */
    suspend fun scanSilences(
        file: File,
        minSilenceMs: Int = 250,
        rmsThreshold: Float = 0.005f,
        windowMs: Int = 25,
    ): List<SilenceRegion> = withContext(Dispatchers.IO) {
        val windowSamples = (TARGET_SR.toLong() * windowMs / 1000).toInt()
        require(windowSamples > 0)
        val streamChunk = windowSamples * 256

        // Phase 1: collect per-window RMS in a flat FloatArray. Cheap
        // memory cost: 25 ms windows × 4 bytes × 144,000 windows for a
        // 1-hour file ≈ 576 KB. Comfortable.
        val rmsList = ArrayList<Float>(8192)
        var samplesSeen = 0L
        decodeChunked(file, chunkSamples = streamChunk).collect { chunk ->
            val total = chunk.samples.size
            val full = total / windowSamples
            for (w in 0 until full) {
                rmsList.add(windowRms(chunk.samples, w * windowSamples, windowSamples))
            }
            samplesSeen += total
        }
        if (rmsList.isEmpty()) {
            Log.i(TAG, "VAD scan: 0 windows (empty audio?)")
            return@withContext emptyList<SilenceRegion>()
        }

        // Phase 2: adaptive threshold from the file's noise floor.
        val sorted = rmsList.toFloatArray().also { it.sort() }
        val p20 = sorted[(sorted.size / 5).coerceAtMost(sorted.size - 1)]
        val adaptive = (p20 * 2.0f).coerceIn(rmsThreshold, 0.02f)
        Log.i(
            TAG, "VAD scan: noise-floor p20=$p20 → threshold=$adaptive " +
                "(floor=$rmsThreshold, cap=0.02)"
        )

        // Phase 3: walk the rms list, find silence runs.
        val out = mutableListOf<SilenceRegion>()
        var silenceStartSec: Double? = null
        val minSilenceSec = minSilenceMs / 1000.0
        val windowSec = windowMs / 1000.0
        for ((i, rms) in rmsList.withIndex()) {
            val tStart = i * windowSec
            if (rms < adaptive) {
                if (silenceStartSec == null) silenceStartSec = tStart
            } else {
                silenceStartSec?.let { s ->
                    if (tStart - s >= minSilenceSec) out.add(SilenceRegion(s, tStart))
                }
                silenceStartSec = null
            }
        }
        // Trailing silence at EOF.
        silenceStartSec?.let { s ->
            val end = rmsList.size * windowSec
            if (end - s >= minSilenceSec) out.add(SilenceRegion(s, end))
        }
        Log.i(TAG, "VAD scan: ${out.size} silences in ${samplesSeen.toDouble() / TARGET_SR}s")
        out
    }

    private fun windowRms(buf: FloatArray, offset: Int, len: Int): Float {
        var sumSq = 0.0
        var i = 0
        while (i < len) {
            val v = buf[offset + i]
            sumSq += v.toDouble() * v.toDouble()
            i++
        }
        return kotlin.math.sqrt(sumSq / len).toFloat()
    }

    /**
     * One scheduled chunk boundary. [silenceAligned] is true when the cut
     * snapped to a real silence region in the audio (no word gets sliced)
     * and false when we fell back to a hard cut at the nominal time
     * (long monologue, music, dense conversation — no silence in the
     * flex window). Downstream uses this to decide whether to emit an
     * audio recap-overlap for the NEXT chunk: silence-aligned cuts need
     * no overlap (nothing is broken across them, à la WhisperX); hard
     * cuts get the 1-second overlap to bridge mid-syllable breaks.
     */
    data class CutPoint(val timeSec: Double, val silenceAligned: Boolean)

    /**
     * Pick chunk-end times that snap to the nearest silence within
     * [flexSec] of each [targetChunkSec] increment. Falls back to a hard
     * cut at the target time when no silence is in range.
     *
     * Returns [CutPoint]s in ascending time order. The last boundary is
     * the file's [durationSec] (implicit, not in the list).
     */
    fun computeCutPoints(
        silences: List<SilenceRegion>,
        durationSec: Double,
        targetChunkSec: Double,
        flexSec: Double,
    ): List<CutPoint> {
        if (durationSec <= targetChunkSec) return emptyList()
        val cuts = mutableListOf<CutPoint>()
        var prevCut = 0.0
        var safety = 0
        while (durationSec - prevCut > targetChunkSec + flexSec && safety < 10_000) {
            val targetTime = prevCut + targetChunkSec
            // ASYMMETRIC flex: silences are only acceptable EARLIER
            // than the nominal target, never later. Symmetric flex
            // (lo = target - flex, hi = target + flex) let cuts land
            // up to 30 s into the chunk when targetChunkSec = 28 and
            // flexSec = 2, exactly at Gemma 4 E2B's audio capacity —
            // and at that edge the engine deterministically wedges
            // (no deltas, hits the outer timeout). One-sided flex
            // means chunks are 26–28 s, never 28–30 s. Worst case we
            // miss a great pause that fell at target+1 s and have to
            // settle for one a bit earlier; quality cost is tiny,
            // wedge avoidance is total.
            val lo = targetTime - flexSec
            val hi = targetTime
            val candidate = silences
                .asSequence()
                .filter { it.midSec in lo..hi && it.midSec > prevCut + 1.0 }
                .minByOrNull { kotlin.math.abs(it.midSec - targetTime) }
            val cut: CutPoint = if (candidate != null) {
                CutPoint(candidate.midSec, silenceAligned = true)
            } else {
                CutPoint(targetTime, silenceAligned = false)
            }
            cuts.add(cut)
            prevCut = cut.timeSec
            safety++
        }
        return cuts
    }

    /**
     * Stream the audio file as chunks bounded by explicit cut points.
     * Each emitted chunk covers the audio from the previous cut (or 0
     * for the first chunk) to the next cut, plus an optional [overlapSamples]
     * recap from the previous chunk's tail (the same shape as the fixed-
     * size [decodeChunked]).
     *
     * Unlike [decodeChunked], chunks here are variable-size by design —
     * the caller picked cut points already (typically via
     * [scanSilences] + [computeCutPoints]) so chunks land on natural
     * pauses instead of mid-syllable.
     *
     * Memory: bounded by max chunk duration; we keep one chunk's worth
     * of samples in srcBuf at a time, plus the overlap.
     */
    fun decodeAtCutPoints(
        file: File,
        cutPoints: List<CutPoint>,
        /**
         * Overlap (samples) to bridge a HARD cut — when VAD couldn't find
         * a real silence in the flex window. The model needs a recap of
         * the previous chunk's tail so mid-word breaks don't lose the
         * first half of the word. Set to 0 to disable.
         */
        hardCutOverlapSamples: Int = 0,
        /**
         * Overlap (samples) to bridge a SILENCE-aligned cut — the audio
         * already has a natural pause at the cut, no word is broken, no
         * recap is needed. WhisperX-style: trust the VAD where it
         * succeeded. Defaults to 0; set to a small value only if you
         * want a safety margin against VAD mis-detection.
         */
        silenceCutOverlapSamples: Int = 0,
    ): Flow<Chunk> = flow {
        require(hardCutOverlapSamples >= 0)
        require(silenceCutOverlapSamples >= 0)
        // Convert cut points to target-rate sample positions and align
        // each with its silence-aligned flag so we can pick the right
        // overlap when emitting the chunk on the OTHER side of that cut.
        val cutsSortedByTime = cutPoints.sortedBy { it.timeSec }
        val cutSamples = cutsSortedByTime.map { (it.timeSec * TARGET_SR).toLong() }
            .toMutableList()
        val cutSilenceFlags = cutsSortedByTime.map { it.silenceAligned }
        val ext = MediaExtractor()
        try {
            ext.setDataSource(file.absolutePath)
            val trackIndex = (0 until ext.trackCount).firstOrNull { i ->
                val fmt = ext.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                mime.startsWith("audio/")
            } ?: error("No audio track in ${file.name}")
            ext.selectTrack(trackIndex)
            val inFormat = ext.getTrackFormat(trackIndex)
            val mime = inFormat.getString(MediaFormat.KEY_MIME)!!
            val inSampleRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val inChannels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            Log.i(TAG, "vad-stream-decoding $mime sr=$inSampleRate ch=$inChannels " +
                "cuts=${cutPoints.size} (silence-aligned=${cutSilenceFlags.count { it }})")
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inFormat, null, null, 0)
            codec.start()

            val ratio = inSampleRate.toDouble() / TARGET_SR
            // Output target-rate samples accumulated since file start.
            // We resample to target rate as we go (matching decodeChunked)
            // so cut-point arithmetic is at target SR.
            var samplesEmittedAtTarget = 0L
            // Current chunk's accumulator at target rate.
            val maxChunkGuessSamples = (cutSamples.maxOrNull() ?: TARGET_SR.toLong() * 60).toInt() + TARGET_SR * 8
            var chunkBuf = FloatArray(0)
            var chunkBufLen = 0
            // Streaming src-rate buffer + linear interp state — copied from
            // decodeChunked's hot loop.
            var srcBuf = FloatArray(8192)
            var srcHead = 0
            var srcTail = 0

            fun ensureChunkCap(n: Int) {
                if (chunkBufLen + n > chunkBuf.size) {
                    val newSize = maxOf(chunkBuf.size * 2, chunkBufLen + n, 16384)
                    chunkBuf = chunkBuf.copyOf(newSize)
                }
            }
            fun appendSrc(v: Float) {
                if (srcTail == srcBuf.size) {
                    if (srcHead > 0) {
                        System.arraycopy(srcBuf, srcHead, srcBuf, 0, srcTail - srcHead)
                        srcTail -= srcHead; srcHead = 0
                    }
                    if (srcTail == srcBuf.size) srcBuf = srcBuf.copyOf(srcBuf.size * 2)
                }
                srcBuf[srcTail++] = v
            }
            // Resample one target sample at relative position from current head.
            // Drains `ratio` source samples worth per target sample.
            var srcFracPos = 0.0
            fun drainToTargetSample(): Float? {
                val srcIdx = srcFracPos
                val i0 = srcIdx.toInt()
                if (srcHead + i0 + 1 >= srcTail) return null
                val frac = (srcIdx - i0).toFloat()
                val v = srcBuf[srcHead + i0] * (1 - frac) +
                    srcBuf[srcHead + i0 + 1] * frac
                srcFracPos += ratio
                return v
            }
            fun maybeAdvanceSrcHead() {
                // When srcFracPos gets large, compact srcBuf by dropping
                // samples we'll never re-read. Keep at least a few samples
                // ahead for the next interp.
                val drop = srcFracPos.toInt() - 2
                if (drop > 0) {
                    srcHead += drop
                    srcFracPos -= drop
                }
            }

            // Index of the next cut to consume (defines current chunk's end).
            var nextCutIdx = 0
            var chunkStartTarget = 0L
            // Pending overlap to prepend to the NEXT chunk (samples copied
            // from the tail of the just-emitted chunk).
            var overlapPrefix: FloatArray? = null

            suspend fun emitChunkIfReady() {
                while (true) {
                    val endTarget = if (nextCutIdx < cutSamples.size) cutSamples[nextCutIdx] else Long.MAX_VALUE
                    val want = (endTarget - chunkStartTarget).toInt().coerceAtLeast(0)
                    if (want <= 0 && nextCutIdx >= cutSamples.size) return
                    val have = chunkBufLen
                    if (have < want) return
                    // Take `want` samples for this chunk; prepend overlap from prev chunk.
                    val prefix = overlapPrefix
                    val out = if (prefix != null) {
                        val combined = FloatArray(prefix.size + want)
                        System.arraycopy(prefix, 0, combined, 0, prefix.size)
                        System.arraycopy(chunkBuf, 0, combined, prefix.size, want)
                        combined
                    } else {
                        chunkBuf.copyOfRange(0, want)
                    }
                    val emittedOverlap = prefix?.size ?: 0
                    val chunkStartSec = (chunkStartTarget - emittedOverlap).coerceAtLeast(0L)
                        .toDouble() / TARGET_SR
                    emit(Chunk(
                        samples = out,
                        startSeconds = chunkStartSec,
                        sampleRate = TARGET_SR,
                        overlapSamples = emittedOverlap,
                    ))
                    // Save tail as next chunk's overlap prefix. Pick the
                    // overlap size based on WHICH cut we just consumed:
                    // silence-aligned → no recap needed (WhisperX-style,
                    // typically 0 samples); hard cut → bridge with the
                    // hardCutOverlap value. The decision is per-cut, not
                    // global, so a mostly-silent file gets zero overlap
                    // overhead while occasional hard-cut chunks still
                    // bridge their boundaries.
                    val consumedCutIdx = nextCutIdx - 1
                    val nextOverlap = if (consumedCutIdx in cutSilenceFlags.indices) {
                        if (cutSilenceFlags[consumedCutIdx]) silenceCutOverlapSamples
                        else hardCutOverlapSamples
                    } else 0
                    overlapPrefix = if (nextOverlap > 0 && want >= nextOverlap) {
                        chunkBuf.copyOfRange(want - nextOverlap, want)
                    } else null
                    // Shift consumed samples out of chunkBuf.
                    if (chunkBufLen > want) {
                        System.arraycopy(chunkBuf, want, chunkBuf, 0, chunkBufLen - want)
                    }
                    chunkBufLen -= want
                    chunkStartTarget = endTarget
                    nextCutIdx++
                    if (nextCutIdx > cutSamples.size) return
                }
            }

            val info = MediaCodec.BufferInfo()
            var sawInputEOS = false
            var sawOutputEOS = false
            while (!sawOutputEOS) {
                if (!sawInputEOS) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        val n = ext.readSampleData(inBuf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEOS = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, ext.sampleTime, 0)
                            ext.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx >= 0 -> {
                        if (info.size > 0) {
                            val outBuf = codec.getOutputBuffer(outIdx)!!.duplicate()
                            outBuf.order(ByteOrder.LITTLE_ENDIAN)
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            appendDownmixed(outBuf, inChannels) { v -> appendSrc(v) }
                            // Drain to target rate as much as possible.
                            while (true) {
                                val v = drainToTargetSample() ?: break
                                ensureChunkCap(1)
                                chunkBuf[chunkBufLen++] = v
                            }
                            maybeAdvanceSrcHead()
                            emitChunkIfReady()
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            sawOutputEOS = true
                        }
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { /* ignore */ }
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> { /* spin */ }
                }
            }
            // EOS: emit any trailing samples as the final chunk regardless
            // of the cut-point list. The last cut implied "end of file"
            // anyway.
            if (chunkBufLen > 0) {
                val prefix = overlapPrefix
                val out = if (prefix != null) {
                    val combined = FloatArray(prefix.size + chunkBufLen)
                    System.arraycopy(prefix, 0, combined, 0, prefix.size)
                    System.arraycopy(chunkBuf, 0, combined, prefix.size, chunkBufLen)
                    combined
                } else {
                    chunkBuf.copyOfRange(0, chunkBufLen)
                }
                val emittedOverlap = prefix?.size ?: 0
                val chunkStartSec = (chunkStartTarget - emittedOverlap).coerceAtLeast(0L)
                    .toDouble() / TARGET_SR
                emit(Chunk(
                    samples = out,
                    startSeconds = chunkStartSec,
                    sampleRate = TARGET_SR,
                    overlapSamples = emittedOverlap,
                ))
            }
            codec.stop()
            codec.release()
        } finally {
            ext.release()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Naive linear resampler. For ASR this is fine — the alias artifacts at
     * 8 kHz target frequencies are inaudible to Whisper/Gemma. The audio book
     * crowd would balk, but they're not our user.
     */
    private fun resampleLinear(input: FloatArray, srIn: Int, srOut: Int): FloatArray {
        if (srIn == srOut) return input
        val ratio = srIn.toDouble() / srOut
        val outLen = (input.size / ratio).toInt()
        val output = FloatArray(outLen)
        for (i in 0 until outLen) {
            val src = i * ratio
            val i0 = src.toInt()
            val i1 = min(i0 + 1, input.size - 1)
            val frac = (src - i0).toFloat()
            output[i] = input[i0] * (1 - frac) + input[i1] * frac
        }
        return output
    }
}
