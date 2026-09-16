package nl.ihnatov.transcriber.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuperPairsTest {

    @Test
    fun `only pairs whose engines are both installed are offered`() {
        val installed = setOf(AsrBackendKind.Parakeet, AsrBackendKind.Gemma4)
        val presets = SuperPairs.presets(installed)
        assertEquals(listOf(AsrBackendKind.Parakeet to AsrBackendKind.Gemma4), presets)
        assertTrue(SuperPairs.isReady(presets.single(), installed))
    }

    @Test
    fun `preferred order is kept when several pairs are installed`() {
        val installed = setOf(
            AsrBackendKind.Parakeet, AsrBackendKind.WhisperCpp, AsrBackendKind.Gemma4,
        )
        val presets = SuperPairs.presets(installed)
        assertEquals(AsrBackendKind.Parakeet to AsrBackendKind.WhisperCpp, presets.first())
        assertTrue(presets.contains(AsrBackendKind.Parakeet to AsrBackendKind.Gemma4))
        assertTrue(presets.contains(AsrBackendKind.WhisperCpp to AsrBackendKind.Gemma4))
        assertEquals(3, presets.size)
    }

    @Test
    fun `falls back to the full list when no pair is complete`() {
        val presets = SuperPairs.presets(setOf(AsrBackendKind.Parakeet))
        assertEquals(SuperPairs.PREFERRED, presets)
        assertFalse(SuperPairs.isReady(presets.first(), setOf(AsrBackendKind.Parakeet)))
    }

    @Test
    fun `no pair repeats an engine or uses the streaming-only one`() {
        for ((a, b) in SuperPairs.PREFERRED) {
            assertTrue(a != b)
            assertTrue(a != AsrBackendKind.NemotronStream && b != AsrBackendKind.NemotronStream)
        }
    }
}
