package nl.ihnatov.transcriber.asr

/**
 * ROVER-style two-recognizer word merge, ported from the Mac app's
 * `Transcriberr/ASR/Backends/EnsembleBackend.swift` (`roverMerge`,
 * `diceSimilarity`, `tokenSimilarity`, `joinSurfaces`, `ScoredWord`) —
 * keep in sync if either changes.
 *
 * One thing Android doesn't have yet that the Mac's tuning assumes:
 * real per-word confidence. sherpa-onnx's offline transducer/CTC results
 * have no confidence field (tracked upstream as k2-fsa/sherpa-onnx#3638,
 * still open as of 2026-09-11) and whisper.cpp's JNI shim doesn't surface
 * `whisper_full_get_token_p` yet either — both deferred in Phase 2/3 of
 * the 2026-09 plan. Callers without real confidence should pass 1f for
 * every [ScoredWord.confidence]; the vote then degrades gracefully to
 * being driven entirely by [votePrior] at disputed positions, which is
 * still meaningful. Re-tune once real confidence exists on either side.
 */
data class ScoredWord(
    /** As transcribed, punctuation attached. */
    val surface: String,
    /** Lowercased, letters/digits only — used for alignment. */
    val norm: String,
    val confidence: Float,
) {
    companion object {
        fun of(surface: String, confidence: Float = 1f): ScoredWord =
            ScoredWord(surface, normalize(surface), confidence)

        fun normalize(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }
    }
}

/** One position in a ROVER alignment between two word sequences A and B. */
sealed interface AlignedItem {
    data class Match(val a: ScoredWord, val b: ScoredWord) : AlignedItem
    data class Substitution(val a: ScoredWord, val b: ScoredWord) : AlignedItem
    data class InsertionA(val a: ScoredWord) : AlignedItem
    data class InsertionB(val b: ScoredWord) : AlignedItem
}

object RoverMerge {

    /** Raw confidence floor for a single-engine word to survive the merge as an insertion. */
    private const val INSERTION_FLOOR = 0.55f

    /**
     * Edit-distance (Levenshtein) alignment of two normalized word
     * sequences, O(n·m) — effectively instant on ~100-word chunks. Ties
     * in the DP backtrace prefer a match/substitution over an insertion
     * from either side, matching the Mac's own backtrace order.
     */
    fun align(a: List<ScoredWord>, b: List<ScoredWord>): List<AlignedItem> {
        val n = a.size
        val m = b.size
        if (n == 0 && m == 0) return emptyList()
        if (n == 0) return b.map { AlignedItem.InsertionB(it) }
        if (m == 0) return a.map { AlignedItem.InsertionA(it) }

        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) dp[i][0] = i
        for (j in 0..m) dp[0][j] = j
        for (i in 1..n) {
            for (j in 1..m) {
                val sub = dp[i - 1][j - 1] + if (a[i - 1].norm == b[j - 1].norm) 0 else 1
                dp[i][j] = minOf(sub, dp[i - 1][j] + 1, dp[i][j - 1] + 1)
            }
        }

        var i = n
        var j = m
        val reversed = mutableListOf<AlignedItem>()
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && dp[i][j] == dp[i - 1][j - 1] + (if (a[i - 1].norm == b[j - 1].norm) 0 else 1)) {
                val wa = a[i - 1]
                val wb = b[j - 1]
                reversed.add(if (wa.norm == wb.norm) AlignedItem.Match(wa, wb) else AlignedItem.Substitution(wa, wb))
                i--; j--
            } else if (i > 0 && dp[i][j] == dp[i - 1][j] + 1) {
                reversed.add(AlignedItem.InsertionA(a[i - 1]))
                i--
            } else {
                reversed.add(AlignedItem.InsertionB(b[j - 1]))
                j--
            }
        }
        return reversed.asReversed()
    }

    /**
     * Vote-merge two word sequences: matches keep their word, substitutions
     * keep the reading with higher prior-weighted confidence, and single-
     * engine insertions survive only above [INSERTION_FLOOR] (the prior
     * governs divergent READINGS, not recall).
     */
    fun merge(a: List<ScoredWord>, b: List<ScoredWord>, priorA: Float = 1f, priorB: Float = 1f): String {
        if (a.isEmpty() && b.isEmpty()) return ""
        if (a.isEmpty()) return joinSurfaces(b.map { it.surface })
        if (b.isEmpty()) return joinSurfaces(a.map { it.surface })
        val surfaces = align(a, b).mapNotNull { item ->
            when (item) {
                is AlignedItem.Match -> item.a.surface
                is AlignedItem.Substitution ->
                    if (item.a.confidence * priorA >= item.b.confidence * priorB) item.a.surface else item.b.surface
                is AlignedItem.InsertionA -> item.a.surface.takeIf { item.a.confidence >= INSERTION_FLOOR }
                is AlignedItem.InsertionB -> item.b.surface.takeIf { item.b.confidence >= INSERTION_FLOOR }
            }
        }
        return joinSurfaces(surfaces)
    }

    /**
     * Join word surfaces defensively: collapse stray engine whitespace and
     * attach apostrophe-led fragments to the previous word ("Пам" +
     * "'ятаєш" -> "Пам'ятаєш", not "Пам 'ятаєш").
     */
    fun joinSurfaces(surfaces: Iterable<String>): String {
        val out = StringBuilder()
        for (raw in surfaces) {
            val w = raw.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
            if (w.isEmpty()) continue
            val firstChar = w.first()
            val lastOut = out.lastOrNull()
            if (out.isEmpty()) {
                out.append(w)
            } else if (firstChar in "'’ʼ‘" && w.length > 1 && lastOut != null && lastOut.isLetter()) {
                out.append(w)
            } else {
                out.append(' ').append(w)
            }
        }
        return out.toString()
    }

    /** Dice coefficient over normalized word tokens: 1.0 = same words, 0 = disjoint. Order-insensitive. */
    fun diceSimilarity(ta: List<String>, tb: List<String>): Double {
        val a = ta.filter { it.isNotEmpty() }
        val b = tb.filter { it.isNotEmpty() }
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val counts = HashMap<String, Int>()
        for (t in a) counts[t] = (counts[t] ?: 0) + 1
        var common = 0
        for (t in b) {
            val c = counts[t] ?: 0
            if (c > 0) {
                counts[t] = c - 1
                common++
            }
        }
        return 2.0 * common / (a.size + b.size)
    }

    /** [diceSimilarity] over two plain-text strings, tokenized on non-alphanumeric boundaries. */
    fun tokenSimilarity(a: String, b: String): Double {
        fun tokens(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        return diceSimilarity(tokens(a), tokens(b))
    }

    /** One disputed alignment position, for the constrained-JSON arbitration second pass. */
    data class Dispute(
        val alignedIndex: Int,
        val optionA: String,
        val optionB: String,
        val confidenceA: Float,
        val confidenceB: Float,
    )

    /** Substitution positions only — where the two engines disagree on a reading, not where one has extra words. */
    fun extractDisputes(a: List<ScoredWord>, b: List<ScoredWord>): List<Dispute> =
        align(a, b).withIndex().mapNotNull { (i, item) ->
            (item as? AlignedItem.Substitution)?.let {
                Dispute(i, it.a.surface, it.b.surface, it.a.confidence, it.b.confidence)
            }
        }

    /**
     * Rebuild the merged text from an arbitration second pass: every
     * [AlignedItem.Match]/insertion keeps its word as-is, and every
     * disputed [AlignedItem.Substitution] takes whichever side `choices`
     * picked (0 = A, 1 = B, indexed in the same order as [disputes] —
     * i.e. the same list [extractDisputes] returned for this same (a, b)
     * pair). A dispute with no corresponding choice defaults to A.
     */
    fun applyChoices(
        a: List<ScoredWord>,
        b: List<ScoredWord>,
        disputes: List<Dispute>,
        choices: List<Int>,
    ): String {
        val choiceByAlignedIndex = disputes.withIndex().associate { (i, d) -> d.alignedIndex to (choices.getOrNull(i) ?: 0) }
        val surfaces = align(a, b).withIndex().map { (idx, item) ->
            when (item) {
                is AlignedItem.Match -> item.a.surface
                is AlignedItem.Substitution -> if ((choiceByAlignedIndex[idx] ?: 0) == 0) item.a.surface else item.b.surface
                is AlignedItem.InsertionA -> item.a.surface
                is AlignedItem.InsertionB -> item.b.surface
            }
        }
        return joinSurfaces(surfaces)
    }
}
