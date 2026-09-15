package nl.ihnatov.transcriber.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "recordings")
data class Recording(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val audioPath: String,          // absolute path on internal storage
    val createdAtMillis: Long,
    val durationSeconds: Double,
    val sourceLanguage: String?,    // null = unknown / auto
    val transcribedWithBackend: String? = null,
    val transcribedWithModel: String? = null,
    val translateToEnglish: Boolean = false,
    /** [nl.ihnatov.transcriber.asr.RecordingCategory.id], set by [nl.ihnatov.transcriber.asr.Gemma4Backend.suggestCategory] after transcription. Null until classified (or if classification failed/was skipped). */
    val category: String? = null,
    /** A recording lives in at most one [Folder]; null = unfiled. No cascade — deleting a Folder nullifies this instead (see MIGRATION_5_6). */
    val folderId: Long? = null,
)

@Entity(
    tableName = "segments",
    foreignKeys = [
        ForeignKey(
            entity = Recording::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("recordingId"), Index(value = ["recordingId", "startSeconds"])],
)
data class Segment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordingId: Long,
    val startSeconds: Double,
    val endSeconds: Double,
    val text: String,
    val language: String? = null,
    val speaker: String? = null,        // SPEAKER_00, SPEAKER_01, ... (until diarization is wired)
    val speakerName: String? = null,    // user-edited display name
)

/**
 * Output of a post-processing preset (Summary, Clean, Translate-polish, etc.)
 * applied to a Recording's transcript. One Recording can have many: a single
 * conversation might be summarized AND translated AND drafted into an email.
 *
 * `presetId` is the id of the preset that produced this output (matches the
 * id in PresetStore). `markdown` holds the generated content. We keep the
 * Recording on cascade-delete so removing an audio also nukes its outputs.
 */
@Entity(
    tableName = "outputs",
    foreignKeys = [
        ForeignKey(
            entity = Recording::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("recordingId"), Index(value = ["recordingId", "presetId"])],
)
data class OutputDoc(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordingId: Long,
    val presetId: String,
    val title: String,
    val markdown: String,
    val createdAtMillis: Long,
)

/**
 * Persisted snapshot of a [nl.ihnatov.transcriber.asr.TranscriptionJobManager.Params]
 * plus the schedule mode (waiting for charger vs FIFO queued).
 *
 * Why this exists: the previous JobManager kept charger-parked tasks in
 * an in-memory map. Killing the process (low memory, reboot, swipe-away)
 * dropped them silently — the user kicked off a 90-minute Gemma run
 * expecting "starts when I plug in tonight" and woke up to nothing in
 * the Library. Persisting here lets the JobManager rebuild its queue on
 * process start. Cascade-deletes with the Recording, so removing an
 * audio also cancels any pending work for it.
 *
 * Schema is intentionally flat — no JSON blobs, no TypeConverters — so
 * Room doesn't pull in a serializer dependency and we can grep for
 * usages of any individual field directly.
 *
 * Languages are stored as a comma-separated string for the same reason
 * (Room can't store List<String> without a converter). Empty string =
 * empty list (full auto-detect).
 */
@Entity(
    tableName = "pending_tasks",
    foreignKeys = [
        ForeignKey(
            entity = Recording::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    // queuedAtMillis is the FIFO key — every tryStartNext() does an
    // ORDER BY on it. An index keeps drain costs O(log n) regardless
    // of how deep the queue gets.
    indices = [Index("queuedAtMillis")],
)
data class PendingTask(
    @PrimaryKey val recordingId: Long,
    val backend: String,
    val languages: String,
    val translateTo: String?,
    val diarize: Boolean,
    val expectedSpeakers: Int,
    val hybridDiarize: Boolean,
    /** True = wait for AC. False = plain FIFO queued behind the running job. */
    val waitForCharger: Boolean,
    val queuedAtMillis: Long,
    /**
     * Super mode intent (schema 7). Persisted so a queued, charger-parked,
     * or checkpointed run replays with the SAME engines the user picked —
     * and, since [TranscriptionJobManager.start] round-trips every run
     * through this row, so that an immediate start keeps them at all.
     */
    @ColumnInfo(defaultValue = "0") val superMode: Boolean = false,
    val superPairA: String? = null,
    val superPairB: String? = null,
    @ColumnInfo(defaultValue = "0") val maxQuality: Boolean = false,
)
