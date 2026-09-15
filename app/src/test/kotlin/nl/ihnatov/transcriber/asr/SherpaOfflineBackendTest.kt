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

class InMemoryCutPointSilenceTest {
    @org.junit.Test
    fun `a 25 ms dip inside speech does not attract the cut`() {
        // 40 s of loud "speech" with a single 25 ms quiet window at 29.9 s
        // (never a qualifying ≥250 ms silence) — the cut must land at the
        // nominal 30 s target, not snap to the dip.
        val sr = 16_000
        val n = 40 * sr
        val samples = FloatArray(n) { i -> if (i % 2 == 0) 0.3f else -0.3f }
        val dipStart = (29.9 * sr).toInt()
        for (i in dipStart until dipStart + sr * 25 / 1000) samples[i] = 0f
        val cuts = SherpaOfflineBackend.computeInMemoryCutPoints(samples, sr)
        val firstEnd = cuts.first().endSample.toDouble() / sr
        org.junit.Assert.assertEquals(30.0, firstEnd, 0.03)
    }

    @org.junit.Test
    fun `a real 300 ms silence inside the flex window attracts the cut`() {
        val sr = 16_000
        val n = 40 * sr
        val samples = FloatArray(n) { i -> if (i % 2 == 0) 0.3f else -0.3f }
        val silStart = (28.0 * sr).toInt()
        for (i in silStart until silStart + sr * 300 / 1000) samples[i] = 0f
        val cuts = SherpaOfflineBackend.computeInMemoryCutPoints(samples, sr)
        val firstEnd = cuts.first().endSample.toDouble() / sr
        org.junit.Assert.assertEquals(28.125, firstEnd, 0.05)
    }
}
