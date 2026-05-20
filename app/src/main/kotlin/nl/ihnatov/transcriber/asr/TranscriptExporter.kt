package nl.ihnatov.transcriber.asr

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nl.ihnatov.transcriber.data.Recording
import nl.ihnatov.transcriber.data.Segment

/**
 * Persists a transcript as plain files next to the audio file. This is the
 * "library outside the app" surface — sidecar files live in the app's private
 * `recordings/` dir but can be shared/exported via the Detail-screen action.
 *
 * Three formats per recording, mirroring the Mac app's `formats.py` so files
 * round-trip cleanly between Mac (Python pipeline) and Android (this app):
 *
 *   - `<stem>.txt`  — readable, one block per speaker
 *   - `<stem>.srt`  — SubRip subtitles for video / share to a media player
 *   - `<stem>.json` — structured: metadata + every segment with timestamps
 *
 * Why all three by default: we don't know what the user wants downstream.
 * Disk is cheap, parsing the right one for the moment is free.
 */
object TranscriptExporter {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    suspend fun writeSidecars(
        recording: Recording,
        segments: List<Segment>,
    ): List<File> = withContext(Dispatchers.IO) {
        val audio = File(recording.audioPath)
        val dir = audio.parentFile ?: return@withContext emptyList()
        val stem = audio.nameWithoutExtension
        val out = mutableListOf<File>()

        File(dir, "$stem.txt").also {
            it.writeText(toTxt(recording, segments))
            out += it
        }
        File(dir, "$stem.srt").also {
            it.writeText(toSrt(segments))
            out += it
        }
        File(dir, "$stem.json").also {
            it.writeText(toJson(recording, segments))
            out += it
        }
        // Speakers sidecar: emit only when there's at least one user-
        // named speaker to persist. The shape mirrors the Mac app's
        // `<stem>.speakers.json` (SPEAKER_NN → display name), so a
        // recording's speaker names round-trip if you move the audio
        // file to the Mac pipeline (or get nuked + reimported on
        // Android — readSpeakersSidecar in the repository merges it
        // back in on the next transcription).
        val speakerNames = segments
            .mapNotNull { seg ->
                val key = seg.speaker ?: return@mapNotNull null
                val name = seg.speakerName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                key to name
            }
            .toMap()
        val speakersFile = File(dir, "$stem.speakers.json")
        if (speakerNames.isNotEmpty()) {
            speakersFile.writeText(json.encodeToString(speakerNames))
            out += speakersFile
        } else if (speakersFile.exists()) {
            // User cleared every name — delete the now-stale sidecar
            // rather than leave it as ground-truth for next re-transcribe.
            speakersFile.delete()
        }
        out
    }

    fun toTxt(recording: Recording, segments: List<Segment>): String {
        val sb = StringBuilder()
        sb.append("# ").append(recording.title).append("\n")
        recording.sourceLanguage?.let { sb.append("Language: ").append(it).append("\n") }
        if (recording.translateToEnglish) {
            // The Recording row carries a boolean ("was translated"); the actual
            // target language lives in segment.language. Use the first segment
            // to label which language the user translated INTO.
            val target = segments.firstOrNull { !it.language.isNullOrBlank() }?.language
            sb.append("Translated to ").append(target ?: "another language").append("\n")
        }
        sb.append("Duration: ").append(formatDuration(recording.durationSeconds)).append("\n\n")

        var lastSpeaker: String? = "__none__"
        for (seg in segments) {
            val speaker = seg.speakerName ?: seg.speaker?.replace("SPEAKER_", "Speaker ")
            if (speaker != null && speaker != lastSpeaker) {
                sb.append("\n[").append(speaker).append("]\n")
                lastSpeaker = speaker
            }
            sb.append(seg.text.trim()).append("\n")
        }
        return sb.toString()
    }

    fun toSrt(segments: List<Segment>): String {
        val sb = StringBuilder()
        for ((i, seg) in segments.withIndex()) {
            sb.append(i + 1).append("\n")
            sb.append(srtTimestamp(seg.startSeconds))
                .append(" --> ")
                .append(srtTimestamp(seg.endSeconds))
                .append("\n")
            val speaker = seg.speakerName ?: seg.speaker?.replace("SPEAKER_", "Speaker ")
            if (speaker != null) sb.append(speaker).append(": ")
            sb.append(seg.text.trim()).append("\n\n")
        }
        return sb.toString()
    }

    fun toJson(recording: Recording, segments: List<Segment>): String {
        val doc = TranscriptJson(
            audioPath = recording.audioPath,
            title = recording.title,
            language = recording.sourceLanguage,
            translated = recording.translateToEnglish,
            durationSeconds = recording.durationSeconds,
            backend = recording.transcribedWithBackend,
            model = recording.transcribedWithModel,
            segments = segments.map {
                JsonSegment(
                    start = it.startSeconds,
                    end = it.endSeconds,
                    speaker = it.speaker,
                    speakerName = it.speakerName,
                    language = it.language,
                    text = it.text,
                )
            },
        )
        return json.encodeToString(doc)
    }

    // ---------- helpers ----------

    private fun srtTimestamp(seconds: Double): String {
        val total = (seconds * 1000).toLong().coerceAtLeast(0)
        val ms = total % 1000
        val s = (total / 1000) % 60
        val m = (total / 60_000) % 60
        val h = total / 3_600_000
        return "%02d:%02d:%02d,%03d".format(h, m, s, ms)
    }

    private fun formatDuration(seconds: Double): String {
        val s = seconds.toInt()
        return if (s >= 3600) {
            "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
        } else {
            "%d:%02d".format(s / 60, s % 60)
        }
    }

    @Serializable
    private data class TranscriptJson(
        val audioPath: String,
        val title: String,
        val language: String?,
        val translated: Boolean,
        val durationSeconds: Double,
        val backend: String?,
        val model: String?,
        val segments: List<JsonSegment>,
    )

    @Serializable
    private data class JsonSegment(
        val start: Double,
        val end: Double,
        val speaker: String?,
        val speakerName: String?,
        val language: String?,
        val text: String,
    )
}
