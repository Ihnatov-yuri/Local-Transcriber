package nl.ihnatov.transcriber.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class WhisperWordGroupingTest {

    private fun group(
        tokens: List<ByteArray>,
        times: List<Pair<Long, Long>>,
        probs: List<Float>,
        segStart: Double = 0.0,
        segEnd: Double = 10.0,
    ) = WhisperWordGrouping.group(
        tokens.toTypedArray(),
        times.flatMap { listOf(it.first, it.second) }.toLongArray(),
        probs.toFloatArray(),
        segStart,
        segEnd,
    )

    private fun b(s: String) = s.toByteArray(Charsets.UTF_8)

    @Test
    fun `a leading space starts a new word and pieces plus punctuation attach`() {
        val words = group(
            tokens = listOf(b(" Hel"), b("lo"), b(","), b(" world"), b(".")),
            times = listOf(0L to 20L, 20L to 40L, 40L to 45L, 60L to 100L, 100L to 105L),
            probs = listOf(0.9f, 0.9f, 0.9f, 0.5f, 0.5f),
        )
        assertEquals(listOf("Hello,", "world."), words.map { it.text })
        assertEquals(0.0, words[0].start, 1e-9)
        assertEquals(0.45, words[0].end, 1e-9)
        assertEquals(0.6, words[1].start, 1e-9)
        assertEquals(1.05, words[1].end, 1e-9)
    }

    @Test
    fun `confidence is the geometric mean of the token probabilities`() {
        val words = group(
            tokens = listOf(b(" ab"), b("cd")),
            times = listOf(0L to 10L, 10L to 20L),
            probs = listOf(0.9f, 0.4f),
        )
        assertEquals(sqrt(0.9 * 0.4).toFloat(), words.single().confidence!!, 1e-5f)
    }

    @Test
    fun `a multi-byte character split across two tokens decodes intact`() {
        // "Привіт" with the 2-byte 'и' cut in half between tokens — what
        // whisper's byte-level BPE actually does to Cyrillic.
        val full = b(" Привіт")
        val cut = 1 + 2 + 2 + 1 // space, П, р, first byte of и
        val words = group(
            tokens = listOf(full.copyOfRange(0, cut), full.copyOfRange(cut, full.size)),
            times = listOf(0L to 30L, 30L to 60L),
            probs = listOf(0.8f, 0.8f),
        )
        assertEquals("Привіт", words.single().text)
    }

    @Test
    fun `a dangling partial sequence is dropped rather than emitted as a replacement char`() {
        val partial = b(" так").let { it + b("и").copyOfRange(0, 1) }
        val words = group(listOf(partial), listOf(0L to 50L), listOf(0.7f))
        assertEquals("так", words.single().text)
    }

    @Test
    fun `word times are clamped inside the segment and never inverted`() {
        val words = group(
            tokens = listOf(b(" early"), b(" late")),
            times = listOf(0L to 150L, 900L to 800L),
            probs = listOf(1f, 1f),
            segStart = 1.0,
            segEnd = 5.0,
        )
        assertEquals(1.0, words[0].start, 1e-9)
        assertEquals(1.5, words[0].end, 1e-9)
        assertEquals(5.0, words[1].start, 1e-9)
        assertTrue(words[1].end >= words[1].start)
    }

    @Test
    fun `zero probability does not produce NaN or zero the word`() {
        val words = group(listOf(b(" x"), b("y")), listOf(0L to 1L, 1L to 2L), listOf(0f, 1f))
        val c = words.single().confidence!!
        assertTrue(c > 0f && c < 1f)
    }

    @Test
    fun `empty input and whitespace-only tokens give no words`() {
        assertTrue(group(emptyList(), emptyList(), emptyList()).isEmpty())
        assertTrue(group(listOf(b(" "), b("")), listOf(0L to 1L, 1L to 2L), listOf(1f, 1f)).isEmpty())
    }
}
