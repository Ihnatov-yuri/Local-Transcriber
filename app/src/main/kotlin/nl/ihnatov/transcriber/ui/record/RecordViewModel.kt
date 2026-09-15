package nl.ihnatov.transcriber.ui.record

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.ihnatov.transcriber.asr.AsrBackendKind
import nl.ihnatov.transcriber.asr.LiveTranscriber
import nl.ihnatov.transcriber.audio.RecordingService
import nl.ihnatov.transcriber.audio.WavRecorder
import nl.ihnatov.transcriber.data.AppContainer

class RecordViewModel(
    application: Application,
    private val container: AppContainer,
) : AndroidViewModel(application) {

    /** One segment from the live worker. */
    data class LiveLine(val startSec: Double, val text: String)

    data class UiState(
        val state: WavRecorder.State = WavRecorder.State.Idle,
        val level: Float = 0f,
        val elapsedMs: Long = 0,
        val hasMicPermission: Boolean = false,
        val finishedRecordingId: Long? = null,
        /** If set on a finished recording, the Detail screen auto-fires Run. */
        val autoTranscribeRecordingId: Long? = null,
        val autoTranscribe: Boolean = true,
        val liveEnabled: Boolean = true,
        // Gemma 4 is the default live engine — same model as file
        // transcription, so prompts + vocabulary + dialect handling all
        // carry over to the streaming path. Whisper tiny is still a one-tap
        // switch in Options for users who want lower per-chunk latency at
        // the cost of multilingual quality.
        val liveEngine: AsrBackendKind = AsrBackendKind.Gemma4,
        /** Allowed languages. Empty = full auto, one = forced, multi = constrained auto. */
        val liveLanguages: Set<String> = emptySet(),
        val liveStatus: LiveStatus = LiveStatus.Idle,
        val liveLines: List<LiveLine> = emptyList(),
    )

    sealed interface LiveStatus {
        data object Idle : LiveStatus
        data object Loading : LiveStatus
        data object Running : LiveStatus
        /** stop() was called but the engine handle hasn't finished releasing yet — see [stopLive]. */
        data object Stopping : LiveStatus
        data object ModelMissing : LiveStatus
        data class Failed(val reason: String) : LiveStatus
    }

    private val recorder = container.recorder
    private val _finished = MutableStateFlow<Long?>(null)
    private val _autoTranscribe = MutableStateFlow(true)
    private val _liveEnabled = MutableStateFlow(true)
    private val _liveEngine = MutableStateFlow(AsrBackendKind.Gemma4)
    // Seed the live picker from the last picks the user made anywhere in the
    // app (Record or Detail). They both write through to UiPrefs.lastLanguages.
    private val _liveLanguages = MutableStateFlow(container.uiPrefs.lastLanguages.value)
    private val _liveStatus = MutableStateFlow<LiveStatus>(LiveStatus.Idle)
    private val _liveLines = MutableStateFlow<List<LiveLine>>(emptyList())

    /**
     * Combined "live config" subset. Wrapping into a single Flow keeps the
     * outer combine() under its 5-argument inline limit.
     */
    private data class LivePack(
        val enabled: Boolean,
        val engine: AsrBackendKind,
        val languages: Set<String>,
        val status: LiveStatus,
        val lines: List<LiveLine>,
    )

    /** Auto-transcribe pair: (toggle value, finished-id-to-auto-fire). */
    private data class AutoPack(val on: Boolean, val pendingId: Long?)

    val ui: StateFlow<UiState> = combine(
        recorder.state,
        recorder.level,
        recorder.elapsedMs,
        combine(_finished, _autoTranscribe) { fin, auto ->
            AutoPack(on = auto, pendingId = fin?.takeIf { auto })
        },
        combine(_liveEnabled, _liveEngine, _liveLanguages, _liveStatus, _liveLines, ::LivePack),
    ) { st, lvl, el, autoPack, live ->
        UiState(
            state = st,
            level = lvl,
            elapsedMs = el,
            hasMicPermission = micPermitted(),
            finishedRecordingId = _finished.value,   // raw finish id for navigation
            autoTranscribeRecordingId = autoPack.pendingId,
            autoTranscribe = autoPack.on,
            liveEnabled = live.enabled,
            liveEngine = live.engine,
            liveLanguages = live.languages,
            liveStatus = live.status,
            liveLines = live.lines,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    private var liveWorker: LiveTranscriber? = null
    /**
     * The coroutine collecting [LiveTranscriber.events]. Tracked so
     * [stopLive] can cancel it — without that, an in-flight chunk's
     * Failed event lands in [_liveStatus] AFTER we've navigated away,
     * and the user sees "chunk failed" when they come back to Record.
     */
    private var liveEventsJob: Job? = null

    init {
        // When the recorder finishes saving, persist a Recording row and surface
        // its id so the UI can navigate to the detail screen.
        recorder.state.onEach { st ->
            if (st is WavRecorder.State.Saved) {
                val app = getApplication<Application>()
                RecordingService.stop(app)
                // Snapshot the live transcript BEFORE shutting the worker
                // down — stopLive() clears it as part of the "leave the
                // Record screen clean" flow below.
                val liveLines = _liveLines.value
                stopLive(clearTranscript = true)
                val file = File(st.path)
                val title = file.nameWithoutExtension
                val durationSec = recorder.durationSeconds()
                val id = container.repository.create(
                    title = title,
                    audioPath = file.absolutePath,
                    durationSeconds = durationSec,
                )
                // Seed the Detail screen with the live transcript so the user
                // sees text immediately instead of staring at "Transcript will
                // appear after the first run." The main transcribe pass
                // (auto-fired by Detail when autoRun=true) overwrites these
                // with higher-quality output a few seconds later.
                if (liveLines.isNotEmpty()) {
                    val seeded = liveLines.map {
                        nl.ihnatov.transcriber.data.Segment(
                            recordingId = id,
                            startSeconds = it.startSec,
                            endSeconds = it.startSec + 5.0,    // live chunks are ~5s
                            text = it.text,
                        )
                    }
                    container.repository.replaceSegments(id, seeded)
                }
                _finished.value = id
                // Drop the recorder out of its Saved state immediately,
                // before the UI gets a chance to navigate away. Previously
                // we relied on the Record screen's LaunchedEffect to call
                // acknowledgeTerminalState(), but if the user backed out
                // before that effect fired the recorder would stay Saved
                // forever and the next visit to Record would show a stale
                // "Finalizing" pill. Doing it here makes the cleanup
                // tied to the same event that produced the Saved state,
                // not to a downstream UI lifecycle.
                recorder.acknowledgeTerminalState()
            }
        }.launchIn(viewModelScope)
    }

    fun setLiveEnabled(value: Boolean) {
        _liveEnabled.value = value
    }

    fun setAutoTranscribe(value: Boolean) {
        _autoTranscribe.value = value
    }

    fun setLiveEngine(kind: AsrBackendKind) {
        _liveEngine.value = kind
    }

    fun setLiveLanguages(codes: Set<String>) {
        _liveLanguages.value = codes
        // Write through so the Detail screen and the next Record session
        // both default to the same picks. Persists across app restarts.
        container.uiPrefs.setLastLanguages(codes)
    }

    // Permission gate at the top of start() guarantees recorder.start() only
    // runs when RECORD_AUDIO is granted, but lint can't follow that across
    // the launch boundary into the coroutine body. Suppress the false
    // positive at the call-site so a regression to the gate itself still
    // shows up as an explicit lint error elsewhere.
    @android.annotation.SuppressLint("MissingPermission")
    fun start() {
        if (!micPermitted()) return
        if (recorder.state.value is WavRecorder.State.Recording ||
            recorder.state.value is WavRecorder.State.Paused
        ) return
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            RecordingService.start(ctx)
            val dir = File(ctx.filesDir, "recordings").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val out = File(dir, "Recording_$stamp.wav")
            _finished.value = null
            _liveLines.value = emptyList()
            _liveStatus.value = LiveStatus.Idle
            recorder.start(out)
            if (_liveEnabled.value) startLive()
        }
    }

    private fun startLive() {
        if (liveWorker != null) return
        val w = container.newLiveTranscriber(
            backend = _liveEngine.value,
            languages = _liveLanguages.value.toList(),
        ).also { liveWorker = it }
        liveEventsJob = viewModelScope.launch {
            w.events.collect { e ->
                when (e) {
                    LiveTranscriber.Event.Loading -> _liveStatus.value = LiveStatus.Loading
                    LiveTranscriber.Event.Ready -> _liveStatus.value = LiveStatus.Running
                    LiveTranscriber.Event.ModelMissing -> _liveStatus.value = LiveStatus.ModelMissing
                    is LiveTranscriber.Event.Failed -> _liveStatus.value = LiveStatus.Failed(e.reason)
                    is LiveTranscriber.Event.Segment ->
                        _liveLines.value = _liveLines.value + LiveLine(e.startSec, e.text)
                }
            }
        }
        w.start(recorder)
    }

    /**
     * Tear down the live worker.
     *
     * @param clearTranscript when true, also wipes [_liveLines] and forces
     * status back to Idle. Pass true when the recording session is ending —
     * the user is about to navigate to Detail and shouldn't return to a
     * Record screen still displaying stale lines. Pass false if you're
     * just toggling live off mid-session (we'd need to keep their text).
     */
    private fun stopLive(clearTranscript: Boolean = false) {
        val w = liveWorker ?: run {
            // Even with no worker, honour the cleanup contract so a stale
            // Failed status from a previous run gets cleared on stop.
            if (clearTranscript) {
                _liveLines.value = emptyList()
                _liveStatus.value = LiveStatus.Idle
            }
            return
        }
        liveWorker = null
        // Cancel the events collector FIRST — otherwise the in-flight chunk
        // can still emit Event.Failed after we return, painting "chunk
        // failed" onto the Record screen seconds after the user navigated
        // to Detail. The worker's internal scope.cancel() in w.stop() then
        // unblocks the chunk safely; we just don't listen to its dying
        // gasp.
        liveEventsJob?.cancel()
        liveEventsJob = null
        // Flip the UI status immediately to Stopping, not Idle — `w.stop()`
        // may block for up to one chunk's inference (~3 sec for Whisper
        // tiny) waiting on the engine's internal mutex, and jumping
        // straight to Idle hid that in-flight teardown rather than
        // surfacing it. The background cleanup still runs to release the
        // native engine handle; Idle lands once it actually finishes.
        _liveStatus.value = LiveStatus.Stopping
        if (clearTranscript) {
            _liveLines.value = emptyList()
        }
        // Use AppContainer.appScope (process-lifetime) rather than
        // viewModelScope. When this is called from onCleared(), the
        // framework has already cancelled viewModelScope, so a launch
        // there would be a no-op and backend.release() would never run,
        // leaking the ~1.5 GB Gemma engine handle until process death.
        container.appScope.launch {
            w.stop()
            // Guard against a newer session already having moved status
            // past Stopping (e.g. the user tapped live-on again before
            // this teardown finished) — don't stomp on it.
            if (_liveStatus.value == LiveStatus.Stopping) {
                _liveStatus.value = LiveStatus.Idle
            }
        }
    }

    fun pauseResume() {
        when (recorder.state.value) {
            is WavRecorder.State.Recording -> recorder.pause()
            is WavRecorder.State.Paused -> recorder.resume()
            else -> Unit
        }
    }

    fun stop() {
        recorder.stop()
    }

    fun acknowledgeFinished() {
        _finished.value = null
        // recorder.acknowledgeTerminalState() now runs in the recorder.state
        // observer in init {} as soon as Saved lands — so by the time the
        // UI gets here, the recorder is already Idle. We still null out
        // _finished so the navigate effect doesn't re-fire on recomposition.
    }

    override fun onCleared() {
        stopLive()
        super.onCleared()
    }

    private fun micPermitted(): Boolean = ContextCompat.checkSelfPermission(
        getApplication(),
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(
                    modelClass: Class<T>,
                    extras: androidx.lifecycle.viewmodel.CreationExtras,
                ): T {
                    val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                    return RecordViewModel(app, container) as T
                }
            }
    }
}
