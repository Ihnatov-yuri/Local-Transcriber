package nl.ihnatov.transcriber.audio

import java.io.File
import kotlin.math.abs

/**
 * One-shot peak-amplitude extractor for the Detail-screen waveform.
 *
 * Re-uses [AudioDecoder]'s streaming chunked decode so we never materialize
 * the whole audio buffer (a 90-minute MP3 would be ~350 MB as float32 mono;
 * we never hold more than one 0.1-second window).
 *
 * Output is [buckets] peak-amplitude values in [0, 1] — one per visual
 * column of the waveform. 200 buckets is plenty for a phone-width
 * waveform (~2 px/bucket on a 412 dp screen at 3x density).
 *
 * Cost: ~150 ms for a 30-min recording on an S24-class device, since
 * AudioDecoder runs MediaCodec on a hardware decoder. Run on a background
 * coroutine and cache the result per-recording.
 */
object WaveformLoader {

    suspend fun extract(file: File, buckets: Int = 200): FloatArray {
        if (!file.exists() || buckets <= 0) return FloatArray(0)
        // 0.1-sec fine-grain windows. At 16 kHz that's 1600 samples per
        // window; small enough that even a 3-second clip produces 30
        // fine-grain bins, enough to resample to even a tiny final
        // bucket count without rounding everything to zero.
        val fineWindow = AudioDecoder.TARGET_SR / 10
        // Stream-collect: per fine-grain window we keep ONLY a single
        // float (the window's peak amplitude). Even a 3-hour audio file
        // produces ~108K floats here = ~430 KB — small enough to hold
        // in memory but vastly cheaper than buffering every chunk's
        // 1600 float samples via .toList() (which would be ~700 MB on
        // the same file and OOM the app).
        val fine = mutableListOf<Float>()
        AudioDecoder.decodeChunked(file, fineWindow).collect { chunk ->
            var peak = 0f
            val s = chunk.samples
            var i = 0
            while (i < s.size) {
                val a = abs(s[i])
                if (a > peak) peak = a
                i++
            }
            fine.add(peak)
        }
        if (fine.isEmpty()) return FloatArray(0)
        // Resample fine-grain to the target bucket count via averaging.
        // Average (not peak) on the second pass smooths the waveform so
        // a single loud transient doesn't make a whole bucket spike.
        val out = FloatArray(buckets)
        val ratio = fine.size.toDouble() / buckets
        for (i in 0 until buckets) {
            val lo = (i * ratio).toInt()
            val hi = ((i + 1) * ratio).toInt().coerceAtMost(fine.size)
            if (lo >= hi) {
                out[i] = if (lo < fine.size) fine[lo] else 0f
                continue
            }
            var sum = 0f
            for (j in lo until hi) sum += fine[j]
            out[i] = sum / (hi - lo)
        }
        return out
    }
}
