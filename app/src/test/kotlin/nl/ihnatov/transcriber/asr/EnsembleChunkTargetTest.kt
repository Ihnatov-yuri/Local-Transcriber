package nl.ihnatov.transcriber.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Super-mode chunker must never hand Gemma a slice longer than one Gemma inference. */
class EnsembleChunkTargetTest {

    private val sampleRate = 16_000

    @Test
    fun `a Gemma-sized target keeps every chunk within one Gemma call`() {
        val target = Gemma4Backend.CHUNK_SECONDS - SherpaOfflineBackend.FLEX_SEC
        // 3 minutes of silence-free "speech": only hard cuts, the worst case for chunk length.
        val samples = FloatArray((180.0 * sampleRate).toInt()) { 0.3f }
        val cuts = SherpaOfflineBackend.computeInMemoryCutPoints(samples, sampleRate, target)
        assertTrue(cuts.size >= 6)
        for (c in cuts) {
            val sec = (c.endSample - c.startSample).toDouble() / sampleRate
            assertTrue("chunk of $sec s exceeds Gemma's ${Gemma4Backend.CHUNK_SECONDS} s", sec <= Gemma4Backend.CHUNK_SECONDS + 1e-6)
        }
        assertEquals(0, cuts.first().startSample)
        assertEquals(samples.size, cuts.last().endSample)
    }

    @Test
    fun `the default target is unchanged for non-Gemma pairs`() {
        val samples = FloatArray((70.0 * sampleRate).toInt()) { 0.3f }
        val byDefault = SherpaOfflineBackend.computeInMemoryCutPoints(samples, sampleRate)
        val explicit = SherpaOfflineBackend.computeInMemoryCutPoints(samples, sampleRate, SherpaOfflineBackend.TARGET_CHUNK_SEC)
        assertEquals(byDefault, explicit)
    }
}
