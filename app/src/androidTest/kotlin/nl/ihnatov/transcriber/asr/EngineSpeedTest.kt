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
 * Real-time factor of each installed offline engine on `files/test_long.wav`
 * (16 kHz mono 16-bit, ~10 min) — the source of the README's speed table.
 * Logs under tag `EngineSpeed`; engines without a model are skipped.
 */
@RunWith(AndroidJUnit4::class)
class EngineSpeedTest {

    @Test
    fun realTimeFactor() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TranscriberApplication
        val factory = app.container.asrFactory
        val wav = File(app.filesDir, "test_long.wav")
        assumeTrue("no test wav", wav.exists())
        val bytes = wav.readBytes()
        val sb = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = FloatArray(sb.remaining()) { sb.get(it) / 32768f }
        val audioSec = samples.size / 16_000.0

        var measured = 0
        for (kind in listOf(AsrBackendKind.Parakeet, AsrBackendKind.Omnilingual, AsrBackendKind.WhisperCpp)) {
            val model = factory.resolveModel(kind, listOf("uk")) ?: continue
            val backend = factory.create(kind)
            try {
                val t0 = System.nanoTime()
                assertTrue(backend.load(model.absolutePath).isSuccess)
                val t1 = System.nanoTime()
                val segs = backend.transcribe(samples, 16_000, language = "uk", translate = false).getOrThrow()
                val t2 = System.nanoTime()
                val runSec = (t2 - t1) / 1e9
                android.util.Log.i("EngineSpeed", "${kind.name} model=${model.name} audio=${"%.0f".format(audioSec)}s " +
                    "load=${"%.1f".format((t1 - t0) / 1e9)}s run=${"%.1f".format(runSec)}s " +
                    "rtf=${"%.1f".format(audioSec / runSec)}x segments=${segs.size} words=${segs.sumOf { it.words?.size ?: 0 }}")
                android.util.Log.i("EngineSpeed", "${kind.name} first words: " +
                    segs.firstOrNull()?.words?.take(8)?.joinToString(" | ") { "${it.text}@${"%.2f".format(it.start)}" })
                measured++
            } finally {
                backend.release()
            }
        }
        assertTrue("at least one engine measured", measured > 0)
    }
}
