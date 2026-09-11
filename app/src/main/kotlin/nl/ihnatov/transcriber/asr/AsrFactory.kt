package nl.ihnatov.transcriber.asr

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Produces ASR backend instances, resolves model file paths, and tracks the
 * user's preferred model per backend.
 *
 * Storage layout:
 *   filesDir/models/
 *     ggml-tiny.bin
 *     ggml-small.bin
 *     gemma-4-E2B-it.litertlm
 *
 * Why internal `filesDir`: external-storage / scoped-storage / FUSE has known
 * visibility issues on Android 14+ Samsung devices — files placed by adb are
 * sometimes invisible to the app process. Internal storage is unambiguous.
 *
 * Provisioning paths (in priority order):
 *   1. Settings → Download a model (catalog of curated HF URLs).
 *   2. Settings → Import custom model file (SAF picker).
 */
class AsrFactory(
    private val context: Context,
    private val promptStore: PromptStore? = null,
    private val gemmaSettings: GemmaSettingsStore? = null,
) {

    private val prefs = context.getSharedPreferences("asr_factory", Context.MODE_PRIVATE)

    fun create(kind: AsrBackendKind): AsrBackend = when (kind) {
        AsrBackendKind.WhisperCpp -> WhisperCppBackend()
        AsrBackendKind.Gemma4 -> Gemma4Backend(context, promptStore, gemmaSettings)
        AsrBackendKind.Parakeet, AsrBackendKind.Omnilingual -> SherpaOfflineBackend(kind)
        AsrBackendKind.NemotronStream -> throw UnsupportedOperationException(
            "NemotronStream is a streaming-only engine, driven directly by LiveTranscriber — " +
                "there's no offline AsrBackend for it."
        )
    }

    fun modelsDir(): File {
        val dir = File(context.filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** True for engines whose model is a directory of files (encoder/decoder/joiner + tokens, or model + tokens). */
    fun isDirectoryBased(kind: AsrBackendKind): Boolean = kind in DIRECTORY_BASED_KINDS

    /**
     * All installed models for [kind], biggest first. Flat-file backends
     * (Whisper, Gemma) match by extension under [modelsDir] directly;
     * directory-based backends (Parakeet, Omnilingual, Nemotron) match
     * catalog entries of that [kind] whose extracted directory exists and
     * looks complete — these are catalog/downloader-only for now, no SAF
     * import (importing a whole multi-file directory through a
     * single-file picker doesn't map cleanly, unlike one Whisper .bin).
     */
    fun listModels(kind: AsrBackendKind): List<File> {
        if (isDirectoryBased(kind)) {
            return ModelCatalog.entries
                .filter { it.kind == kind && it.role == ModelRole.Asr }
                .map { File(modelsDir(), it.filename) }
                .filter { it.isDirectory && isCompleteModelDir(it, kind) }
                .sortedByDescending { dirSizeBytes(it) }
        }
        val exts = extensionsFor(kind)
        return modelsDir()
            .listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in exts }
            ?.sortedByDescending { it.length() }
            ?: emptyList()
    }

    /** Sum of file sizes under a directory (one level — these model dirs are always flat). */
    private fun dirSizeBytes(dir: File): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    private fun isCompleteModelDir(dir: File, kind: AsrBackendKind): Boolean {
        val files = dir.listFiles() ?: return false
        val hasTokens = files.any { it.name == "tokens.txt" }
        if (!hasTokens) return false
        return when (kind) {
            // Both transducers: Parakeet (offline) and Nemotron 3.5 (online
            // streaming) — the sherpa-onnx package ships the same
            // encoder/decoder/joiner shape for either.
            AsrBackendKind.Parakeet, AsrBackendKind.NemotronStream -> listOf("encoder", "decoder", "joiner").all { prefix ->
                files.any { it.isFile && it.name.startsWith(prefix) && it.extension == "onnx" }
            }
            AsrBackendKind.Omnilingual ->
                files.any { it.isFile && it.name.startsWith("model") && it.extension == "onnx" }
            else -> false
        }
    }

    /**
     * Resolve which model file to use for a given backend.
     *
     * Priority:
     *   1. The filename the user explicitly selected via [setSelectedModel].
     *      If that file no longer exists, fall through.
     *   2. The largest matching file under [modelsDir] (large-v3 > tiny).
     *   3. null if no compatible file is installed.
     */
    fun resolveModel(kind: AsrBackendKind): File? {
        val installed = listModels(kind)
        if (installed.isEmpty()) return null
        val pinned = getSelectedModel(kind)
        if (pinned != null) {
            val match = installed.firstOrNull { it.name == pinned }
            if (match != null) return match
        }
        return installed.first()
    }

    fun getSelectedModel(kind: AsrBackendKind): String? =
        prefs.getString(selectionKey(kind), null)

    fun setSelectedModel(kind: AsrBackendKind, filename: String?) {
        prefs.edit().apply {
            if (filename == null) remove(selectionKey(kind))
            else putString(selectionKey(kind), filename)
            apply()
        }
    }

    /**
     * Copy a model the user picked via SAF into our internal `modelsDir()`.
     *
     * Atomic: streams into `<name>.partial`, then renames over the final
     * filename only on a successful complete copy. This protects against
     * three otherwise-real failure modes:
     *
     *   1. Interrupted import (low storage, app killed, USB drop) — the old
     *      file at `<name>` stays intact; only the `.partial` is left behind
     *      and gets cleaned up on next import or `deleteModel`.
     *   2. Overwriting a model that's currently being read by an in-flight
     *      transcription — the rename is atomic on the same filesystem, so
     *      no consumer ever sees a half-written file.
     *   3. Re-imports of the same filename — old `.partial` from a previous
     *      failed run is deleted before we start, so we don't append to it.
     *
     * Caller should run this on a background dispatcher — large model files
     * take tens of seconds to copy.
     */
    suspend fun importModelFromUri(uri: Uri): File = withContext(Dispatchers.IO) {
        val name = DocumentFile.fromSingleUri(context, uri)?.name ?: "model-${System.currentTimeMillis()}.bin"
        val dst = File(modelsDir(), name)
        val partial = File(modelsDir(), "$name.partial")
        // Clear any leftover from a prior failed run; doesn't matter if it
        // doesn't exist.
        if (partial.exists()) partial.delete()
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Could not open URI $uri" }
                FileOutputStream(partial).use { output -> input.copyTo(output) }
            }
            // Atomic rename. renameTo is atomic on the same volume (we're
            // always within filesDir, so this holds). If the final path
            // already exists, renameTo returns false on some filesystems —
            // delete first to be safe. The brief window between delete and
            // rename is OK because we're not racing other writers (only
            // this code writes here).
            if (dst.exists()) dst.delete()
            if (!partial.renameTo(dst)) {
                throw java.io.IOException("Failed to finalize import: rename ${partial.name} -> ${dst.name}")
            }
        } catch (t: Throwable) {
            // Clean up the partial on any failure path so we don't leave
            // garbage in modelsDir for the user to wonder about.
            if (partial.exists()) partial.delete()
            throw t
        }
        dst
    }

    fun deleteModel(file: File): Boolean {
        // Directory-based sherpa models (Parakeet/Omnilingual/Nemotron):
        // File.delete() only removes EMPTY directories, so a populated
        // model directory would silently fail to delete without this.
        val deleted = if (file.isDirectory) file.deleteRecursively() else file.delete()
        // If the user deleted their selected model, clear the pin so resolve()
        // falls back cleanly. `entries` is the Kotlin 2.x replacement for the
        // deprecated `.values()` array.
        for (kind in AsrBackendKind.entries) {
            if (getSelectedModel(kind) == file.name) setSelectedModel(kind, null)
        }
        return deleted
    }

    fun expectedExtensionsHint(kind: AsrBackendKind): String = when (kind) {
        AsrBackendKind.WhisperCpp -> ".bin (ggml-tiny.bin, ggml-small.bin, ggml-large-v3-turbo-q5_0.bin)"
        AsrBackendKind.Gemma4 -> ".litertlm or .task (gemma-4-E2B-it.litertlm)"
        AsrBackendKind.Parakeet, AsrBackendKind.Omnilingual, AsrBackendKind.NemotronStream ->
            "a downloaded model folder (Settings → Download) — not something you import manually"
    }

    private fun extensionsFor(kind: AsrBackendKind): Set<String> = when (kind) {
        AsrBackendKind.WhisperCpp -> setOf("bin", "ggml")
        AsrBackendKind.Gemma4 -> setOf("litertlm", "task")
        AsrBackendKind.Parakeet, AsrBackendKind.Omnilingual, AsrBackendKind.NemotronStream -> emptySet()
    }

    /**
     * Which backend a model file belongs to, inferred from its extension.
     * Returns null for files that match no known backend (including
     * directory-based models — those are identified by [kindForDirectory]
     * instead, since a directory has no extension to switch on). Used by
     * the Settings "Installed" UI to group models and offer an active-
     * model picker per backend (so a user with both Gemma E2B and E4B —
     * or two Whisper sizes — can choose which one transcription uses).
     */
    fun kindForFile(file: File): AsrBackendKind? {
        val ext = file.extension.lowercase()
        return AsrBackendKind.entries.firstOrNull { ext.isNotEmpty() && ext in extensionsFor(it) }
    }

    /** Which directory-based backend [dir] belongs to, by matching it against a catalog entry, or null. */
    fun kindForDirectory(dir: File): AsrBackendKind? =
        ModelCatalog.entries.firstOrNull { it.role == ModelRole.Asr && File(modelsDir(), it.filename) == dir }?.kind

    private fun selectionKey(kind: AsrBackendKind): String = "selected_model_${kind.name}"

    companion object {
        private val DIRECTORY_BASED_KINDS = setOf(
            AsrBackendKind.Parakeet,
            AsrBackendKind.Omnilingual,
            AsrBackendKind.NemotronStream,
        )
    }
}
