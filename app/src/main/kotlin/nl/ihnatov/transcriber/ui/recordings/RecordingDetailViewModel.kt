package nl.ihnatov.transcriber.ui.recordings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import nl.ihnatov.transcriber.asr.AsrBackendKind
import nl.ihnatov.transcriber.asr.PostProcessor
import nl.ihnatov.transcriber.asr.PostProcessingPreset
import nl.ihnatov.transcriber.asr.TranscriptionJobManager
import nl.ihnatov.transcriber.data.AppContainer
import nl.ihnatov.transcriber.data.OutputDoc
import nl.ihnatov.transcriber.data.Recording
import nl.ihnatov.transcriber.data.Segment
import nl.ihnatov.transcriber.data.TranscriptVersion

class RecordingDetailViewModel(
    application: Application,
    private val container: AppContainer,
    private val recordingId: Long,
) : AndroidViewModel(application) {

    /**
     * UI mirror of [TranscriptionJobManager.JobStatus] for this recording.
     * Kept as a separate data class so VM consumers don't have to import
     * asr-package types. The mapping is one-to-one in [ui].
     */
    data class JobStatus(
        val running: Boolean = false,
        val waitingForCharger: Boolean = false,
        val queued: Boolean = false,
        val stopping: Boolean = false,
        val stageLabel: String = "",
        val progress: Float = 0f,
        val error: String? = null,
    )

    data class PresetStatus(
        val running: Boolean = false,
        val label: String = "",
        val error: String? = null,
    )

    data class UiState(
        val recording: Recording? = null,
        val segments: List<Segment> = emptyList(),
        val outputs: List<OutputDoc> = emptyList(),
        val presets: List<PostProcessingPreset> = emptyList(),
        val job: JobStatus = JobStatus(),
        /** Map keyed by presetId; running/failed status per preset. */
        val presetStatus: Map<String, PresetStatus> = emptyMap(),
        /** Past transcript snapshots, newest first — see [nl.ihnatov.transcriber.data.RecordingRepository.observeVersions]. */
        val versions: List<TranscriptVersion> = emptyList(),
    ) {
        val running get() = job.running
        val stageLabel get() = job.stageLabel
        val progress get() = job.progress
        val error get() = job.error
    }

    // Job state is no longer owned by this VM — it lives in
    // container.transcriptionJobManager so the job survives navigation away
    // from Detail. We project the manager's per-recording status into
    // this VM's UiState.
    private val job: kotlinx.coroutines.flow.Flow<JobStatus> =
        container.transcriptionJobManager.statuses.map { byId ->
            val s = byId[recordingId] ?: TranscriptionJobManager.JobStatus()
            JobStatus(
                running = s.running,
                waitingForCharger = s.waitingForCharger,
                queued = s.queued,
                stopping = s.stopping,
                stageLabel = s.stageLabel,
                progress = s.progress,
                error = s.error,
            )
        }
    private val presetStatuses = MutableStateFlow<Map<String, PresetStatus>>(emptyMap())

    /** Bundle of the four lower-frequency flows so the outer combine stays under 5 args. */
    private data class DocPack(
        val outputs: List<OutputDoc>,
        val presets: List<PostProcessingPreset>,
        val statuses: Map<String, PresetStatus>,
        val versions: List<TranscriptVersion>,
    )

    val ui: StateFlow<UiState> = combine(
        container.repository.observe(recordingId),
        container.repository.observeSegments(recordingId),
        combine(
            container.repository.observeOutputs(recordingId),
            container.presetStore.presets,
            presetStatuses,
            container.repository.observeVersions(recordingId),
            ::DocPack,
        ),
        job,
    ) { rec, segs, docPack, j ->
        UiState(
            recording = rec,
            segments = segs,
            outputs = docPack.outputs,
            presets = docPack.presets,
            job = j,
            presetStatus = docPack.statuses,
            versions = docPack.versions,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    fun transcribe(
        backend: AsrBackendKind,
        languages: List<String>,
        /**
         * Target translation language code (en/ar/uk/nl). null = transcribe in
         * source language with no translation step.
         */
        translateTo: String?,
        diarize: Boolean = false,
        expectedSpeakers: Int = -1,
        /**
         * When true, the task is parked until the device is on AC power.
         * Lets the user kick off a long Gemma run before going to sleep
         * without burning battery — it'll start once they plug in.
         */
        runOnCharger: Boolean = false,
        /**
         * Hybrid diarization: sherpa-onnx pre-pass for globally consistent
         * speaker clustering + Gemma transcription with hints. See
         * [TranscriptionRunner.run]'s docs for the policy details.
         */
        hybridDiarize: Boolean = false,
        /** Super mode (Phase 3): run [superPairA] + [superPairB] and vote-merge instead of just [backend]. */
        superMode: Boolean = false,
        superPairA: AsrBackendKind? = null,
        superPairB: AsrBackendKind? = null,
        /** Constrained-JSON arbitration second pass on low-agreement Super chunks. */
        maxQuality: Boolean = false,
    ) {
        val rec = ui.value.recording ?: return
        container.transcriptionJobManager.start(
            recordingId = rec.id,
            params = TranscriptionJobManager.Params(
                backend = backend,
                languages = languages,
                translateTo = translateTo,
                diarize = diarize,
                expectedSpeakers = expectedSpeakers,
                hybridDiarize = hybridDiarize,
                superMode = superMode,
                superPairA = superPairA,
                superPairB = superPairB,
                maxQuality = maxQuality,
            ),
            runOnCharger = runOnCharger,
        )
    }

    /** Cancel the in-flight or charger-parked job for this recording. */
    fun cancelTranscription() {
        val rec = ui.value.recording ?: return
        container.transcriptionJobManager.cancel(rec.id)
    }

    /** Dismiss the error banner after the user has read it. */
    fun dismissJobError() {
        val rec = ui.value.recording ?: return
        container.transcriptionJobManager.dismissError(rec.id)
    }

    /**
     * Run a post-processing preset against the current transcript. Emits
     * progress into [presetStatuses] keyed by preset id; the Detail UI shows
     * a per-chip spinner while the run is in flight.
     */
    fun runPreset(presetId: String) {
        val rec = ui.value.recording ?: return
        val segs = ui.value.segments
        if (segs.isEmpty()) {
            updateStatus(presetId) { it.copy(error = "Transcribe the recording first.") }
            return
        }
        val current = presetStatuses.value[presetId]
        if (current?.running == true) return

        viewModelScope.launch {
            updateStatus(presetId) { it.copy(running = true, label = "Loading model", error = null) }
            try {
                container.postProcessor.run(
                    recordingId = rec.id,
                    presetId = presetId,
                    segments = segs,
                    language = rec.sourceLanguage,
                ) { progress ->
                    when (progress) {
                        PostProcessor.Progress.Loading ->
                            updateStatus(presetId) { it.copy(label = "Loading Gemma 4…") }
                        PostProcessor.Progress.Running ->
                            updateStatus(presetId) { it.copy(label = "Generating…") }
                        is PostProcessor.Progress.Done ->
                            updateStatus(presetId) { it.copy(running = false, label = "", error = null) }
                        is PostProcessor.Progress.Failed ->
                            updateStatus(presetId) { it.copy(running = false, error = progress.reason) }
                    }
                }
            } catch (t: Throwable) {
                updateStatus(presetId) { it.copy(running = false, error = t.message ?: "Failed") }
            }
        }
    }

    fun deleteOutput(id: Long) {
        viewModelScope.launch { container.repository.deleteOutput(id) }
    }

    /**
     * Restore a past [TranscriptVersion] as the live transcript. The
     * repository snapshots the current live segments first (see
     * [nl.ihnatov.transcriber.data.RecordingRepository.restoreVersion]),
     * so this is safe to call without its own confirmation snapshot —
     * the state being replaced is never lost.
     */
    fun restoreVersion(versionId: Long) {
        viewModelScope.launch {
            val rec = ui.value.recording ?: return@launch
            val restored = container.repository.restoreVersion(
                versionId = versionId,
                currentEngineId = rec.transcribedWithBackend ?: "unknown",
                currentEngineLabel = rec.transcribedWithModel ?: rec.transcribedWithBackend ?: "unknown",
            ) ?: return@launch
            // Keep the on-disk .txt/.srt/.json sidecars in sync with the
            // restored transcript — same convention as editSegmentText/
            // renameAllByKey below.
            runCatching {
                nl.ihnatov.transcriber.asr.TranscriptExporter.writeSidecars(rec, restored)
            }
        }
    }

    fun deleteVersion(id: Long) {
        viewModelScope.launch { container.repository.deleteVersion(id) }
    }

    private fun updateStatus(
        presetId: String,
        mutate: (PresetStatus) -> PresetStatus,
    ) {
        val curr = presetStatuses.value[presetId] ?: PresetStatus()
        presetStatuses.value = presetStatuses.value + (presetId to mutate(curr))
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            val rec = ui.value.recording ?: return@launch
            // Cancel any in-flight or pending transcription FIRST. Otherwise
            // the runner keeps grinding after the Recording row is gone and
            // its next repository.replaceSegments(recordingId, ...) call
            // throws an FK-violation (the segments table FK to recordings
            // is on-delete-CASCADE, but the runner is mid-flight and tries
            // to insert against a now-vanished parent).
            container.transcriptionJobManager.cancel(rec.id)
            container.repository.delete(rec)
            onDeleted()
        }
    }

    fun renameSpeaker(segment: Segment, name: String) {
        viewModelScope.launch {
            container.repository.updateSegment(segment.copy(speakerName = name.ifBlank { null }))
        }
    }

    /**
     * Edit one segment's text inline. Used for fixing STT errors — proper
     * nouns, code-switched words, dialect oddities Gemma misheard. The new
     * text replaces the old one as-is (no Gemma rewrite). Re-exports the
     * sidecar TXT/SRT/JSON so the on-disk files stay in sync.
     */
    fun editSegmentText(segment: Segment, newText: String) {
        if (newText == segment.text) return
        viewModelScope.launch {
            container.repository.updateSegment(segment.copy(text = newText))
            // Re-write sidecars so the .txt/.srt next to the audio reflect the
            // user's correction. Cheap (kB-scale) compared to re-running ASR.
            val rec = ui.value.recording ?: return@launch
            val segs = ui.value.segments.map { if (it.id == segment.id) it.copy(text = newText) else it }
            runCatching {
                nl.ihnatov.transcriber.asr.TranscriptExporter.writeSidecars(rec, segs)
            }
        }
    }

    /** Rename every segment whose `speaker` matches [speakerKey] (e.g. "SPEAKER_00"). */
    fun renameAllByKey(speakerKey: String, displayName: String?) {
        viewModelScope.launch {
            val cleaned = displayName?.ifBlank { null }
            val targets = ui.value.segments.filter { it.speaker == speakerKey }
            targets.forEach {
                container.repository.updateSegment(it.copy(speakerName = cleaned))
            }
            // Rewrite sidecars (.txt/.srt/.json/.speakers.json) so the
            // rename round-trips to disk. The speakers sidecar is what
            // the repository's replaceSegments() picks up on the next
            // re-transcribe to keep the name across DB delete + insert.
            val rec = ui.value.recording ?: return@launch
            val segs = ui.value.segments.map {
                if (it.speaker == speakerKey) it.copy(speakerName = cleaned) else it
            }
            runCatching {
                nl.ihnatov.transcriber.asr.TranscriptExporter.writeSidecars(rec, segs)
            }
        }
    }

    companion object {
        fun factory(container: AppContainer, recordingId: Long): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(
                    modelClass: Class<T>,
                    extras: androidx.lifecycle.viewmodel.CreationExtras,
                ): T {
                    val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                    return RecordingDetailViewModel(app, container, recordingId) as T
                }
            }
    }
}
