package nl.ihnatov.transcriber.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class RecordingRepository(
    private val context: Context,
    private val recordings: RecordingDao,
    private val segments: SegmentDao,
    private val outputs: OutputDao,
) {

    fun observeAll(): Flow<List<Recording>> = recordings.observeAll()

    /**
     * Search recordings by free-text query. Matches against title and any
     * segment text (substring, case-insensitive on SQLite's default
     * collation). Empty/blank query returns [observeAll]'s full list.
     *
     * Escapes the SQL LIKE wildcards (% and _) and our escape char (\)
     * inside the query so a search for "100%" doesn't match everything.
     */
    fun search(query: String): Flow<List<Recording>> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return observeAll()
        val escaped = trimmed
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        return recordings.search("%$escaped%")
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
