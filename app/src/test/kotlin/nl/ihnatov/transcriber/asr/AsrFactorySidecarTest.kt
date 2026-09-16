package nl.ihnatov.transcriber.asr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrFactorySidecarTest {

    @Test
    fun `LiteRT-LM caches next to the Gemma model are not models`() {
        assertTrue(AsrFactory.isRuntimeSidecar("gemma-4-E2B-it.litertlm_1789564398_2588147712_mldrift_weight_cache.bin"))
        assertTrue(AsrFactory.isRuntimeSidecar("gemma-4-E2B-it.litertlm_1789564398_2588147712_mldrift_program_cache.bin"))
    }

    @Test
    fun `real model files are not sidecars`() {
        assertFalse(AsrFactory.isRuntimeSidecar("ggml-tiny.bin"))
        assertFalse(AsrFactory.isRuntimeSidecar("ggml-large-v3-turbo-q5_0.bin"))
        assertFalse(AsrFactory.isRuntimeSidecar("gemma-4-E2B-it.litertlm"))
        assertFalse(AsrFactory.isRuntimeSidecar("embedding-wespeaker.onnx"))
    }
}
