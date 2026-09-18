package nl.ihnatov.transcriber.data

import nl.ihnatov.transcriber.data.TranscriptDiff.Op
import nl.ihnatov.transcriber.data.TranscriptDiff.Span
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptDiffTest {

    private fun words(s: String) = TranscriptDiff.tokenize(s)

    /** Replaying the ops must reproduce both inputs — the invariant every path (LCS, anchors, fallback) has to hold. */
    private fun assertRoundTrip(old: List<String>, new: List<String>, ops: List<Span>) {
        assertEquals(old, ops.filter { it.op != Op.Added }.map { it.text })
        assertEquals(new, ops.filter { it.op != Op.Removed }.map { it.text })
    }

    @Test
    fun `identical texts produce no changes`() {
        val r = TranscriptDiff.diff("one two three", "one two three")
        assertTrue(r.identical)
        assertEquals(listOf(listOf(Span("one two three", Op.Same))), r.paragraphs)
    }

    @Test
    fun `substitution insertion and deletion are word level`() {
        val r = TranscriptDiff.diff(
            "we met kaiko at the old office yesterday",
            "we met Kyko at the office yesterday afternoon",
        )
        assertEquals(
            listOf(
                Span("we met", Op.Same),
                Span("kaiko", Op.Removed),
                Span("Kyko", Op.Added),
                Span("at the", Op.Same),
                Span("old", Op.Removed),
                Span("office yesterday", Op.Same),
                Span("afternoon", Op.Added),
            ),
            r.paragraphs.single(),
        )
        assertEquals(2, r.addedWords)
        assertEquals(2, r.removedWords)
    }

    @Test
    fun `case and punctuation differences are not changes and new spelling wins`() {
        val r = TranscriptDiff.diff("hello world how are you", "Hello, world. How are you?")
        assertTrue(r.identical)
        assertEquals("Hello, world. How are you?", r.paragraphs.single().single().text)
    }

    @Test
    fun `empty sides`() {
        assertEquals(listOf(Span("a", Op.Added), Span("b", Op.Added)), TranscriptDiff.diffTokens(emptyList(), words("a b")))
        assertEquals(listOf(Span("a", Op.Removed)), TranscriptDiff.diffTokens(words("a"), emptyList()))
        assertTrue(TranscriptDiff.diff("", "").paragraphs.isEmpty())
    }

    @Test
    fun `anchor path aligns around unique words when the table budget is exceeded`() {
        val old = words("x x x alpha y y y beta z z z")
        val new = words("q q alpha r r r r beta s s")
        // maxCells = 1 forces the patience-anchor path for every non-trivial gap.
        val ops = TranscriptDiff.diffTokens(old, new, maxCells = 1)
        assertRoundTrip(old, new, ops)
        assertEquals(listOf("alpha", "beta"), ops.filter { it.op == Op.Same }.map { it.text })
    }

    @Test
    fun `crossing anchors keep only an increasing chain`() {
        val old = words("p p alpha p beta p p")
        val new = words("q beta q q alpha q")
        val ops = TranscriptDiff.diffTokens(old, new, maxCells = 1)
        assertRoundTrip(old, new, ops)
        assertEquals(1, ops.count { it.op == Op.Same })
    }

    @Test
    fun `no anchors and over budget falls back to replace`() {
        val old = words("a a a a")
        val new = words("b b b")
        val ops = TranscriptDiff.diffTokens(old, new, maxCells = 1)
        assertEquals(List(4) { Span("a", Op.Removed) } + List(3) { Span("b", Op.Added) }, ops)
    }

    @Test
    fun `two hour transcript stays within budget and finds the edits`() {
        val rnd = java.util.Random(7)
        val vocab = List(3000) { "w$it" }
        val old = List(20_000) { vocab[rnd.nextInt(vocab.size)] }.toMutableList()
        // Sprinkle unique words so both sides share anchors, as real speech does (names, numbers).
        for (i in 0 until 20_000 step 50) old[i] = "unique$i"
        val new = old.toMutableList()
        for (i in 25 until 20_000 step 100) new[i] = "changed$i"
        val started = System.nanoTime()
        val ops = TranscriptDiff.diffTokens(old, new, maxCells = 250_000)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertRoundTrip(old, new, ops)
        assertEquals(200, ops.count { it.op == Op.Added })
        assertEquals(200, ops.count { it.op == Op.Removed })
        assertTrue("took ${elapsedMs}ms", elapsedMs < 10_000)
    }

    @Test
    fun `paragraphs never split inside a change`() {
        val ops = listOf(
            Span("a", Op.Same), Span("b", Op.Same),
            Span("c", Op.Removed), Span("d", Op.Added),
            Span("e", Op.Same), Span("f", Op.Same),
        )
        val paras = TranscriptDiff.paragraphs(ops, wordsPerParagraph = 3)
        assertEquals(
            listOf(
                listOf(Span("a b", Op.Same), Span("c", Op.Removed), Span("d", Op.Added), Span("e", Op.Same)),
                listOf(Span("f", Op.Same)),
            ),
            paras,
        )
    }
}
