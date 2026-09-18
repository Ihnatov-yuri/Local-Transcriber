package nl.ihnatov.transcriber.asr

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import nl.ihnatov.transcriber.audio.WavRecorder

/**
 * Streaming transcription during recording. Engine-agnostic across
 * [AsrBackendKind.WhisperCpp], [AsrBackendKind.Gemma4],
 * [AsrBackendKind.Parakeet], and [AsrBackendKind.Omnilingual] — all four
 * route through the normal [AsrBackend] chunk-transcribe seam, treating
 * each ~5s [WavRecorder.Chunk] as an independent short utterance the same
 * way file transcription already chunks long files. [AsrBackendKind.Gemma4]
 * additionally picks the user-installed `.litertlm` (largest if multiple)
 * and reuses the same prompt machinery as file transcription, so the
 * user-editable prompts in Settings apply here too.
 *
 * [AsrBackendKind.NemotronStream] is different in kind, not degree: it's a
 * genuine streaming model (cache-aware transducer), so instead of
 * re-decoding independent chunks it keeps ONE [OnlineStream] alive across
 * the whole recording, feeding it 100 ms [WavRecorder.frames] via
 * [OnlineStream.acceptWaveform], emitting a replaceable [Event.Partial] as
 * the hypothesis grows and a final segment when sherpa-onnx's own endpoint
 * detector fires — see [processNemotronFrame]. It bypasses
 * [AsrBackend] entirely (there's no offline backend for it — see
 * [AsrFactory.create]'s doc), so [backend] is null for this kind.
 *
 * Parakeet/Omnilingual are much bigger than Whisper-tiny (487/293 MB vs a
 * few dozen), so expect noticeably more load time and per-chunk latency
 * live than the tiny-only default — that's a real tradeoff of picking them
 * here, not a bug.
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
        /** Carries [kind] (== the enclosing [backendKind]) so a UI that shows
         *  this alongside a separately-mutable "current engine" selection
         *  doesn't drift onto a different engine's name if that selection
         *  changes after this event fires — see RecordScreen.kt's LastHeardBlock. */
        data class ModelMissing(val kind: AsrBackendKind) : Event
        data object Loading : Event
        data object Ready : Event
        data class Segment(
            val startSec: Double,
            val endSec: Double,
            val text: String,
        ) : Event
        /**
         * The current, still-growing hypothesis for the utterance that
         * started at [startSec] — replaces the previous Partial rather than
         * appending. Superseded by the [Segment] that closes the utterance.
         * Streaming engines only.
         */
        data class Partial(val startSec: Double, val text: String) : Event
        data class Failed(val reason: String) : Event
    }

    /** True for the one engine that keeps a persistent decoder stream and flushes on [stop]. */
    val isStreaming: Boolean get() = backendKind == AsrBackendKind.NemotronStream

    private val _events = MutableSharedFlow<Event>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<Event> = _events.asSharedFlow()

    // null for NemotronStream — AsrFactory.create() throws for it by design
    // (see that function's doc); the Nemotron path below never touches
    // this field.
    private val backend: AsrBackend? =
        if (backendKind == AsrBackendKind.NemotronStream) null else factory.create(backendKind)
    private val mutex = Mutex()
    private var scope: CoroutineScope? = null
    private var feederJob: Job? = null
    @Volatile private var stopped = false

    // NemotronStream-only state. One recognizer + one stream live for the
    // whole recording (unlike the chunk backends, which reload per Record
    // session but not per chunk either — the difference here is the STREAM
    // itself carries decoder state across chunks, which is the whole point
    // of a streaming model).
    private var onlineRecognizer: OnlineRecognizer? = null
    private var onlineStream: OnlineStream? = null

    /** Start time of the current not-yet-endpointed utterance, or null between utterances. */
    private var nemotronUtteranceStartSec: Double? = null

    /** End time of the last frame fed to the stream — end timestamp for endpoints and the [stop] flush. */
    private var nemotronLastChunkEndSec: Double = 0.0

    /** Last [Event.Partial] text sent, so an unchanged hypothesis isn't re-emitted 10x a second. */
    private var nemotronLastPartial: String = ""

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
     * - Everything else: whatever's user-pinned (or largest/best-match
     *   installed) via the normal [AsrFactory.resolveModel] resolution —
     *   same file/directory file transcription would pick, so improvements
     *   there (or an explicit pin from the Detail screen's RUN sheet)
     *   benefit live too. For [AsrBackendKind.Parakeet]/[AsrBackendKind.Omnilingual]/
     *   [AsrBackendKind.NemotronStream] this resolves the model DIRECTORY
     *   (encoder/decoder/joiner or model + tokens.txt), not a single file.
     */
    private fun pickModel(): File? = when (backendKind) {
        AsrBackendKind.WhisperCpp ->
            factory.modelsDir().listFiles()
                ?.firstOrNull { it.isFile && it.name.contains("tiny", ignoreCase = true) }
        AsrBackendKind.Gemma4, AsrBackendKind.Parakeet, AsrBackendKind.Omnilingual,
        AsrBackendKind.NemotronStream -> factory.resolveModel(backendKind, languages)
    }

    fun start(recorder: WavRecorder) = start(recorder.chunks, recorder.frames, recorder.state)

    /** The audio seam behind [start] — instrumented tests feed a WAV through it instead of a microphone. */
    internal fun start(
        chunks: Flow<WavRecorder.Chunk>,
        frames: Flow<WavRecorder.Chunk>,
        recorderState: Flow<WavRecorder.State>,
    ) {
        if (scope != null) return
        stopped = false
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope = it }
        s.launch {
            val model = pickModel()
            if (model == null) {
                _events.emit(Event.ModelMissing(backendKind))
                return@launch
            }
            _events.emit(Event.Loading)
            val loaded = if (isStreaming) {
                // Mutex-protected — stop()'s Nemotron cleanup path
                // (flushAndReleaseNemotron) takes this same lock. Without
                // it here, a rapid start-then-stop races loadNemotron's
                // writes to onlineRecognizer/onlineStream against
                // flushAndReleaseNemotron's. The `stopped` re-check inside
                // the lock covers the other ordering: stop() already ran
                // its cleanup (saw nulls, no-opped) while this coroutine
                // was still in pickModel() — neither Mutex.lock()'s fast
                // path nor a DROP_OLDEST emit checks for cancellation, so
                // without it loadNemotron would build a ~475 MB recognizer
                // nothing ever releases.
                mutex.withLock {
                    if (stopped) return@launch
                    loadNemotron(model)
                }
            } else {
                requireNotNull(backend) { "backend is only null for NemotronStream" }.load(model.absolutePath)
            }
            if (stopped) {
                // Same race for the chunk engines: stop()'s release() ran
                // before load() finished, so release again now.
                if (!isStreaming) withContext(NonCancellable) { backend?.release() }
                return@launch
            }
            if (loaded.isFailure) {
                _events.emit(Event.Failed("Could not load ${model.name}: " +
                    (loaded.exceptionOrNull()?.message ?: "unknown")))
                return@launch
            }
            _events.emit(Event.Ready)

            feederJob = launch {
                if (isStreaming) {
                    // A pause splices pre-pause audio straight onto
                    // post-resume audio with no silence between them, so
                    // close the open utterance at the pause instead of
                    // letting one segment span it.
                    launch {
                        recorderState.collect { st ->
                            if (st is WavRecorder.State.Paused) {
                                mutex.withLock { if (!stopped) finalizeNemotronUtterance() }
                            }
                        }
                    }
                    frames.collect { frame ->
                        if (stopped) return@collect
                        mutex.withLock { if (!stopped) processNemotronFrame(frame) }
                    }
                } else {
                    chunks.collect { chunk ->
                        if (stopped) return@collect
                        processOneChunk(chunk)
                    }
                }
            }
        }
    }

    /**
     * Build the [OnlineRecognizer] for Nemotron 3.5 from its model
     * directory. Same directory shape as [SherpaOfflineBackend]'s Parakeet
     * path, resolved via the shared
     * [SherpaOfflineBackend.findTransducerFiles] (see
     * [AsrFactory.isCompleteModelDir]). Endpoint rule values are
     * sherpa-onnx's own published defaults (verified against
     * `k2-fsa/sherpa-onnx`'s `OnlineRecognizer.kt` and the Android demo's
     * `getEndpointConfig()`), spelled out explicitly rather than relied on
     * as constructor defaults so this doesn't silently drift on an AAR bump.
     */
    private fun loadNemotron(dir: File): Result<Unit> = try {
        val files = SherpaOfflineBackend.findTransducerFiles(dir)
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = WavRecorder.SAMPLE_RATE, featureDim = 80, dither = 0f),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = files.encoder.absolutePath,
                    decoder = files.decoder.absolutePath,
                    joiner = files.joiner.absolutePath,
                ),
                tokens = files.tokens.absolutePath,
                numThreads = 2,
                provider = "cpu",
            ),
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(mustContainNonSilence = false, minTrailingSilence = 2.4f, minUtteranceLength = 0f),
                rule2 = EndpointRule(mustContainNonSilence = true, minTrailingSilence = 1.4f, minUtteranceLength = 0f),
                rule3 = EndpointRule(mustContainNonSilence = false, minTrailingSilence = 0f, minUtteranceLength = 20f),
            ),
            enableEndpoint = true,
        )
        onlineRecognizer?.release()
        val recognizer = OnlineRecognizer(null, config)
        onlineRecognizer = recognizer
        onlineStream?.release()
        onlineStream = recognizer.createStream().also(::applyNemotronLanguage)
        nemotronUtteranceStartSec = null
        Result.success(Unit)
    } catch (t: CancellationException) {
        throw t
    } catch (t: Throwable) {
        Result.failure(t)
    }

    private suspend fun processOneChunk(chunk: WavRecorder.Chunk) {
        mutex.withLock {
            if (stopped) return
            // Shadows the nullable class property with a non-null local —
            // safe because NemotronStream (the only kind backend is null
            // for) never reaches this function; see start().
            val backend = requireNotNull(backend) { "backend is only null for NemotronStream" }
            val t0 = System.currentTimeMillis()
            // For Gemma, route through transcribeChunk so the full language
            // list (constrained-auto when 2+) is honoured. For Whisper we keep
            // the legacy single-language path — multi-select degrades to auto.
            val res = if (backend is Gemma4Backend) {
                // Local copy so the smart-cast survives crossing into the
                // runCatching lambda. (Smart casts on `val` class properties
                // don't carry into capturing lambdas — local val does.)
                val gemma: Gemma4Backend = backend
                runCatchingCancellable {
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

    /**
     * Nemotron picks its language per stream at runtime, not at model
     * load. Only forced when exactly one language is selected — the model
     * has no constrained-auto mode, so 0 or 2+ picks stay on its own auto
     * detection. An unsupported code is logged natively and falls back to
     * auto rather than failing.
     */
    private fun applyNemotronLanguage(stream: OnlineStream) {
        val code = languages.singleOrNull() ?: return
        runCatching { stream.setOption("language", code) }
            .onFailure { Log.w(TAG, "NemotronStream setOption(language=$code) failed", it) }
    }

    /**
     * Nemotron's streaming path: keep decoding into the SAME [OnlineStream]
     * across every [WavRecorder.frames] slice (that's what makes it
     * "streaming" rather than a batch of independent chunk transcriptions,
     * unlike the other engines above). After each 100 ms frame the current
     * hypothesis goes out as an [Event.Partial] — a replaceable line, so
     * the UI tracks speech well under a second behind — and only once
     * sherpa-onnx's own endpoint detector decides the utterance is done
     * does it become an immutable [Event.Segment]. Called with [mutex] held.
     */
    private suspend fun processNemotronFrame(frame: WavRecorder.Chunk) {
        val recognizer = onlineRecognizer ?: return
        val stream = onlineStream ?: return
        val frameEndSec = frame.startTimeSeconds + frame.samples.size.toDouble() / WavRecorder.SAMPLE_RATE
        try {
            if (nemotronUtteranceStartSec == null) nemotronUtteranceStartSec = frame.startTimeSeconds
            stream.acceptWaveform(frame.samples, WavRecorder.SAMPLE_RATE)
            nemotronLastChunkEndSec = frameEndSec
            var decoded = false
            while (recognizer.isReady(stream)) {
                recognizer.decode(stream)
                decoded = true
                // Cancellation checkpoint so stop()'s mutex-guarded cleanup
                // waits behind a single decode() call, not the whole loop.
                yield()
            }
            if (recognizer.isEndpoint(stream)) {
                finalizeNemotronUtterance()
            } else if (decoded) {
                val text = recognizer.getResult(stream).text.cleanSpaces()
                if (text.isNotEmpty() && text != nemotronLastPartial) {
                    nemotronLastPartial = text
                    _events.emit(Event.Partial(nemotronUtteranceStartSec ?: frame.startTimeSeconds, text))
                }
            }
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            Log.e(TAG, "NemotronStream frame failed", t)
            _events.emit(Event.Failed("Chunk failed: ${t.message}"))
            // Recover instead of leaving the stream permanently wedged for
            // the rest of the session — every other engine's chunks are
            // independent, so a transient failure there self-heals on the
            // next chunk; Nemotron's ONE persistent stream needs an
            // explicit reset to get the same property. If reset() itself
            // throws (the stream/recognizer is unrecoverably broken), fall
            // back to releasing and nulling the fields so the next frame
            // cleanly no-ops via the `?: return` guards above instead of
            // repeatedly hitting the same broken native object.
            val recovered = runCatching { recognizer.reset(stream); applyNemotronLanguage(stream) }
            nemotronUtteranceStartSec = null
            nemotronLastPartial = ""
            if (recovered.isFailure) {
                runCatching { stream.release() }
                runCatching { recognizer.release() }
                onlineStream = null
                onlineRecognizer = null
            }
        }
    }

    /**
     * Close the open utterance: emit what's been decoded so far as a final
     * [Event.Segment] and reset the stream for the next one. Called with
     * [mutex] held — on a detected endpoint and when recording pauses.
     */
    private suspend fun finalizeNemotronUtterance() {
        val recognizer = onlineRecognizer ?: return
        val stream = onlineStream ?: return
        val text = recognizer.getResult(stream).text.cleanSpaces()
        val startSec = nemotronUtteranceStartSec ?: nemotronLastChunkEndSec
        recognizer.reset(stream)
        // Not verified whether reset() keeps per-stream options; reapplying
        // is harmless if it does.
        applyNemotronLanguage(stream)
        nemotronUtteranceStartSec = null
        nemotronLastPartial = ""
        if (text.isNotEmpty()) {
            Log.i(TAG, "NemotronStream endpoint @${"%.1f".format(startSec)}s")
            _events.emit(Event.Segment(startSec = startSec, endSec = nemotronLastChunkEndSec, text = text))
        }
    }

    /**
     * Tear everything down. For [isStreaming] sessions this also flushes
     * any not-yet-endpointed trailing speech and RETURNS it, rather than
     * emitting it to [events]: the caller is about to stop listening, and
     * an emit from here only queues the collector's resumption — a caller
     * on Dispatchers.Main.immediate would cancel that collector and read
     * its own state before the queued event ever ran. Null for the chunk
     * engines and when there was nothing left to flush.
     *
     * Runs NonCancellable so a caller that dies mid-stop (ViewModel
     * cleared) can't strand a loaded native engine, and off the caller's
     * thread — the flush decode and native release are not main-thread work.
     */
    suspend fun stop(): Event.Segment? = withContext(NonCancellable + Dispatchers.Default) {
        stopped = true
        feederJob?.cancel()
        feederJob = null
        scope?.cancel()
        scope = null
        if (isStreaming) {
            mutex.withLock { flushAndReleaseNemotron() }
        } else {
            backend?.release()
            null
        }
    }

    /**
     * Flush any not-yet-endpointed trailing speech (e.g. the user tapped
     * Stop mid-utterance, before a natural pause) via
     * [OnlineStream.inputFinished] so the last few words of a live session
     * aren't silently dropped, then tear down the recognizer/stream —
     * Nemotron has no [AsrBackend] for [stop] to call [AsrBackend.release] on.
     */
    private fun flushAndReleaseNemotron(): Event.Segment? {
        val recognizer = onlineRecognizer
        val stream = onlineStream
        try {
            if (recognizer != null && stream != null) {
                stream.inputFinished()
                while (recognizer.isReady(stream)) recognizer.decode(stream)
                val text = recognizer.getResult(stream).text.cleanSpaces()
                if (text.isNotEmpty()) {
                    val endSec = nemotronLastChunkEndSec
                    return Event.Segment(startSec = nemotronUtteranceStartSec ?: endSec, endSec = endSec, text = text)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "NemotronStream flush failed", t)
        } finally {
            runCatching { stream?.release() }
            runCatching { recognizer?.release() }
            onlineStream = null
            onlineRecognizer = null
            nemotronUtteranceStartSec = null
        }
        return null
    }

    /** Nemotron joins sentences with a double space. */
    private fun String.cleanSpaces(): String = trim().replace(MULTI_SPACE, " ")

    /** [runCatching] that doesn't eat cancellation. */
    private inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (t: CancellationException) {
        throw t
    } catch (t: Throwable) {
        Result.failure(t)
    }

    companion object {
        private const val TAG = "LiveTranscriber"
        private val MULTI_SPACE = Regex("\\s{2,}")
    }
}
