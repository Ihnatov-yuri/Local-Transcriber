package nl.ihnatov.transcriber.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoverMergeTest {

    private fun sw(text: String, confidence: Float = 1f) = ScoredWord.of(text, confidence)
    private fun words(vararg s: String) = s.map { sw(it) }

    // ---- alignment ----

    @Test
    fun `identical sequences align as all matches`() {
        val a = words("the", "quick", "fox")
        val b = words("the", "quick", "fox")
        val trace = RoverMerge.align(a, b)
        assertEquals(3, trace.size)
        assertTrue(trace.all { it is AlignedItem.Match })
    }

    @Test
    fun `a single differing word aligns as one substitution`() {
        val a = words("the", "quick", "fox")
        val b = words("the", "slow", "fox")
        val trace = RoverMerge.align(a, b)
        assertEquals(3, trace.size)
        assertTrue(trace[0] is AlignedItem.Match)
        assertTrue(trace[1] is AlignedItem.Substitution)
        assertTrue(trace[2] is AlignedItem.Match)
    }

    @Test
    fun `an extra word in B aligns as an insertion`() {
        val a = words("the", "fox")
        val b = words("the", "quick", "fox")
        val trace = RoverMerge.align(a, b)
        assertEquals(3, trace.size)
        assertTrue(trace[1] is AlignedItem.InsertionB)
    }

    @Test
    fun `empty A aligns as all insertions from B`() {
        val trace = RoverMerge.align(emptyList(), words("a", "b"))
        assertEquals(2, trace.size)
        assertTrue(trace.all { it is AlignedItem.InsertionB })
    }

    // ---- merge / vote ----

    @Test
    fun `merge on agreement returns the shared text`() {
        val a = words("the", "quick", "fox")
        val b = words("the", "quick", "fox")
        assertEquals("the quick fox", RoverMerge.merge(a, b))
    }

    @Test
    fun `merge picks the higher-confidence reading at a substitution`() {
        val a = listOf(sw("the", 1f), sw("OWASP", 0.9f), sw("ten", 1f))
        val b = listOf(sw("the", 1f), sw("overas", 0.4f), sw("ten", 1f))
        assertEquals("the OWASP ten", RoverMerge.merge(a, b))
    }

    @Test
    fun `merge respects vote priors over raw confidence`() {
        val a = listOf(sw("word", 0.9f))  // higher raw confidence...
        val b = listOf(sw("verb", 0.5f))
        // ...but B's engine has a much higher prior for this language, so B wins.
        assertEquals("verb", RoverMerge.merge(a, b, priorA = 0.5f, priorB = 1f))
    }

    @Test
    fun `insertion above the floor survives, below it is dropped`() {
        val a = listOf(sw("hello", 1f), sw("extra", 0.7f))
        val b = listOf(sw("hello", 1f))
        assertEquals("hello extra", RoverMerge.merge(a, b))

        val a2 = listOf(sw("hello", 1f), sw("junk", 0.2f))
        val b2 = listOf(sw("hello", 1f))
        assertEquals("hello", RoverMerge.merge(a2, b2))
    }

    @Test
    fun `merge with one empty side returns the other side verbatim`() {
        assertEquals("hello world", RoverMerge.merge(words("hello", "world"), emptyList()))
        assertEquals("hello world", RoverMerge.merge(emptyList(), words("hello", "world")))
    }

    // ---- joinSurfaces ----

    @Test
    fun `joinSurfaces attaches a leading apostrophe fragment to the previous word`() {
        assertEquals("Пам'ятаєш", RoverMerge.joinSurfaces(listOf("Пам", "'ятаєш")))
    }

    @Test
    fun `joinSurfaces collapses stray internal whitespace in one surface`() {
        assertEquals("hello world", RoverMerge.joinSurfaces(listOf("hello", " world ")))
    }

    // ---- similarity ----

    @Test
    fun `diceSimilarity is 1 for identical token sets`() {
        assertEquals(1.0, RoverMerge.diceSimilarity(listOf("a", "b"), listOf("a", "b")), 1e-9)
    }

    @Test
    fun `diceSimilarity is 0 for disjoint token sets`() {
        assertEquals(0.0, RoverMerge.diceSimilarity(listOf("a", "b"), listOf("c", "d")), 1e-9)
    }

    @Test
    fun `tokenSimilarity ignores punctuation and case`() {
        assertEquals(1.0, RoverMerge.tokenSimilarity("Hello, World!", "hello world"), 1e-9)
    }

    // ---- dispute extraction ----

    @Test
    fun `extractDisputes returns only substitution positions`() {
        val a = listOf(sw("the"), sw("OWASP"), sw("ten"), sw("extra"))
        val b = listOf(sw("the"), sw("overas"), sw("ten"))
        val disputes = RoverMerge.extractDisputes(a, b)
        assertEquals(1, disputes.size)
        assertEquals("OWASP", disputes.single().optionA)
        assertEquals("overas", disputes.single().optionB)
    }

    @Test
    fun `extractDisputes is empty for fully agreeing sequences`() {
        assertTrue(RoverMerge.extractDisputes(words("a", "b"), words("a", "b")).isEmpty())
    }

    // ---- applyChoices ----

    @Test
    fun `applyChoices picks option A when choice is 0`() {
        val a = words("the", "OWASP", "ten")
        val b = words("the", "overas", "ten")
        val disputes = RoverMerge.extractDisputes(a, b)
        assertEquals("the OWASP ten", RoverMerge.applyChoices(a, b, disputes, listOf(0)))
    }

    @Test
    fun `applyChoices picks option B when choice is 1`() {
        val a = words("the", "OWASP", "ten")
        val b = words("the", "overas", "ten")
        val disputes = RoverMerge.extractDisputes(a, b)
        assertEquals("the overas ten", RoverMerge.applyChoices(a, b, disputes, listOf(1)))
    }

    @Test
    fun `applyChoices resolves multiple disputes independently`() {
        val a = words("one", "two", "three", "four")
        val b = words("uno", "two", "tres", "four")
        val disputes = RoverMerge.extractDisputes(a, b)
        assertEquals(2, disputes.size)
        assertEquals("uno two three four", RoverMerge.applyChoices(a, b, disputes, listOf(1, 0)))
    }

    @Test
    fun `applyChoices keeps agreed and inserted words untouched`() {
        val a = words("hello", "big", "world")
        val b = words("hello", "world")
        val disputes = RoverMerge.extractDisputes(a, b)
        assertTrue(disputes.isEmpty())
        assertEquals("hello big world", RoverMerge.applyChoices(a, b, disputes, emptyList()))
    }
}
