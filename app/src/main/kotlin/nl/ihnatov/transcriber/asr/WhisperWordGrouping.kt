package nl.ihnatov.transcriber.asr

import kotlin.math.exp
import kotlin.math.ln

/**
 * Turns whisper.cpp's per-token output (see `nativeSegmentTokens` in
 * `jni_whisper.cpp`) into [Word]s. Pure Kotlin so the rule is unit-testable
 * without the native library.
 *
 * Whisper's BPE tokens carry their own leading space: a token whose text
 * starts with ' ' begins a new word, anything else (word pieces, attached
 * punctuation) continues the current one. Token text arrives as raw BYTES
 * and is decoded only once a whole word is assembled — the vocabulary
 * splits multi-byte UTF-8 characters across tokens (routine for Cyrillic
 * and Arabic), so decoding token-by-token would corrupt them.
 */
object WhisperWordGrouping {

    /** Floor for a token probability before taking its log — a literal 0 would zero the whole word. */
    private const val MIN_TOKEN_P = 1e-6

    private const val SPACE: Byte = 0x20
    private const val REPLACEMENT_CHAR = '�'

    /**
     * @param tokenBytes raw UTF-8 bytes of each (non-special) token
     * @param tokenTimes t0,t1 interleaved per token, whisper's 10 ms units
     * @param tokenProbs probability of each token
     * @param segStart/segEnd the owning segment's bounds in seconds — whisper's
     *   token-level timestamps are marked experimental upstream and can stray,
     *   so every word is clamped inside its segment.
     * @return words in order; confidence is the geometric mean of the word's token probabilities.
     */
    fun group(
        tokenBytes: Array<ByteArray>,
        tokenTimes: LongArray,
        tokenProbs: FloatArray,
        segStart: Double,
        segEnd: Double,
    ): List<Word> {
        val n = minOf(tokenBytes.size, tokenTimes.size / 2, tokenProbs.size)
        if (n == 0) return emptyList()
        val words = mutableListOf<Word>()
        val buf = java.io.ByteArrayOutputStream()
        var t0 = 0L
        var t1 = 0L
        var logSum = 0.0
        var count = 0

        fun flush() {
            if (count > 0) {
                // Malformed leftovers (a truncated sequence at the very end
                // of a segment) decode to U+FFFD — drop those, keep the rest.
                val text = String(buf.toByteArray(), Charsets.UTF_8)
                    .filter { it != REPLACEMENT_CHAR }
                    .trim()
                if (text.isNotEmpty()) {
                    val start = (t0 * 0.01).coerceIn(segStart, maxOf(segStart, segEnd))
                    val end = (t1 * 0.01).coerceIn(start, maxOf(start, segEnd))
                    words.add(Word(start, end, text, exp(logSum / count).toFloat()))
                }
            }
            buf.reset()
            logSum = 0.0
            count = 0
        }

        for (i in 0 until n) {
            val bytes = tokenBytes[i]
            if (bytes.isEmpty()) continue
            if (bytes[0] == SPACE && count > 0) flush()
            if (count == 0) t0 = tokenTimes[i * 2]
            t1 = tokenTimes[i * 2 + 1]
            buf.write(bytes, 0, bytes.size)
            logSum += ln(tokenProbs[i].toDouble().coerceIn(MIN_TOKEN_P, 1.0))
            count++
        }
        flush()
        return words
    }
}
