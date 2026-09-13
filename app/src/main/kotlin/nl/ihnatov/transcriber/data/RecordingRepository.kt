package nl.ihnatov.transcriber.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class RecordingRepository(
    private val context: Context,
    private val recordings: RecordingDao,
    private val segments: SegmentDao,
    private val outputs: OutputDao,
    private val versions: TranscriptVersionDao,
    private val folders: FolderDao,
    private val tags: TagDao,
) {

    /**
     * The Library list's one query: [folderId], [tagId], and [query] are
     * independently-settable, AND-combined filters — pass null/blank to
     * skip a filter. Mirrors the Mac app's own progressive-narrowing
     * `filtered` computed property (folder → tag → search), so applying
     * folder and tag filters together with a search term behaves exactly
     * like the Mac: each just narrows further, none of them are
     * exclusive modes.
     *
     * Escapes the SQL LIKE wildcards (% and _) and our escape char (\)
     * inside [query] so a search for "100%" doesn't match everything.
     */
    fun observeLibrary(folderId: Long? = null, tagId: Long? = null, query: String = ""): Flow<List<Recording>> {
        val trimmed = query.trim()
        val pattern = if (trimmed.isEmpty()) null else "%${escapeLikePattern(trimmed)}%"
        return recordings.observeFiltered(folderId, tagId, pattern)
    }

    fun observe(id: Long): Flow<Recording?> = recordings.observe(id)

    fun observeSegments(recordingId: Long): Flow<List<Segment>> = segments.observe(recordingId)

    suspend fun get(id: Long): Recording? = recordings.get(id)

    /** Insert metadata for a newly captured (or imported) audio file. */
    suspend fun create(
        title: String,
        audioPath: String,
        durationSeconds: Double,
        createdAtMillis: Long = System.currentTimeMillis(),
        sourceLanguage: String? = null,
    ): Long = recordings.insert(
        Recording(
            title = title,
            audioPath = audioPath,
            createdAtMillis = createdAtMillis,
            durationSeconds = durationSeconds,
            sourceLanguage = sourceLanguage,
        )
    )

    suspend fun update(recording: Recording) = recordings.update(recording)

    suspend fun delete(recording: Recording) = withContext(Dispatchers.IO) {
        recordings.delete(recording.id)
        runCatching { File(recording.audioPath).delete() }
    }

    /**
     * Replace all segments for a recording, preserving any user-set
     * speaker display names across the swap.
     *
     * The old behavior (a plain delete + insert) wiped `speakerName`
     * every time the user re-ran transcription — a real data-loss bug:
     * "I renamed SPEAKER_01 to Ahmed yesterday, ran the transcription
     * again with diarize on this morning, the name is gone."
     *
     * Snapshot the existing speaker → speakerName map first; reapply
     * it to incoming rows whose own speakerName is null. New speakers
     * (SPEAKER_03 in a more-segmented re-run) keep their null names —
     * the user re-labels them.
     *
     * Reads from the sherpa `<stem>.speakers.json` sidecar too when
     * present (see TranscriptExporter.writeSpeakersSidecar), so names
     * persist across reinstalls / DB wipes too.
     */
    suspend fun replaceSegments(
        recordingId: Long,
        segs: List<Segment>,
    ): Unit = withContext(Dispatchers.IO) {
        val priorNames: Map<String, String> = segments.list(recordingId)
            .mapNotNull { row ->
                val key = row.speaker ?: return@mapNotNull null
                val name = row.speakerName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                key to name
            }
            .toMap()
        // Sidecar overrides DB priors — it's the manual source of
        // truth (e.g., the user edited it by hand or it came from a
        // Mac-app export).
        val sidecarNames = recordings.get(recordingId)?.let { rec ->
            readSpeakersSidecar(File(rec.audioPath))
        } ?: emptyMap()
        val merged = (priorNames + sidecarNames)
        val withNames = if (merged.isEmpty()) segs else segs.map { s ->
            if (!s.speakerName.isNullOrBlank()) s
            else {
                val applied = s.speaker?.let { merged[it] }
                if (applied != null) s.copy(speakerName = applied) else s
            }
        }
        segments.replaceAll(recordingId, withNames)
    }

    /**
     * Read a `<stem>.speakers.json` sidecar (Mac-app-compatible) if it
     * exists next to the audio file. Format is a single JSON object
     * mapping SPEAKER_NN → display name. Failures are silent (returns
     * empty map) — corrupted sidecars should not break re-transcription.
     */
    private fun readSpeakersSidecar(audio: File): Map<String, String> {
        val dir = audio.parentFile ?: return emptyMap()
        val file = File(dir, "${audio.nameWithoutExtension}.speakers.json")
        if (!file.exists()) return emptyMap()
        return runCatching {
            val raw = file.readText()
            kotlinx.serialization.json.Json.decodeFromString<Map<String, String>>(raw)
        }.getOrDefault(emptyMap())
    }

    suspend fun updateSegment(segment: Segment) = segments.update(segment)

    /**
     * Every recording's id paired with its transcript, oldest-first
     * segment order — the raw material for
     * [nl.ihnatov.transcriber.asr.VocabularyHarvester.harvest]. Reads the
     * whole library, so callers should run this off the main thread and
     * not too often (see `LearnedNames.kt`'s launch-time trigger).
     */
    suspend fun allTranscriptsForHarvest(): List<nl.ihnatov.transcriber.asr.VocabularyHarvester.HarvestItem> =
        withContext(Dispatchers.IO) {
            recordings.listAll().map { rec ->
                val text = segments.list(rec.id).sortedBy { it.startSeconds }.joinToString("\n") { it.text }
                nl.ihnatov.transcriber.asr.VocabularyHarvester.HarvestItem(rec.id, text)
            }
        }

    fun observeVersions(recordingId: Long): Flow<List<TranscriptVersion>> = versions.observe(recordingId)

    /** Shared by [snapshotCurrentTranscript] and [restoreVersion]; a no-op when there's nothing to snapshot yet. */
    private suspend fun snapshotIfNonEmpty(recordingId: Long, engineId: String, engineLabel: String) {
        val current = segments.list(recordingId)
        if (current.isEmpty()) return
        versions.insert(
            TranscriptVersion(
                recordingId = recordingId,
                engineId = engineId,
                engineLabel = engineLabel,
                createdAtMillis = System.currentTimeMillis(),
                segmentCount = current.size,
                segmentsJson = encodeSegments(current),
            )
        )
    }

    /**
     * Snapshot the recording's CURRENT segments as a new [TranscriptVersion]
     * before they're about to be overwritten — call this once per run
     * (not once per incremental [replaceSegments] save; a Gemma streaming
     * run calls that many times per chunk, which would otherwise flood
     * the version list). A no-op when there's nothing to snapshot yet
     * (first-ever run on a recording).
     */
    suspend fun snapshotCurrentTranscript(
        recordingId: Long,
        engineId: String,
        engineLabel: String,
    ): Unit = withContext(Dispatchers.IO) {
        snapshotIfNonEmpty(recordingId, engineId, engineLabel)
    }

    /**
     * Replace the live transcript with a saved version's segments — the
     * inverse of [snapshotCurrentTranscript]. Snapshots whatever is
     * currently live first (labeled [currentEngineId]/[currentEngineLabel],
     * i.e. whatever produced it — same convention as
     * [nl.ihnatov.transcriber.asr.TranscriptionRunner]'s pre-run snapshot),
     * so restoring an older version can never silently discard the state
     * you restored FROM — that state becomes a version of its own.
     *
     * Returns the restored segments (so the caller can re-write sidecar
     * files without a redundant DB read), or null if [versionId] doesn't
     * exist.
     */
    suspend fun restoreVersion(
        versionId: Long,
        currentEngineId: String,
        currentEngineLabel: String,
    ): List<Segment>? = withContext(Dispatchers.IO) {
        val version = versions.get(versionId) ?: return@withContext null
        snapshotIfNonEmpty(version.recordingId, currentEngineId, currentEngineLabel)
        val restored = decodeSegments(version.segmentsJson).map { it.toSegment(version.recordingId) }
        segments.replaceAll(version.recordingId, restored)
        restored
    }

    suspend fun deleteVersion(id: Long) = versions.delete(id)

    // ---- Folders (Phase 6) ----
    // A recording lives in at most one folder. Name uniqueness is enforced
    // here, case-insensitively, not via a DB constraint — same reasoning
    // as the Mac's own Folder/Tag comment: a UNIQUE column would turn a
    // duplicate insert into a silent upsert instead of a rejected one.

    class EmptyNameException : Exception("Name cannot be empty.")
    class DuplicateNameException(name: String) : Exception("'$name' already exists.")

    fun observeFoldersWithCounts(): Flow<List<FolderWithCount>> = folders.observeAllWithCounts()

    /** Plain folder list (no counts) — the Detail screen's "FOLDER: X ▾" dropdown just needs names to choose from. */
    fun observeFolders(): Flow<List<Folder>> = folders.observeAllWithCounts().map { list -> list.map { it.folder } }

    suspend fun createFolder(name: String): Folder {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw EmptyNameException()
        if (folders.findByName(trimmed) != null) throw DuplicateNameException(trimmed)
        val existing = folders.listAll()
        val folder = Folder(
            name = trimmed,
            sortOrder = (existing.maxOfOrNull { it.sortOrder } ?: -1) + 1,
            createdAtMillis = System.currentTimeMillis(),
        )
        return folder.copy(id = folders.insert(folder))
    }

    suspend fun renameFolder(folder: Folder, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw EmptyNameException()
        val existing = folders.findByName(trimmed)
        if (existing != null && existing.id != folder.id) throw DuplicateNameException(trimmed)
        folders.update(folder.copy(name = trimmed))
    }

    /** Recordings in [folder] survive — they're unfiled, never deleted (matches the Mac's `.nullify` delete rule). */
    suspend fun deleteFolder(folder: Folder) = folders.delete(folder.id)

    /** null = remove from its folder. */
    suspend fun moveToFolder(recording: Recording, folderId: Long?) =
        recordings.update(recording.copy(folderId = folderId))

    // ---- Tags (Phase 6) ----
    // Many-to-many with Recording. Tags have no independent lifecycle or
    // "delete tag" action — a Tag row disappears automatically once its
    // last usage is removed (see removeTag), same as the Mac.

    fun observeTagsWithCounts(): Flow<List<TagWithCount>> = tags.observeAllWithCounts()

    fun observeTagsForRecording(recordingId: Long): Flow<List<Tag>> = tags.observeForRecording(recordingId)

    /** Find-or-create by trimmed, case-insensitive name; no-op if already applied. */
    suspend fun addTag(name: String, recordingId: Long): Tag? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        val tag = tags.findByName(trimmed) ?: run {
            val new = Tag(name = trimmed, createdAtMillis = System.currentTimeMillis())
            new.copy(id = tags.insert(new))
        }
        tags.attach(recordingId, tag.id)
        return tag
    }

    /** Removes the tag from this recording; deletes the Tag row entirely if that was its last usage. */
    suspend fun removeTag(tagId: Long, recordingId: Long) = tags.detachAndPruneIfOrphaned(recordingId, tagId)

    /**
     * Diff-based bulk edit: after this call, [recordingId] carries exactly
     * [names] (each found-or-created); anything it carried before that
     * isn't in [names] is removed (and pruned if that orphans it).
     */
    suspend fun setTags(names: List<String>, recordingId: Long) {
        val wanted = names.map { it.trim() }.filter { it.isNotEmpty() }
        val current = tags.listForRecording(recordingId)
        val stale = current.filter { tag -> wanted.none { it.equals(tag.name, ignoreCase = true) } }
        for (tag in stale) removeTag(tag.id, recordingId)
        for (name in wanted) addTag(name, recordingId)
    }

    /** Observe all post-processing outputs for a recording (in creation order). */
    fun observeOutputs(recordingId: Long): Flow<List<OutputDoc>> = outputs.observe(recordingId)

    /**
     * Insert a fresh OutputDoc, replacing any earlier output that used the
     * same preset on the same recording. Keeps the Detail UI tidy — running
     * Summary twice updates the existing Summary tab instead of stacking.
     */
    suspend fun replaceOutput(doc: OutputDoc): Long {
        outputs.deleteByPreset(recordingId = doc.recordingId, presetId = doc.presetId)
        return outputs.insert(doc)
    }

    suspend fun deleteOutput(id: Long) = outputs.delete(id)

    /** Imported file plus probed duration (0.0 if probing fails). */
    data class ImportedAudio(val file: File, val durationSeconds: Double)

    /**
     * Copy an audio file the user picked through SAF into our private
     * recordings/ folder and probe its duration with MediaMetadataRetriever
     * so the Library row shows a real `mm:ss` immediately (no need to wait
     * for ASR). Works for WAV/MP3/M4A/AAC/OGG/FLAC — anything Android can
     * play.
     */
    suspend fun importFromUri(uri: Uri): ImportedAudio = withContext(Dispatchers.IO) {
        val display = DocumentFile.fromSingleUri(context, uri)?.name ?: "imported.audio"
        val dst = File(recordingsDir(), "${System.currentTimeMillis()}_$display")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Could not open audio URI $uri" }
            FileOutputStream(dst).use { output -> input.copyTo(output) }
        }
        val duration = probeDurationSeconds(dst)
        ImportedAudio(file = dst, durationSeconds = duration)
    }

    private fun probeDurationSeconds(file: File): Double {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            ms / 1000.0
        } catch (_: Throwable) {
            0.0
        } finally {
            runCatching { r.release() }
        }
    }

    fun recordingsDir(): File {
        val dir = File(context.filesDir, "recordings")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}

/**
 * Escape SQL LIKE's two wildcards (`%`, `_`) and our own escape character
 * (`\`) inside a raw search term, so a literal search for e.g. "100%"
 * matches only that substring instead of "everything" (`%` unescaped is
 * LIKE's own any-sequence wildcard). Callers wrap the result in their own
 * leading/trailing `%` for a substring match; the query itself must use
 * `ESCAPE '\'` for this to take effect (see [RecordingDao.search]).
 */
internal fun escapeLikePattern(raw: String): String =
    raw
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")
