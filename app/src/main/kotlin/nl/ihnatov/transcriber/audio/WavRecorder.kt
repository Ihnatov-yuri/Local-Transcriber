package nl.ihnatov.transcriber.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Records 16 kHz, mono, 16-bit PCM into a WAV file on disk.
 *
 * Matches Whisper's preferred input format so no resampling is needed
 * downstream. Live RMS is exposed via [level]; elapsed wall time via [elapsedMs].
 *
 * Pause/resume hold the file open and keep elapsed-time accurate.
 *
 * Threading: caller invokes [start]/[pause]/[resume]/[stop] from the main
 * thread (or a ViewModel scope). All AudioRecord I/O runs on the recorder's
 * own coroutine scope on Dispatchers.IO.
 */
class WavRecorder(context: Context) {

    sealed interface State {
        data object Idle : State
        data object Recording : State
        data object Paused : State
        data class Failed(val reason: String) : State
        data class Saved(val path: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()    // 0..1 RMS

    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    /**
     * Audio chunks for live consumers. Each emission carries:
     *   samples — float mono in [-1, 1], `CHUNK_SAMPLES` long
     *   startTimeSeconds — start time of this chunk within the recording
     *
     * Buffer = 4: live transcribers can fall a few seconds behind without
     * dropping audio. If a slow consumer is more than 4 chunks behind we drop
     * the oldest chunk rather than stall the recorder (DROP_OLDEST).
     */
    data class Chunk(val samples: FloatArray, val startTimeSeconds: Double)

    private val _chunks = MutableSharedFlow<Chunk>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val chunks: SharedFlow<Chunk> = _chunks.asSharedFlow()

    private val appContext = context.applicationContext

    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private var record: AudioRecord? = null
    private var raf: RandomAccessFile? = null
    private var outputPath: String? = null
    private var bytesWritten: Long = 0
    private var startRealtimeMs: Long = 0
    private var pausedAccumMs: Long = 0
    private var pausedAtMs: Long = 0
    @Volatile private var paused = false
    @Volatile private var stopRequested = false

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(outputFile: File) {
        if (_state.value is State.Recording || _state.value is State.Paused) return

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            _state.value = State.Failed("AudioRecord.getMinBufferSize returned $minBuf")
            return
        }
        val bufferBytes = minBuf * 4   // ~80 ms at 16 kHz mono int16

        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes,
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            _state.value = State.Failed("AudioRecord failed to initialize")
            return
        }

        outputFile.parentFile?.mkdirs()
        raf = RandomAccessFile(outputFile, "rw").apply {
            setLength(0)
            // Reserve 44 bytes for the WAV header — fill in at stop().
            seek(44)
        }
        outputPath = outputFile.absolutePath
        bytesWritten = 0
        record = rec
        paused = false
        stopRequested = false
        startRealtimeMs = System.currentTimeMillis()
        pausedAccumMs = 0
        pausedAtMs = 0

        rec.startRecording()
        _state.value = State.Recording
        _elapsedMs.value = 0

        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scope = it }
        job = s.launch {
            val buf = ByteArray(bufferBytes)
            // Rolling float buffer for live chunk emission. We pre-size it to one
            // full chunk to avoid allocation in the hot path; the actual slice
            // we emit is always exactly CHUNK_SAMPLES long.
            val chunkBuf = FloatArray(CHUNK_SAMPLES)
            var chunkFill = 0
            // Total mono samples written to the chunk stream so far (across
            // chunks), used to compute each chunk's start time. Recording
            // bytes have a separate counter (bytesWritten) because they go to
            // the WAV header.
            var samplesEmitted = 0L
            while (!stopRequested) {
                if (paused) {
                    // Light yield while paused; AudioRecord stays in STOPPED state.
                    Thread.sleep(20)
                    continue
                }
                val n = try {
                    rec.read(buf, 0, buf.size)
                } catch (_: IllegalStateException) {
                    // AudioRecord released between iterations — stop() ran. Exit
                    // cleanly instead of dying with an uncaught exception that
                    // takes the SupervisorJob with it.
                    break
                }
                if (n > 0) {
                    // The raf can be nulled out from the main thread by stop()
                    // between this iteration's read and write. Snapshot to a
                    // local so the null-check + use are atomic; wrap the I/O
                    // in try/catch so a write to an already-closed file (small
                    // race window inside stop()) doesn't crash the job.
                    val rafLocal = raf
                    if (rafLocal != null) {
                        try {
                            rafLocal.write(buf, 0, n)
                            bytesWritten += n
                        } catch (_: java.io.IOException) {
                            break
                        }
                    } else {
                        break
                    }
                    _level.value = rmsOf(buf, n)
                    _elapsedMs.value = effectiveElapsedMs()

                    // Pack int16 bytes into chunkBuf as float in [-1, 1].
                    // Emit a Chunk whenever the buffer fills.
                    var i = 0
                    val end = n - 1
                    while (i < end) {
                        val lo = buf[i].toInt() and 0xff
                        val hi = buf[i + 1].toInt()
                        val sample = (hi shl 8) or lo
                        val signed = if (sample >= 0x8000) sample - 0x10000 else sample
                        chunkBuf[chunkFill++] = signed / 32768f
                        i += 2
                        if (chunkFill == CHUNK_SAMPLES) {
                            val startSec = samplesEmitted.toDouble() / SAMPLE_RATE
                            samplesEmitted += CHUNK_SAMPLES
                            _chunks.tryEmit(Chunk(chunkBuf.copyOf(), startSec))
                            chunkFill = 0
                        }
                    }
                }
            }
        }
    }

    fun pause() {
        if (_state.value !is State.Recording) return
        paused = true
        record?.stop()
        pausedAtMs = System.currentTimeMillis()
        _state.value = State.Paused
    }

    fun resume() {
        if (_state.value !is State.Paused) return
        pausedAccumMs += System.currentTimeMillis() - pausedAtMs
        pausedAtMs = 0
        paused = false
        record?.startRecording()
        _state.value = State.Recording
    }

    fun stop() {
        stopRequested = true
        val r = record
        try {
            r?.stop()
        } catch (_: IllegalStateException) {
            // Already stopped (paused state); ignore.
        }
        r?.release()
        record = null
        job?.cancel()
        scope?.cancel()
        scope = null
        job = null

        val raFile = raf
        raf = null
        val path = outputPath
        outputPath = null

        if (raFile != null && path != null) {
            try {
                writeWavHeader(raFile, bytesWritten)
                raFile.close()
                _state.value = State.Saved(path)
            } catch (ex: Exception) {
                _state.value = State.Failed("Could not finalize WAV: ${ex.message}")
            }
        } else {
            _state.value = State.Idle
        }
        _level.value = 0f
    }

    fun durationSeconds(): Double {
        val samples = bytesWritten / BYTES_PER_SAMPLE
        return samples.toDouble() / SAMPLE_RATE
    }

    /**
     * Acknowledge a terminal state (Saved/Failed) and return to Idle.
     *
     * The Saved state intentionally lingers after [stop] so the UI can render
     * a brief "Finalizing" pill while downstream code (DB write, navigation,
     * auto-transcribe trigger) runs. Once the VM has acted on Saved, it
     * should call this so the Record screen returns to its resting state —
     * otherwise navigating back to Record later shows a stale "Finalizing"
     * pill forever, even after the navigated-to Detail screen is happily
     * transcribing.
     */
    fun acknowledgeTerminalState() {
        val s = _state.value
        if (s is State.Saved || s is State.Failed) {
            _state.value = State.Idle
            _elapsedMs.value = 0
        }
    }

    private fun effectiveElapsedMs(): Long {
        val wall = System.currentTimeMillis() - startRealtimeMs
        return (wall - pausedAccumMs).coerceAtLeast(0)
    }

    private fun rmsOf(buf: ByteArray, length: Int): Float {
        if (length <= 0) return 0f
        var sumSq = 0.0
        var i = 0
        val end = length - 1
        while (i < end) {
            val lo = buf[i].toInt() and 0xff
            val hi = buf[i + 1].toInt()
            val sample = (hi shl 8) or lo
            val signed = if (sample >= 0x8000) sample - 0x10000 else sample
            sumSq += (signed * signed).toDouble()
            i += 2
        }
        val n = length / 2
        val rms = sqrt(sumSq / n)
        return min(1f, (rms / 32768.0).toFloat())
    }

    private fun writeWavHeader(raf: RandomAccessFile, audioBytes: Long) {
        val totalDataLen = audioBytes + 36
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(totalDataLen.toInt())
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)                                 // PCM subchunk size
        header.putShort(1)                                // audio format = PCM
        header.putShort(CHANNELS.toShort())
        header.putInt(SAMPLE_RATE)
        header.putInt(SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE) // byte rate
        header.putShort((CHANNELS * BYTES_PER_SAMPLE).toShort()) // block align
        header.putShort((BYTES_PER_SAMPLE * 8).toShort())        // bits per sample
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(audioBytes.toInt())
        raf.seek(0)
        raf.write(header.array())
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNELS = 1
        const val BYTES_PER_SAMPLE = 2

        /**
         * Live chunk size in samples. 5 seconds @ 16 kHz = 80_000 floats.
         * Mac app uses 5 sec too; long enough for whisper-tiny to produce
         * meaningful output, short enough to feel "live."
         */
        const val CHUNK_SECONDS = 5
        const val CHUNK_SAMPLES = SAMPLE_RATE * CHUNK_SECONDS
    }
}
