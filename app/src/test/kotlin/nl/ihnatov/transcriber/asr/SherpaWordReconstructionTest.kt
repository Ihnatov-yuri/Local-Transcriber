package nl.ihnatov.transcriber.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class SherpaWordReconstructionTest {
    private val backend = SherpaOfflineBackend(AsrBackendKind.Parakeet)

    private fun words(vararg tokens: String): List<Word> {
        val ts = FloatArray(tokens.size) { it * 0.1f }
        val dur = FloatArray(tokens.size) { 0.1f }
        return backend.reconstructWords(arrayOf(*tokens), ts, dur, offsetSec = 10.0)
    }

    @Test
    fun `leading space starts a word - the shape sherpa-onnx actually returns`() {
        val w = words(" Ти", " пи", "тай", ",", " що")
        assertEquals(listOf("Ти", "питай,", "що"), w.map { it.text })
        assertEquals(10.1, w[1].start, 1e-6)
        assertEquals(10.4, w[1].end, 1e-6)
    }

    @Test
    fun `sentencepiece marker starts a word too`() {
        assertEquals(listOf("hello", "world"), words("▁hel", "lo", "▁world").map { it.text })
    }

    @Test
    fun `bare marker token splits without producing an empty word`() {
        assertEquals(listOf("a", "b"), words("a", " ", "b").map { it.text })
    }
}
