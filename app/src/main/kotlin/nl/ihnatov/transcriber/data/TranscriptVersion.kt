package nl.ihnatov.transcriber.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A saved snapshot of a recording's transcript, taken right before a
 * (re-)transcription run overwrites the live `segments` rows — so
 * re-running with a different engine, or one that does worse, never
 * silently loses a working transcript. Phase 4 of the 2026-09 plan's
 * first real Room migration (3 to 4); see [nl.ihnatov.transcriber.data.AppDatabase]'s
 * `MIGRATION_3_4` — this project went destructive-migration-only up to
 * here specifically so this table could be the first thing that needs a
 * real one.
 *
 * `segmentsJson` is a deliberate, scoped exception to this project's
 * usual flat-columns-only schema (see [Segment]'s own doc comment): a
 * versioned snapshot is archival, not a hot query path, and Room's own
 * per-column query support buys nothing here — every read is "give me
 * everything for this version, as it was."
 */
@Entity(
    tableName = "transcript_versions",
    foreignKeys = [
        ForeignKey(
            entity = Recording::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("recordingId")],
)
data class TranscriptVersion(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordingId: Long,
    /** [nl.ihnatov.transcriber.asr.AsrBackendKind] name, or "super:parakeet+whisper" for a Super mode run. */
    val engineId: String,
    /** Human-readable label for the version picker, e.g. "Parakeet" or "Super: Parakeet + Whisper". */
    val engineLabel: String,
    val createdAtMillis: Long,
    val segmentCount: Int,
    val segmentsJson: String,
)

/** What actually gets JSON-encoded into [TranscriptVersion.segmentsJson] — a plain-data mirror of [Segment], no Room annotations. */
@Serializable
data class SegmentSnapshot(
    val startSeconds: Double,
    val endSeconds: Double,
    val text: String,
    val language: String? = null,
    val speaker: String? = null,
    val speakerName: String? = null,
)

private val snapshotJson = Json { ignoreUnknownKeys = true }

fun Segment.toSnapshot(): SegmentSnapshot =
    SegmentSnapshot(startSeconds, endSeconds, text, language, speaker, speakerName)

fun SegmentSnapshot.toSegment(recordingId: Long): Segment =
    Segment(
        recordingId = recordingId,
        startSeconds = startSeconds,
        endSeconds = endSeconds,
        text = text,
        language = language,
        speaker = speaker,
        speakerName = speakerName,
    )

fun encodeSegments(segments: List<Segment>): String =
    snapshotJson.encodeToString(segments.map { it.toSnapshot() })

/** Returns an empty list (rather than throwing) for a corrupt or unreadable blob — a bad snapshot must never crash the version picker. */
fun decodeSegments(json: String): List<SegmentSnapshot> =
    runCatching { snapshotJson.decodeFromString<List<SegmentSnapshot>>(json) }.getOrDefault(emptyList())
