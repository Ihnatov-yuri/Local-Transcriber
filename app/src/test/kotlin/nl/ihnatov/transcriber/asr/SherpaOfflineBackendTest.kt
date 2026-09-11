package nl.ihnatov.transcriber.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaOfflineBackendTest {

    private val sampleRate = 16_000

    private fun silentThenLoud(totalSec: Double, silenceAtSec: Double, silenceDurationSec: Double = 1.0): FloatArray {
        val n = (totalSec * sampleRate).toInt()
        val samples = FloatArray(n) { 0.2f } // "loud" throughout by default
        val silenceStart = (silenceAtSec * sampleRate).toInt()
        val silenceEnd = ((silenceAtSec + silenceDurationSec) * sampleRate).toInt().coerceAtMost(n)
        for (i in silenceStart until silenceEnd) samples[i] = 0f
        return samples
    }

    @Test
    fun `short audio under the target is a single cut`() {
        val samples = FloatArray((10.0 * sampleRate).toInt())
        val cuts = SherpaOfflineBackend.computeInMemoryCutPoints(samples, sampleRate)
        assertEquals(1, cuts.size)
        assertEquals(0, cuts.single().startSample)
        assertEquals(samples.size, cuts.single().endSample)
    }

    @Test
    fun `empty audio returns no cuts`() {
        assertEquals(0, SherpaOfflineBackend.computeInMemoryCutPoints(FloatArray(0), sampleRate).size)
    }

    @Test
    fun `cuts cover the whole buffer with no gaps or overlaps`() {
        val samples = silentThenLoud(totalSec = 95.0, silenceAtSec = 28.0)
        val cuts = SherpaOfflineBackend.computeInMemoryCutPoints(samples, sampleRate)
        assertTrue("expected multiple chunks for a 95s buffer", cuts.size > 1)
        assertEquals(0, cuts.first().startSample)
        assertEquals(samples.size, cuts.last().endSample)
        for (i in 1 until cuts.size) {
            assertEquals("cut ${i - 1}->$i must be contiguous", cuts[i - 1].endSample, cuts[i].startSample)
        }
    }

    @Test
    fun `a long silence-free stretch still gets cut near the target`() {
        // No silence anywhere -> hard cut at/near the 30s target, never
        // growing unboundedly.
        val samples = FloatArray((70.0 * sampleRate).toInt()) { 0.3f }
        val cuts = SherpaOfflineBackend.computeInMemoryCutPoints(samples, sampleRate)
        assertTrue(cuts.size >= 2)
        val firstCutSec = cuts.first().endSample.toDouble() / sampleRate
        assertTrue("first cut ($firstCutSec s) should land near the 30s target", firstCutSec in 25.0..35.0)
    }
}
