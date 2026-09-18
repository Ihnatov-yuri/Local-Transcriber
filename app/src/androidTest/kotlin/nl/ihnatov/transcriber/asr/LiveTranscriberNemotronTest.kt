package nl.ihnatov.transcriber.asr

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import nl.ihnatov.transcriber.TranscriberApplication
import nl.ihnatov.transcriber.audio.WavRecorder
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the real NemotronStream path with speech instead of a microphone
 * (an emulator's mic is silent). Needs the Nemotron model installed in the
 * app's models dir and a 16 kHz mono 16-bit WAV pushed to
 * `files/test_speech.wav`; skipped when either is missing.
 */
@RunWith(AndroidJUnit4::class)
class LiveTranscriberNemotronTest {

    @Test
    fun streamsPartialsEndpointsAndFlushesOnStop() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TranscriberApplication
        val container = app.container
        val wav = File(app.filesDir, "test_speech.wav")
        assumeTrue("no test wav", wav.exists())
        assumeTrue("no Nemotron model", container.asrFactory.listModels(AsrBackendKind.NemotronStream).isNotEmpty())
        val samples = readPcm16(wav)

        val live = container.newLiveTranscriber(AsrBackendKind.NemotronStream, emptyList())
        val frames = MutableSharedFlow<WavRecorder.Chunk>(extraBufferCapacity = 10_000)
        val events = mutableListOf<LiveTranscriber.Event>()
        val scope = CoroutineScope(Dispatchers.Default)
        scope.launch { live.events.collect { synchronized(events) { events += it } } }
        delay(200)
        live.start(emptyFlow(), frames, MutableStateFlow(WavRecorder.State.Recording))
        withTimeout(120_000) { live.events.first { it is LiveTranscriber.Event.Ready || it is LiveTranscriber.Event.Failed } }

        // Speech, then 3 s of silence (-> an endpoint), then the speech
        // again with NO trailing silence (-> only stop()'s flush can return it).
        val t0 = System.currentTimeMillis()
        val audio = samples + FloatArray(3 * WavRecorder.SAMPLE_RATE) + samples
        var off = 0
        while (off + WavRecorder.FRAME_SAMPLES <= audio.size) {
            frames.emit(WavRecorder.Chunk(
                audio.copyOfRange(off, off + WavRecorder.FRAME_SAMPLES),
                off.toDouble() / WavRecorder.SAMPLE_RATE,
            ))
            off += WavRecorder.FRAME_SAMPLES
            delay(100) // real time, like a microphone
        }
        delay(1500)
        val flushed = live.stop()
        scope.cancel()

        val snapshot = synchronized(events) { events.toList() }
        val partials = snapshot.filterIsInstance<LiveTranscriber.Event.Partial>()
        val segments = snapshot.filterIsInstance<LiveTranscriber.Event.Segment>()
        android.util.Log.i("NemotronTest", "audio=${audio.size / 16000.0}s wall=${System.currentTimeMillis() - t0}ms " +
            "partials=${partials.size} segments=${segments.map { "[${it.startSec}-${it.endSec}] ${it.text}" }} flushed=$flushed")
        assertTrue("no Failed events", snapshot.none { it is LiveTranscriber.Event.Failed })
        assertTrue("partials arrive while speaking", partials.size >= 3)
        assertTrue("silence produces an endpointed segment", segments.isNotEmpty())
        assertTrue("stop() returns the trailing utterance", flushed != null && flushed.text.isNotBlank())
        assertTrue("timestamps are ordered", flushed!!.startSec > segments.first().endSec - 0.5)
    }

    private fun readPcm16(f: File): FloatArray {
        val bytes = f.readBytes()
        val bb = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(bb.remaining()) { bb.get(it) / 32768f }
    }
}
