package nl.ihnatov.transcriber.data

/**
 * Word-level diff between two transcripts — backs the History sheet's
 * COMPARE action (Phase 4 of the 2026-09 plan). Pure Kotlin, no Android
 * types, so it unit-tests on the JVM.
 *
 * Two engines rarely segment the same audio the same way, so a
 * line-by-line pre-pass would mark nearly every line "changed" and buy
 * nothing. Instead the whole word stream is diffed, kept tractable by:
 *
 *  1. trimming the common prefix/suffix,
 *  2. running the classic LCS table only when `n·m` fits [maxCells],
 *  3. otherwise splitting on patience anchors (words that occur exactly
 *     once on each side, kept in longest-increasing order) and recursing
 *     into the gaps — a 2-hour, ~20k-word transcript never allocates a
 *     20k × 20k table,
 *  4. and, for a gap that is still too big and has no anchors, giving up
 *     on alignment for that gap only (all removed, then all added).
 *
 * Words are matched on a normalized key (lowercased, punctuation
 * stripped) so "Hello," vs "hello" isn't reported as a change — engines
 * disagree on punctuation and casing constantly, and highlighting that
 * would bury the real word differences. The displayed token for a match
 * is the NEW side's spelling.
 */
object TranscriptDiff {

    enum class Op { Same, Added, Removed }

    data class Span(val text: String, val op: Op)

    data class Result(
        /** Runs of same-op words, grouped into display paragraphs. */
        val paragraphs: List<List<Span>>,
        val addedWords: Int,
        val removedWords: Int,
    ) {
        val identical: Boolean get() = addedWords == 0 && removedWords == 0
    }

    /** Default LCS budget: 4M ints (16 MB) per table, freed between gaps. */
    const val DEFAULT_MAX_CELLS = 4_000_000

    fun tokenize(text: String): List<String> =
        text.split(WHITESPACE).filter { it.isNotEmpty() }

    fun diff(
        old: String,
        new: String,
        maxCells: Int = DEFAULT_MAX_CELLS,
        wordsPerParagraph: Int = 80,
    ): Result {
        val ops = diffTokens(tokenize(old), tokenize(new), maxCells)
        return Result(
            paragraphs = paragraphs(ops, wordsPerParagraph),
            addedWords = ops.count { it.op == Op.Added },
            removedWords = ops.count { it.op == Op.Removed },
        )
    }

    /** One [Span] per word, in reading order. */
    fun diffTokens(
        old: List<String>,
        new: List<String>,
        maxCells: Int = DEFAULT_MAX_CELLS,
    ): List<Span> {
        val a = Array(old.size) { normalize(old[it]) }
        val b = Array(new.size) { normalize(new[it]) }
        val out = ArrayList<Span>(maxOf(old.size, new.size))
        Worker(old, new, a, b, maxCells.coerceAtLeast(1), out).run(0, old.size, 0, new.size)
        return out
    }

    private fun normalize(token: String): String {
        val k = token.lowercase().filter { it.isLetterOrDigit() }
        return k.ifEmpty { token }
    }

    private class Worker(
        val old: List<String>,
        val new: List<String>,
        val a: Array<String>,
        val b: Array<String>,
        val maxCells: Int,
        val out: MutableList<Span>,
    ) {
        fun run(aLo0: Int, aHi0: Int, bLo0: Int, bHi0: Int) {
            var aLo = aLo0
            var aHi = aHi0
            var bLo = bLo0
            var bHi = bHi0
            while (aLo < aHi && bLo < bHi && a[aLo] == b[bLo]) {
                out += Span(new[bLo], Op.Same)
                aLo++
                bLo++
            }
            var suffix = 0
            while (aLo < aHi && bLo < bHi && a[aHi - 1] == b[bHi - 1]) {
                aHi--
                bHi--
                suffix++
            }
            val n = aHi - aLo
            val m = bHi - bLo
            when {
                n == 0 || m == 0 -> emitReplace(aLo, aHi, bLo, bHi)
                n.toLong() * m.toLong() <= maxCells -> lcs(aLo, aHi, bLo, bHi)
                else -> {
                    val anchors = anchors(aLo, aHi, bLo, bHi)
                    if (anchors.isEmpty()) {
                        emitReplace(aLo, aHi, bLo, bHi)
                    } else {
                        var pa = aLo
                        var pb = bLo
                        for ((ia, ib) in anchors) {
                            run(pa, ia, pb, ib)
                            out += Span(new[ib], Op.Same)
                            pa = ia + 1
                            pb = ib + 1
                        }
                        run(pa, aHi, pb, bHi)
                    }
                }
            }
            for (i in 0 until suffix) out += Span(new[bHi + i], Op.Same)
        }

        private fun emitReplace(aLo: Int, aHi: Int, bLo: Int, bHi: Int) {
            for (i in aLo until aHi) out += Span(old[i], Op.Removed)
            for (j in bLo until bHi) out += Span(new[j], Op.Added)
        }

        private fun lcs(aLo: Int, aHi: Int, bLo: Int, bHi: Int) {
            val n = aHi - aLo
            val m = bHi - bLo
            val w = m + 1
            // t[i*w + j] = LCS length of a[aLo+i ..] and b[bLo+j ..]
            val t = IntArray((n + 1) * w)
            for (i in n - 1 downTo 0) {
                for (j in m - 1 downTo 0) {
                    t[i * w + j] = if (a[aLo + i] == b[bLo + j]) {
                        t[(i + 1) * w + j + 1] + 1
                    } else {
                        maxOf(t[(i + 1) * w + j], t[i * w + j + 1])
                    }
                }
            }
            var i = 0
            var j = 0
            while (i < n && j < m) {
                when {
                    a[aLo + i] == b[bLo + j] -> {
                        out += Span(new[bLo + j], Op.Same)
                        i++
                        j++
                    }
                    t[(i + 1) * w + j] >= t[i * w + j + 1] -> out += Span(old[aLo + i++], Op.Removed)
                    else -> out += Span(new[bLo + j++], Op.Added)
                }
            }
            while (i < n) out += Span(old[aLo + i++], Op.Removed)
            while (j < m) out += Span(new[bLo + j++], Op.Added)
        }

        /** Patience anchors: (indexInA, indexInB) of words unique on both sides, as the longest run increasing in both. */
        private fun anchors(aLo: Int, aHi: Int, bLo: Int, bHi: Int): List<Pair<Int, Int>> {
            val countA = HashMap<String, Int>()
            for (i in aLo until aHi) countA[a[i]] = (countA[a[i]] ?: 0) + 1
            val posB = HashMap<String, Int>() // -1 = seen more than once
            for (j in bLo until bHi) posB[b[j]] = if (posB.containsKey(b[j])) -1 else j
            val pairs = ArrayList<Pair<Int, Int>>()
            for (i in aLo until aHi) {
                if (countA[a[i]] != 1) continue
                val j = posB[a[i]] ?: continue
                if (j >= 0) pairs += i to j
            }
            if (pairs.isEmpty()) return pairs
            // pairs are increasing in A already; longest increasing subsequence on B (patience sort).
            val tails = IntArray(pairs.size) // index into pairs of each pile's top
            val prev = IntArray(pairs.size) { -1 }
            var len = 0
            for (p in pairs.indices) {
                val jb = pairs[p].second
                var lo = 0
                var hi = len
                while (lo < hi) {
                    val mid = (lo + hi) ushr 1
                    if (pairs[tails[mid]].second < jb) lo = mid + 1 else hi = mid
                }
                if (lo > 0) prev[p] = tails[lo - 1]
                tails[lo] = p
                if (lo == len) len++
            }
            val result = ArrayList<Pair<Int, Int>>(len)
            var p = tails[len - 1]
            while (p >= 0) {
                result += pairs[p]
                p = prev[p]
            }
            result.reverse()
            return result
        }
    }

    /** Merge per-word spans into same-op runs, broken into paragraphs of roughly [wordsPerParagraph] words. */
    internal fun paragraphs(ops: List<Span>, wordsPerParagraph: Int): List<List<Span>> {
        val size = wordsPerParagraph.coerceAtLeast(1)
        val out = ArrayList<List<Span>>()
        var start = 0
        while (start < ops.size) {
            var end = minOf(start + size, ops.size)
            // Don't cut through a change: extend until the boundary is
            // between two unchanged words (bounded so a wholly-rewritten
            // transcript still paginates).
            val limit = minOf(start + size * 2, ops.size)
            while (end < limit && (ops[end - 1].op != Op.Same || ops[end].op != Op.Same)) end++
            val para = ArrayList<Span>()
            var runOp = ops[start].op
            val sb = StringBuilder()
            for (k in start until end) {
                val s = ops[k]
                if (s.op != runOp) {
                    para += Span(sb.toString(), runOp)
                    sb.setLength(0)
                    runOp = s.op
                }
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(s.text)
            }
            para += Span(sb.toString(), runOp)
            out += para
            start = end
        }
        return out
    }

    private val WHITESPACE = Regex("\\s+")
}
