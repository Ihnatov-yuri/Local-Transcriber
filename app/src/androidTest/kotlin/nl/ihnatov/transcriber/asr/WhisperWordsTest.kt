package nl.ihnatov.transcriber.asr

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import nl.ihnatov.transcriber.TranscriberApplication
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real whisper.cpp through the JNI shim: segments must come back with
 * per-word timestamps and confidences. Needs a `*tiny*` ggml model in the
 * models dir and `files/test_speech.wav` (16 kHz mono 16-bit); skipped otherwise.
 */
@RunWith(AndroidJUnit4::class)
class WhisperWordsTest {

    @Test
    fun segmentsCarryWordsWithConfidence() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TranscriberApplication
        val factory = app.container.asrFactory
        val wav = File(app.filesDir, "test_speech.wav")
        val model = factory.modelsDir().listFiles()?.firstOrNull { it.isFile && it.name.contains("tiny") }
        assumeTrue("no test wav", wav.exists())
        assumeTrue("no whisper tiny model", model != null)
        val bytes = wav.readBytes()
        val sb = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = FloatArray(sb.remaining()) { sb.get(it) / 32768f }

        val backend = factory.create(AsrBackendKind.WhisperCpp)
        try {
            assertTrue(backend.load(model!!.absolutePath).isSuccess)
            val segs = backend.transcribe(samples, 16_000, language = "uk", translate = false).getOrThrow()
            android.util.Log.i("WhisperWordsTest", segs.joinToString("\n") { s ->
                "[${s.startSeconds}-${s.endSeconds}] ${s.text} :: " +
                    s.words?.joinToString(" ") { "${it.text}(${"%.2f".format(it.confidence)}@${"%.2f".format(it.start)})" }
            })
            assertTrue(segs.isNotEmpty())
            val words = segs.flatMap { it.words.orEmpty() }
            assertTrue("words present", words.size >= 5)
            assertTrue("confidence in (0,1]", words.all { (it.confidence ?: 0f) > 0f && it.confidence!! <= 1f })
            assertTrue("word times ordered and inside the clip", words.zipWithNext().all { (a, b) -> a.start <= b.start + 1e-6 } &&
                words.all { it.start >= 0.0 && it.end <= samples.size / 16_000.0 + 0.5 })
        } finally {
            backend.release()
        }
    }
}
