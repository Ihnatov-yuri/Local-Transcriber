package nl.ihnatov.transcriber.asr

/**
 * Deterministic disfluency collapse for speech transcripts, ported from
 * the Mac app's `Transcriberr/ASR/TextDestutter.swift` — keep in sync if
 * either changes.
 *
 * Small local LLMs reliably fail to strip stutters from long transcripts
 * no matter how the prompt begs ("for for for for person", "the next the
 * next conversation"), so the collapse happens in code before the text
 * reaches the model. Rules are conservative on purpose:
 *  - immediate word runs of 3+ always collapse to one
 *  - word doubles collapse unless the word is a legitimate English double
 *    ("that that's", "had had", emphasis words)
 *  - immediate phrase repeats of 2-4 words always collapse ("bring in
 *    bring in", "present that present that")
 *  - nothing collapses across a sentence boundary, so intentional repeats
 *    like "Yeah. Yeah." and "Thanks. Thanks." survive
 */
object TextDestutter {

    /** Words that repeat legitimately in fluent English at run length 2. */
    private val LEGIT_DOUBLES = setOf(
        "that", "had", "very", "really", "no", "yes", "yeah", "bye", "ha", "so",
    )

    /**
     * Pure hesitation sounds — dropped outright before stutter collapse
     * (which also lets "how it uh how it" collapse as a phrase echo).
     */
    private val FILLERS = setOf("uh", "um", "erm", "mm", "mhm", "hmm", "mmm")

    fun collapse(text: String): String =
        text.split("\n").joinToString("\n") { collapseLine(it) }

    private fun norm(t: String): String = t.lowercase().trim(',', ';')

    private fun endsSentence(t: String): Boolean =
        t.endsWith(".") || t.endsWith("!") || t.endsWith("?")

    fun collapseLine(line: String): String {
        val rawTokens = line.split(" ").filter { it.isNotEmpty() }
        // Drop fillers, but carry a filler's sentence-ending punctuation
        // back to the previous word — otherwise "Okay, um. Okay" loses its
        // boundary and the stutter pass would merge a deliberate restart.
        val tokens = mutableListOf<String>()
        for (token in rawTokens) {
            val n = token.lowercase().trim(',', ';', '.', '!', '?')
            if (n !in FILLERS) {
                tokens.add(token)
                continue
            }
            if (endsSentence(token) && tokens.isNotEmpty() && !endsSentence(tokens.last()) && token.isNotEmpty()) {
                val punct = token.last()
                var t = tokens.last()
                while (t.isNotEmpty() && (t.last() == ',' || t.last() == ';')) t = t.dropLast(1)
                tokens[tokens.size - 1] = t + punct
            }
            // else: filler dropped outright, no punctuation to carry.
        }
        if (tokens.size <= 1) return tokens.joinToString(" ")

        val out = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            out.add(tokens[i])
            i++

            // Phrase repeats: drop the next n tokens while they echo the n
            // just emitted (longest echo first).
            var collapsed = true
            while (collapsed) {
                collapsed = false
                for (n in 4 downTo 2) {
                    if (out.size < n || i + n > tokens.size) continue
                    val prev = out.takeLast(n)
                    val next = tokens.subList(i, i + n)
                    // A sentence end ANYWHERE in the first copy means the
                    // "echo" starts a new sentence ("Thank you. Thank
                    // you.") — a deliberate repeat, not a stutter.
                    if (prev.map(::norm) != next.map(::norm)) continue
                    if (prev.any(::endsSentence)) continue
                    if (next.dropLast(1).any(::endsSentence)) continue
                    if (!prev.all { norm(it).isNotEmpty() }) continue
                    i += n
                    collapsed = true
                    break
                }
            }

            // Single-word stutter runs. Count repeats of the just-emitted
            // token, never across a sentence end.
            val last = out.last()
            if (endsSentence(last) || norm(last).isEmpty()) continue
            var run = 0
            while (i + run < tokens.size &&
                norm(tokens[i + run]) == norm(last) &&
                (run == 0 || !endsSentence(tokens[i + run - 1]))
            ) {
                run++
            }
            if (run >= 2 || (run == 1 && norm(last) !in LEGIT_DOUBLES)) {
                // Keep the FIRST occurrence — it carries sentence-initial
                // capitalization ("For for for" -> "For"). A repeat
                // carrying .!? can never match norm equality, so no
                // punctuation is lost by dropping the rest.
                i += run
            }
        }
        return out.joinToString(" ")
    }
}
