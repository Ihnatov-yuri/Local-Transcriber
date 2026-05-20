package nl.ihnatov.transcriber.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {

    @Query("SELECT * FROM recordings ORDER BY createdAtMillis DESC")
    fun observeAll(): Flow<List<Recording>>

    @Query("SELECT * FROM recordings WHERE id = :id")
    fun observe(id: Long): Flow<Recording?>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun get(id: Long): Recording?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(recording: Recording): Long

    @Update
    suspend fun update(recording: Recording)

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun delete(id: Long)

    /**
     * Full-text search across title + segments. Returns recordings whose
     * title matches OR any segment text matches the query (case-insensitive
     * substring). Ordered by createdAt DESC to keep the list visually
     * stable as the user types.
     *
     * Why LIKE not FTS4: FTS would be ~5× faster on large libraries but
     * needs a content-table + triggers + a Room migration. With <1000
     * recordings × ~200 segments avg, the LIKE scan is still ~10k rows —
     * sub-100 ms on any modern device. Revisit if/when a user reports
     * sluggish search.
     *
     * The escape-for-LIKE step is the caller's job (Repository wraps).
     */
    @Query(
        "SELECT DISTINCT r.* FROM recordings r " +
            "LEFT JOIN segments s ON s.recordingId = r.id " +
            "WHERE r.title LIKE :pattern ESCAPE '\\' " +
            "OR s.text LIKE :pattern ESCAPE '\\' " +
            "ORDER BY r.createdAtMillis DESC"
    )
    fun search(pattern: String): Flow<List<Recording>>
}

@Dao
interface SegmentDao {

    @Query("SELECT * FROM segments WHERE recordingId = :recordingId ORDER BY startSeconds ASC")
    fun observe(recordingId: Long): Flow<List<Segment>>

    @Query("SELECT * FROM segments WHERE recordingId = :recordingId ORDER BY startSeconds ASC")
    suspend fun list(recordingId: Long): List<Segment>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(segments: List<Segment>)

    @Query("DELETE FROM segments WHERE recordingId = :recordingId")
    suspend fun deleteForRecording(recordingId: Long)

    @Transaction
    suspend fun replaceAll(recordingId: Long, segments: List<Segment>) {
        deleteForRecording(recordingId)
        if (segments.isNotEmpty()) insertAll(segments)
    }

    @Update
    suspend fun update(segment: Segment)
}

/**
 * Pending-task queue (charger-parked + plain FIFO queued). Keyed by
 * recordingId — one pending task per recording at a time. Ordered by
 * queuedAtMillis ASC for FIFO drain semantics.
 */
@Dao
interface PendingTaskDao {

    @Query("SELECT * FROM pending_tasks ORDER BY queuedAtMillis ASC")
    suspend fun listAll(): List<PendingTask>

    @Query("SELECT * FROM pending_tasks WHERE recordingId = :id")
    suspend fun get(id: Long): PendingTask?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: PendingTask)

    @Query("DELETE FROM pending_tasks WHERE recordingId = :id")
    suspend fun delete(id: Long)
}

@Dao
interface OutputDao {

    @Query("SELECT * FROM outputs WHERE recordingId = :recordingId ORDER BY createdAtMillis ASC")
    fun observe(recordingId: Long): Flow<List<OutputDoc>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(doc: OutputDoc): Long

    @Update
    suspend fun update(doc: OutputDoc)

    @Query("DELETE FROM outputs WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM outputs WHERE recordingId = :recordingId AND presetId = :presetId")
    suspend fun deleteByPreset(recordingId: Long, presetId: String)
}
