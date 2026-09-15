package nl.ihnatov.transcriber.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class AsrBackendTest {

    @Test
    fun `defaultEngineFor picks Parakeet when Arabic is not selected`() {
        assertEquals(AsrBackendKind.Parakeet, defaultEngineFor(emptySet()))
        assertEquals(AsrBackendKind.Parakeet, defaultEngineFor(setOf("en")))
        assertEquals(AsrBackendKind.Parakeet, defaultEngineFor(setOf("uk", "nl")))
    }

    @Test
    fun `defaultEngineFor picks Omnilingual whenever Arabic is selected`() {
        assertEquals(AsrBackendKind.Omnilingual, defaultEngineFor(setOf("ar")))
        assertEquals(AsrBackendKind.Omnilingual, defaultEngineFor(setOf("ar", "en")))
    }
}
