package nl.ihnatov.transcriber.asr

/**
 * Heuristic per-chunk quality signal, ported as-is from the Mac app's
 * `Transcriberr/ASR/ChunkConfidence.swift`. Gemma 4 doesn't expose
 * token-level confidence, so this looks at the OUTPUT TEXT for the
 * failure shapes actually observed in practice:
 *   - Output is very short relative to the audio window's duration
 *   - Output ends mid-sentence with no terminal punctuation
 *   - Output collapses into a repetitive loop ("the the the the ...")
 *   - Output contains common hallucination patterns
 *
 * Each signal contributes to a 0..1 score; everything at or above
 * [lowThreshold] is a candidate for Super mode's second (arbitration) pass.
 */
data class ChunkConfidence(val score: Double, val reasons: List<String>) {

    val isLow: Boolean get() = score >= lowThreshold

    companion object {
        /** Chunks at or above this score are candidates for re-arbitration. */
        const val lowThreshold: Double = 0.5

        fun assess(rawText: String, audioDurationSeconds: Double): ChunkConfidence {
            val text = rawText.trim()
            var score = 0.0
            val reasons = mutableListOf<String>()

            // 1. Empty / near-empty output for non-silent audio (>5s)
            val charCount = text.length
            if (audioDurationSeconds > 5 && charCount < 12) {
                score += 0.6
                reasons.add("too-short(${charCount}c/${audioDurationSeconds.toInt()}s)")
            } else if (audioDurationSeconds > 15 && charCount < 60) {
                score += 0.3
                reasons.add("sparse(${charCount}c/${audioDurationSeconds.toInt()}s)")
            }

            // 2. Repetition loops — same 2-5 word phrase repeated 4+ times
            val loop = detectRepetitionLoop(text)
            if (loop != null) {
                score += 0.7
                reasons.add("loop(\"${loop.take(40)}…\")")
            }

            // 3. Hallucinated apologies / refusals
            val lower = text.lowercase()
            val hallucinations = listOf(
                "i cannot transcribe", "i am unable to", "as an ai",
                "sorry, i", "i can't help with",
            )
            val hit = hallucinations.firstOrNull { it in lower }
            if (hit != null) {
                score += 0.8
                reasons.add("hallucinated(\"$hit\")")
            }

            // 4. Unterminated final sentence — only when the last character
            // before whitespace is a real letter (not a number/symbol).
            if (charCount > 30) {
                val last = text.lastOrNull()
                if (last != null && last.isLetter() &&
                    !text.endsWith(".") && !text.endsWith("!") &&
                    !text.endsWith("?") && !text.endsWith("…")
                ) {
                    score += 0.15
                    reasons.add("no-terminal-punct")
                }
            }

            return ChunkConfidence(score = score.coerceAtMost(1.0), reasons = reasons)
        }

        /** Find any 2-5 word sequence repeated 4+ times back-to-back. */
        private fun detectRepetitionLoop(text: String): String? {
            val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.size < 8) return null
            for (ngram in 2..5) {
                if (ngram * 4 > words.size) continue
                for (start in 0..(words.size - ngram * 4)) {
                    val pattern = words.subList(start, start + ngram)
                    var matches = 1
                    var cursor = start + ngram
                    while (cursor + ngram <= words.size) {
                        if (words.subList(cursor, cursor + ngram) == pattern) {
                            matches++
                            cursor += ngram
                        } else break
                    }
                    if (matches >= 4) return pattern.joinToString(" ")
                }
            }
            return null
        }
    }
}
