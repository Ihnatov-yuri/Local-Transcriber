package nl.ihnatov.transcriber.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import nl.ihnatov.transcriber.asr.AsrBackendKind
import nl.ihnatov.transcriber.asr.AsrFactory
import nl.ihnatov.transcriber.asr.DiarizationRunner
import nl.ihnatov.transcriber.asr.GemmaSettingsStore
import nl.ihnatov.transcriber.asr.LearnedNamesStore
import nl.ihnatov.transcriber.asr.LiveTranscriber
import nl.ihnatov.transcriber.asr.ModelDownloader
import nl.ihnatov.transcriber.asr.PostProcessor
import nl.ihnatov.transcriber.asr.PresetStore
import nl.ihnatov.transcriber.asr.PromptStore
import nl.ihnatov.transcriber.asr.SnippetStore
import nl.ihnatov.transcriber.asr.TranscriptionJobManager
import nl.ihnatov.transcriber.asr.TranscriptionRunner
import nl.ihnatov.transcriber.asr.UiPrefs
import nl.ihnatov.transcriber.audio.WavRecorder

/**
 * Hand-rolled DI container. One instance per process, owned by
 * [nl.ihnatov.transcriber.TranscriberApplication]. Lazy-init so that
 * Room and the recorder don't run on the application main thread unless
 * something actually asks for them.
 */
class AppContainer(private val appContext: Context) {

    /**
     * Process-lifetime scope for cleanup that must outlive any individual
     * ViewModel. Specifically: tearing down a [nl.ihnatov.transcriber.asr.LiveTranscriber]
     * (and its multi-GB Gemma 4 engine handle) on VM clear.
     *
     * Why this exists: `ViewModel.onCleared` runs AFTER the framework cancels
     * `viewModelScope`, so any `viewModelScope.launch { backend.release() }`
     * issued from `onCleared` is a no-op and the native handle leaks until
     * process death. Routing the release through this scope (which is never
     * cancelled) guarantees the cleanup actually runs.
     *
     * SupervisorJob so one failed release doesn't break later ones.
     */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: AppDatabase by lazy { AppDatabase.build(appContext) }

    val repository: RecordingRepository by lazy {
        RecordingRepository(
            context = appContext,
            recordings = database.recordings(),
            segments = database.segments(),
            outputs = database.outputs(),
            versions = database.transcriptVersions(),
            folders = database.folders(),
            tags = database.tags(),
        )
    }

    val recorder: WavRecorder by lazy { WavRecorder(appContext) }

    /** Copies recordings to a user-chosen folder outside app-private storage. See BackupManager's doc comment for why this exists. */
    val backupManager: BackupManager by lazy { BackupManager(appContext, repository) }

    val promptStore: PromptStore by lazy { PromptStore(appContext) }

    val presetStore: PresetStore by lazy { PresetStore(appContext) }

    val snippetStore: SnippetStore by lazy { SnippetStore(appContext) }

    val gemmaSettings: GemmaSettingsStore by lazy { GemmaSettingsStore(appContext) }

    /** "Learned" vocabulary suggestions (Settings → Learned) — see LearnedNames.kt. */
    val learnedNamesStore: LearnedNamesStore by lazy { LearnedNamesStore(appContext) }

    /**
     * Cross-screen UI preferences. Holds the last-used language picks (so
     * Record's picker carries into Detail) plus the transcript-view toggles
     * (timestamps on/off, prose vs cards).
     */
    val uiPrefs: UiPrefs by lazy { UiPrefs(appContext) }

    val asrFactory: AsrFactory by lazy { AsrFactory(appContext, promptStore, gemmaSettings) }

    val postProcessor: PostProcessor by lazy {
        PostProcessor(appContext, asrFactory, promptStore, presetStore, snippetStore, repository, gemmaSettings)
    }

    val modelDownloader: ModelDownloader by lazy { ModelDownloader(asrFactory) }

    val diarizationRunner: DiarizationRunner by lazy { DiarizationRunner(appContext, uiPrefs) }

    val transcriptionRunner: TranscriptionRunner by lazy {
        TranscriptionRunner(appContext, repository, asrFactory, diarizationRunner, uiPrefs)
    }

    /**
     * Process-lifetime job manager. ViewModels treat this as a singleton:
     * call [TranscriptionJobManager.start] to schedule, observe
     * [TranscriptionJobManager.statuses] to render progress. Jobs survive
     * Detail-screen navigation away — see TranscriptionJobManager.kt for
     * the why.
     */
    val transcriptionJobManager: TranscriptionJobManager by lazy {
        TranscriptionJobManager(
            context = appContext,
            appScope = appScope,
            runner = transcriptionRunner,
            repository = repository,
            pendingTasks = database.pendingTasks(),
        )
    }

    /**
     * New instance per Record session — the VM owns the lifecycle.
     * [backend] picks whether live runs Whisper or Gemma 4.
     * [languages] is the allowed-languages list. Empty = full auto, one =
     * force, multiple = constrained auto.
     */
    fun newLiveTranscriber(
        backend: AsrBackendKind = AsrBackendKind.WhisperCpp,
        languages: List<String> = emptyList(),
    ): LiveTranscriber =
        LiveTranscriber(appContext, asrFactory, promptStore, backend, languages)

    /**
     * Default engine for new transcription jobs. Gemma 4 wins because:
     *   - one model handles transcription + translation + dialect detection
     *   - the user's vocabulary + prompts + style toggles all apply
     *   - on the S24-class hardware this app targets, chunked Gemma matches
     *     Whisper's wall-clock without the Whisper alignment cost
     * Whisper is still available; the user picks it from the engine chip.
     */
    fun defaultBackend(): AsrBackendKind = AsrBackendKind.Gemma4
}
