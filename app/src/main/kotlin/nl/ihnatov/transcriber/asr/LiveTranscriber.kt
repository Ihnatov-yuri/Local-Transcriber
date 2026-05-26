package nl.ihnatov.transcriber.asr

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.ihnatov.transcriber.audio.WavRecorder

/**
 * Streaming transcription during recording. Engine-agnostic: pass
 * [AsrBackendKind.WhisperCpp] (tiny) or [AsrBackendKind.Gemma4]. For Gemma 4
 * the chunk transcription path automatically picks the user-installed
 * `.litertlm` (largest if multiple) and reuses the same prompt machinery as
 * file transcription, so the user-editable prompts in Settings apply here too.
 *
 * After [start], the worker collects [WavRecorder.chunks] and emits transcribed
 * segments to [events]. Each chunk's start time is preserved.
 *
 * Threading: chunks arrive on the recorder's IO thread; we hop to a private
 * scope on the Default dispatcher and serialize transcription through a mutex
 * so two chunks can't fight over the same engine.
 *
 * Lifecycle: one instance per Record session — call [start] before recording,
 * [stop] when recording ends.
 */
class LiveTranscriber(
    private val context: Context,
    private val factory: AsrFactory,
    private val promptStore: PromptStore,
    private val backendKind: AsrBackendKind,
    /**
     * Allowed source-language set. Empty = full auto, one = force, multi =
     * constrained auto. Same semantics as the file-transcription path.
     */
    private val languages: List<String>,
) {
    sealed interface Event {
        data object ModelMissing : Event
        data object Loading : Event
        data object Ready : Event
        data class Segment(
            val startSec: Double,
            val endSec: Double,
            val text: String,
        ) : Event
        data class Failed(val reason: String) : Event
    }

    private val _events = MutableSharedFlow<Event>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private val backend: AsrBackend = factory.create(backendKind)
    private val mutex = Mutex()
    private var scope: CoroutineScope? = null
    private var feederJob: Job? = null
    @Volatile private var stopped = false

    /**
     * Tail of the previous live chunk's transcript, carried forward as
     * Gemma continuation context. The file path already does this; the
     * live path used to start every chunk cold, so on short ambiguous
     * chunks Gemma's language detection wobbled (a half-second of
     * Ukrainian could get read as Russian/Polish without prior context).
     * Feeding the last ~200 chars anchors language + named entities
     * across the chunk boundary. Guarded by the mutex (only touched
     * inside processOneChunk).
     */
    private var previousContext: String? = null

    /**
     * Pick the right on-disk model for the chosen backend.
     *
     * - Whisper: prefer a "*tiny*" file specifically. Larger Whisper models
     *   exceed the per-chunk latency budget; the user gets noticeable lag.
     * - Gemma 4: use whatever .litertlm is currently the user-pinned (or
     *   largest installed). Same model file as file transcription, so we
     *   benefit from any improvements there.
     */
    private fun pickModel(): File? = when (backendKind) {
        AsrBackendKind.WhisperCpp ->
            factory.modelsDir().listFiles()
                ?.firstOrNull { it.isFile && it.name.contains("tiny", ignoreCase = true) }
        AsrBackendKind.Gemma4 -> factory.resolveModel(AsrBackendKind.Gemma4)
    }

    fun start(recorder: WavRecorder) {
        if (scope != null) return
        stopped = false
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope = it }
        s.launch {
            val model = pickModel()
            if (model == null) {
                _events.emit(Event.ModelMissing)
                return@launch
            }
            _events.emit(Event.Loading)
            val loaded = backend.load(model.absolutePath)
            if (loaded.isFailure) {
                _events.emit(Event.Failed("Could not load ${model.name}: " +
                    (loaded.exceptionOrNull()?.message ?: "unknown")))
                return@launch
            }
            _events.emit(Event.Ready)

            feederJob = launch {
                recorder.chunks.collect { chunk ->
                    if (stopped) return@collect
                    processOneChunk(chunk)
                }
            }
        }
    }

    private suspend fun processOneChunk(chunk: WavRecorder.Chunk) {
        mutex.withLock {
            if (stopped) return
            val t0 = System.currentTimeMillis()
            // For Gemma, route through transcribeChunk so the full language
            // list (constrained-auto when 2+) is honoured. For Whisper we keep
            // the legacy single-language path — multi-select degrades to auto.
            val res = if (backend is Gemma4Backend) {
                // Local copy so the smart-cast survives crossing into the
                // runCatching lambda. (Smart casts on `val` class properties
                // don't carry into capturing lambdas — local val does.)
                val gemma: Gemma4Backend = backend
                runCatching {
                    listOf(
                        RawSegment(
                            startSeconds = 0.0,
                            endSeconds = chunk.samples.size.toDouble() / WavRecorder.SAMPLE_RATE,
                            text = gemma.transcribeChunk(
                                chunkSamples = chunk.samples,
                                sampleRate = WavRecorder.SAMPLE_RATE,
                                languages = languages,
                                translateTo = null,    // live = always source language
                                previousContext = previousContext,
                            ),
                        )
                    ).filter { it.text.isNotBlank() }
                }
            } else {
                backend.transcribe(
                    samples = chunk.samples,
                    sampleRate = WavRecorder.SAMPLE_RATE,
                    language = languages.singleOrNull(),
                    translate = false,
                    progress = null,
                )
            }
            val took = System.currentTimeMillis() - t0
            res.onSuccess { rawSegs ->
                Log.i(TAG, "${backendKind.name} chunk @${"%.1f".format(chunk.startTimeSeconds)}s -> " +
                    "${rawSegs.size} seg in ${took}ms")
                for (r in rawSegs) {
                    val text = r.text.trim()
                    if (text.isNotEmpty()) {
                        _events.emit(
                            Event.Segment(
                                startSec = chunk.startTimeSeconds + r.startSeconds,
                                endSec = chunk.startTimeSeconds + r.endSeconds,
                                text = text,
                            )
                        )
                    }
                }
                // Carry this chunk's tail forward as continuation context
                // for the next chunk (Gemma only; Whisper ignores it).
                // Anchors language + named entities across the boundary.
                val combined = rawSegs.joinToString(" ") { it.text.trim() }.trim()
                if (combined.isNotEmpty()) {
                    previousContext = combined.takeLast(200)
                }
            }
            res.onFailure {
                _events.emit(Event.Failed("Chunk failed: ${it.message}"))
            }
        }
    }

    suspend fun stop() {
        stopped = true
        feederJob?.cancel()
        feederJob = null
        scope?.cancel()
        scope = null
        backend.release()
    }

    companion object {
        private const val TAG = "LiveTranscriber"
    }
}
