package nl.ihnatov.transcriber.asr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkConfidenceTest {

    @Test
    fun `clean well-punctuated text scores low`() {
        val c = ChunkConfidence.assess(
            "This is a normal sentence with proper punctuation.",
            audioDurationSeconds = 10.0,
        )
        assertFalse(c.isLow)
    }

    @Test
    fun `very short output for long non-silent audio is flagged`() {
        val c = ChunkConfidence.assess("Um.", audioDurationSeconds = 20.0)
        assertTrue(c.isLow)
        assertTrue(c.reasons.any { it.startsWith("too-short") })
    }

    @Test
    fun `a repeated phrase loop is flagged`() {
        val looped = (1..6).joinToString(" ") { "the the" }
        val c = ChunkConfidence.assess(looped, audioDurationSeconds = 10.0)
        assertTrue(c.isLow)
        assertTrue(c.reasons.any { it.startsWith("loop") })
    }

    @Test
    fun `a hallucinated refusal is flagged`() {
        val c = ChunkConfidence.assess("I'm sorry, I cannot transcribe this audio.", audioDurationSeconds = 10.0)
        assertTrue(c.isLow)
        assertTrue(c.reasons.any { it.startsWith("hallucinated") })
    }

    @Test
    fun `missing terminal punctuation on a long segment is a small penalty only`() {
        val c = ChunkConfidence.assess(
            "This is a reasonably long sentence that just trails off without punctuation",
            audioDurationSeconds = 10.0,
        )
        assertTrue(c.reasons.contains("no-terminal-punct"))
        assertFalse("a single missing-punctuation signal alone shouldn't cross the low threshold", c.isLow)
    }

    @Test
    fun `score never exceeds 1_0`() {
        val looped = (1..10).joinToString(" ") { "no no" }
        val c = ChunkConfidence.assess("I'm sorry, I cannot transcribe this: $looped", audioDurationSeconds = 20.0)
        assertTrue(c.score <= 1.0)
    }
}
