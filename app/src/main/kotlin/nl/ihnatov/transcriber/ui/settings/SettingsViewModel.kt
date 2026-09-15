package nl.ihnatov.transcriber.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nl.ihnatov.transcriber.asr.CatalogEntry
import nl.ihnatov.transcriber.asr.ModelCatalog
import nl.ihnatov.transcriber.asr.ModelDownloader
import nl.ihnatov.transcriber.asr.PromptStore
import nl.ihnatov.transcriber.data.AppContainer

class SettingsViewModel(
    application: Application,
    private val container: AppContainer,
) : AndroidViewModel(application) {

    /** Per-entry download status. Keyed by [CatalogEntry.id]. */
    sealed interface DownloadStatus {
        data object Idle : DownloadStatus
        data class Running(val bytesRead: Long, val totalBytes: Long?) : DownloadStatus
        /** Archive models only: download finished, decompressing — no percentage available, see ModelDownloader.Progress.Extracting. */
        data object Extracting : DownloadStatus
        data class Done(val file: File) : DownloadStatus
        data class Failed(val reason: String) : DownloadStatus
    }

    data class UiState(
        val installedFiles: List<File> = emptyList(),
        val catalog: List<CatalogEntry> = ModelCatalog.entries,
        val downloads: Map<String, DownloadStatus> = emptyMap(),
        val importing: Boolean = false,
        val importError: String? = null,
    )

    private val _state = MutableStateFlow(UiState(installedFiles = listInstalled()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val downloadJobs = mutableMapOf<String, Job>()

    fun refresh() {
        _state.value = _state.value.copy(installedFiles = listInstalled())
    }

    fun download(entry: CatalogEntry) {
        val current = _state.value.downloads[entry.id]
        if (current is DownloadStatus.Running || current is DownloadStatus.Extracting) return   // already running
        downloadJobs[entry.id]?.cancel()
        downloadJobs[entry.id] = viewModelScope.launch {
            container.modelDownloader.download(entry).collect { progress ->
                val next = when (progress) {
                    ModelDownloader.Progress.Starting -> DownloadStatus.Running(0, null)
                    is ModelDownloader.Progress.Streaming ->
                        DownloadStatus.Running(progress.bytesRead, progress.totalBytes)
                    ModelDownloader.Progress.Extracting -> DownloadStatus.Extracting
                    is ModelDownloader.Progress.Done -> DownloadStatus.Done(progress.file)
                    is ModelDownloader.Progress.Failed -> DownloadStatus.Failed(progress.reason)
                }
                _state.value = _state.value.copy(
                    downloads = _state.value.downloads + (entry.id to next),
                    installedFiles = if (next is DownloadStatus.Done) listInstalled() else _state.value.installedFiles,
                )
            }
        }
    }

    fun cancelDownload(entry: CatalogEntry) {
        downloadJobs[entry.id]?.cancel()
        downloadJobs.remove(entry.id)
        _state.value = _state.value.copy(
            downloads = _state.value.downloads - entry.id,
        )
    }

    // ---- Gemma prompts (exposed for direct binding from Compose) ----
    val promptStore: PromptStore get() = container.promptStore
    val presetStore: nl.ihnatov.transcriber.asr.PresetStore get() = container.presetStore
    val snippetStore: nl.ihnatov.transcriber.asr.SnippetStore get() = container.snippetStore
    val gemmaSettings: nl.ihnatov.transcriber.asr.GemmaSettingsStore get() = container.gemmaSettings
    val uiPrefs: nl.ihnatov.transcriber.asr.UiPrefs get() = container.uiPrefs
    val learnedNamesStore: nl.ihnatov.transcriber.asr.LearnedNamesStore get() = container.learnedNamesStore

    /** Re-harvest learned names from the whole library — "Rescan library" in Settings → Learned. */
    fun rescanLearnedNames() {
        nl.ihnatov.transcriber.asr.refreshLearnedTerms(
            scope = viewModelScope,
            repository = container.repository,
            promptStore = container.promptStore,
            store = container.learnedNamesStore,
        )
    }

    fun addLearnedTerm(term: nl.ihnatov.transcriber.asr.VocabularyHarvester.Term) {
        nl.ihnatov.transcriber.asr.addLearnedTerm(container.promptStore, container.learnedNamesStore, term)
    }

    fun dismissLearnedTerm(term: nl.ihnatov.transcriber.asr.VocabularyHarvester.Term) {
        container.learnedNamesStore.dismiss(term.key)
    }

    fun deleteFile(file: File) {
        viewModelScope.launch {
            container.asrFactory.deleteModel(file)
            // Clear any "Done" entry whose file matched, so its card returns to "Download".
            val updatedDownloads = _state.value.downloads.filterValues {
                !(it is DownloadStatus.Done && it.file == file)
            }
            _state.value = _state.value.copy(
                installedFiles = listInstalled(),
                downloads = updatedDownloads,
            )
        }
    }

    fun import(uri: Uri) {
        if (_state.value.importing) return
        _state.value = _state.value.copy(importing = true, importError = null)
        viewModelScope.launch {
            runCatching { container.asrFactory.importModelFromUri(uri) }
                .onSuccess {
                    _state.value = _state.value.copy(
                        importing = false,
                        installedFiles = listInstalled(),
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        importing = false,
                        importError = it.message ?: "Import failed",
                    )
                }
        }
    }

    private fun listInstalled(): List<File> =
        runCatching {
            container.asrFactory.modelsDir().listFiles()
                ?.toList()
                // Skip *.partial (mid-download) and *.extracting (mid-archive-
                // extract) — those are unfinished installs, not usable models.
                // Directories are the sherpa-onnx engines (Parakeet/Omnilingual/
                // Nemotron); flat files are everything else.
                ?.filter {
                    !it.name.endsWith(".partial") && !it.name.endsWith(".extracting")
                }
                ?.sortedBy { it.name }
                ?: emptyList()
        }.getOrDefault(emptyList())

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(
                    modelClass: Class<T>,
                    extras: androidx.lifecycle.viewmodel.CreationExtras,
                ): T {
                    val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                    return SettingsViewModel(app, container) as T
                }
            }
    }
}
