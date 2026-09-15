package nl.ihnatov.transcriber.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SuperArbitrationTest {

    @Test
    fun `parses a well-formed choices response`() {
        val result = parseArbitrationResponse("""{"choices": [0, 1, 0]}""", disputeCount = 3)
        assertEquals(listOf(0, 1, 0), result)
    }

    @Test
    fun `pads a short response with 0`() {
        val result = parseArbitrationResponse("""{"choices": [1]}""", disputeCount = 3)
        assertEquals(listOf(1, 0, 0), result)
    }

    @Test
    fun `truncates a long response`() {
        val result = parseArbitrationResponse("""{"choices": [1, 0, 1, 1, 1]}""", disputeCount = 2)
        assertEquals(listOf(1, 0), result)
    }

    @Test
    fun `clamps out-of-range values into 0-1`() {
        val result = parseArbitrationResponse("""{"choices": [5, -3]}""", disputeCount = 2)
        assertEquals(listOf(1, 0), result)
    }

    @Test
    fun `malformed JSON returns null`() {
        assertNull(parseArbitrationResponse("not json at all", disputeCount = 2))
    }

    @Test
    fun `empty choices with disputes pending returns null`() {
        assertNull(parseArbitrationResponse("""{"choices": []}""", disputeCount = 2))
    }

    @Test
    fun `zero disputes returns an empty list even with empty choices`() {
        assertEquals(emptyList<Int>(), parseArbitrationResponse("""{"choices": []}""", disputeCount = 0))
    }
}
