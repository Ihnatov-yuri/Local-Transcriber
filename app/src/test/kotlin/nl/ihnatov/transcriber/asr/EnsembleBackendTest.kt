package nl.ihnatov.transcriber.asr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnsembleBackendTest {

    @Test
    fun `shouldBench is false below the wedge limit`() {
        assertFalse(EnsembleBackend.shouldBench(wedgeCount = 1, maxWedges = 2))
    }

    @Test
    fun `shouldBench is true at the wedge limit`() {
        assertTrue(EnsembleBackend.shouldBench(wedgeCount = 2, maxWedges = 2))
    }

    @Test
    fun `shouldBench stays true past the wedge limit`() {
        assertTrue(EnsembleBackend.shouldBench(wedgeCount = 5, maxWedges = 2))
    }

    @Test
    fun `votePrior defaults to neutral 1f for every pairing`() {
        assertEqualsFloat(1f, EnsembleBackend.votePrior(AsrBackendKind.Parakeet, "uk"))
        assertEqualsFloat(1f, EnsembleBackend.votePrior(AsrBackendKind.WhisperCpp, null))
        assertEqualsFloat(1f, EnsembleBackend.votePrior(AsrBackendKind.Gemma4, "ar"))
    }

    private fun assertEqualsFloat(expected: Float, actual: Float) {
        org.junit.Assert.assertEquals(expected, actual, 1e-6f)
    }
}
