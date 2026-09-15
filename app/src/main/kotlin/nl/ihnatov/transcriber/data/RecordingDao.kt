package nl.ihnatov.transcriber.data

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {

    @Query("SELECT * FROM recordings WHERE id = :id")
    fun observe(id: Long): Flow<Recording?>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun get(id: Long): Recording?

    /** Snapshot of every recording — for one-shot library-wide scans (e.g. the learned-names harvester), not for display (use [observeFiltered]). */
    @Query("SELECT * FROM recordings")
    suspend fun listAll(): List<Recording>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(recording: Recording): Long

    @Update
    suspend fun update(recording: Recording)

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun delete(id: Long)

    /**
     * The Library list's one query: folder, tag, and text search are
     * independently-settable, AND-combined filters (each null = "don't
     * filter on this"), mirroring the Mac app's own progressive-narrowing
     * `filtered` computed property (folder → tag → search). Ordered by
     * createdAt DESC to keep the list visually stable as the user types.
     *
     * Why LIKE not FTS4 for the text side: FTS would be ~5× faster on
     * large libraries but needs a content-table + triggers + its own
     * migration. With <1000 recordings × ~200 segments avg, the LIKE scan
     * is still ~10k rows — sub-100 ms on any modern device. Revisit if/
     * when a user reports sluggish search.
     *
     * The escape-for-LIKE step is the caller's job (Repository wraps).
     */
    @Query(
        "SELECT DISTINCT r.* FROM recordings r " +
            "LEFT JOIN recording_tag_cross_ref x ON x.recordingId = r.id " +
            "LEFT JOIN segments s ON s.recordingId = r.id " +
            "WHERE (:folderId IS NULL OR r.folderId = :folderId) " +
            "AND (:tagId IS NULL OR x.tagId = :tagId) " +
            "AND (:pattern IS NULL OR r.title LIKE :pattern ESCAPE '\\' OR s.text LIKE :pattern ESCAPE '\\') " +
            "ORDER BY r.createdAtMillis DESC"
    )
    fun observeFiltered(folderId: Long?, tagId: Long?, pattern: String?): Flow<List<Recording>>

    /** Fast path for the default Library view (optional folder filter only) — observes `recordings` alone. */
    @Query(
        "SELECT * FROM recordings WHERE (:folderId IS NULL OR folderId = :folderId) ORDER BY createdAtMillis DESC"
    )
    fun observeByFolder(folderId: Long?): Flow<List<Recording>>
}

@Dao
interface SegmentDao {

    @Query("SELECT * FROM segments WHERE recordingId = :recordingId ORDER BY startSeconds ASC")
    fun observe(recordingId: Long): Flow<List<Segment>>

    @Query("SELECT * FROM segments WHERE recordingId = :recordingId ORDER BY startSeconds ASC")
    suspend fun list(recordingId: Long): List<Segment>

    /** Every segment in the library, grouped by recording in time order — for the learned-names harvest. */
    @Query("SELECT * FROM segments ORDER BY recordingId ASC, startSeconds ASC")
    suspend fun listAllOrdered(): List<Segment>

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

@Dao
interface TranscriptVersionDao {

    @Query("SELECT * FROM transcript_versions WHERE recordingId = :recordingId ORDER BY createdAtMillis DESC")
    fun observe(recordingId: Long): Flow<List<TranscriptVersion>>

    @Query("SELECT * FROM transcript_versions WHERE id = :id")
    suspend fun get(id: Long): TranscriptVersion?

    @Query("SELECT * FROM transcript_versions WHERE recordingId = :recordingId ORDER BY createdAtMillis DESC LIMIT 1")
    suspend fun latest(recordingId: Long): TranscriptVersion?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(version: TranscriptVersion): Long

    @Query("DELETE FROM transcript_versions WHERE id = :id")
    suspend fun delete(id: Long)
}

/** A [Folder] plus how many recordings currently sit in it — the Library chip strip's "NAME (count)" label. */
data class FolderWithCount(
    @Embedded val folder: Folder,
    val recordingCount: Int,
)

@Dao
interface FolderDao {

    @Query(
        "SELECT f.*, (SELECT COUNT(*) FROM recordings r WHERE r.folderId = f.id) AS recordingCount " +
            "FROM folders f ORDER BY f.sortOrder ASC, f.name ASC"
    )
    fun observeAllWithCounts(): Flow<List<FolderWithCount>>

    @Query("SELECT * FROM folders ORDER BY sortOrder ASC, name ASC")
    suspend fun listAll(): List<Folder>

    @Query("SELECT * FROM folders WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findByName(name: String): Folder?

    @Insert
    suspend fun insert(folder: Folder): Long

    @Update
    suspend fun update(folder: Folder)

    @Query("UPDATE recordings SET folderId = NULL WHERE folderId = :id")
    suspend fun unfileRecordings(id: Long)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun deleteRow(id: Long)

    /** Recordings survive — they're just unfiled, never deleted (matches the Mac's `.nullify` delete rule). */
    @Transaction
    suspend fun delete(id: Long) {
        unfileRecordings(id)
        deleteRow(id)
    }
}

/** A [Tag] plus how many recordings currently carry it — the Library tag-filter menu's "name (count)" label. */
data class TagWithCount(
    @Embedded val tag: Tag,
    val recordingCount: Int,
)

@Dao
interface TagDao {

    @Query(
        "SELECT t.*, (SELECT COUNT(*) FROM recording_tag_cross_ref x WHERE x.tagId = t.id) AS recordingCount " +
            "FROM tags t ORDER BY t.name ASC"
    )
    fun observeAllWithCounts(): Flow<List<TagWithCount>>

    @Query("SELECT t.* FROM tags t INNER JOIN recording_tag_cross_ref x ON x.tagId = t.id WHERE x.recordingId = :recordingId ORDER BY t.name ASC")
    fun observeForRecording(recordingId: Long): Flow<List<Tag>>

    /** One-shot snapshot for [RecordingRepository.setTags]'s diff — use [observeForRecording] for display. */
    @Query("SELECT t.* FROM tags t INNER JOIN recording_tag_cross_ref x ON x.tagId = t.id WHERE x.recordingId = :recordingId")
    suspend fun listForRecording(recordingId: Long): List<Tag>

    @Query("SELECT * FROM tags WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findByName(name: String): Tag?

    @Insert
    suspend fun insert(tag: Tag): Long

    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun deleteRow(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addCrossRef(ref: RecordingTagCrossRef)

    @Query("DELETE FROM recording_tag_cross_ref WHERE recordingId = :recordingId AND tagId = :tagId")
    suspend fun removeCrossRef(recordingId: Long, tagId: Long)

    @Query("SELECT COUNT(*) FROM recording_tag_cross_ref WHERE tagId = :tagId")
    suspend fun countUsages(tagId: Long): Int

    /** Adds the cross-ref; if that was the tag's first usage this is a no-op beyond the insert (find-or-create happens in the repository). */
    @Transaction
    suspend fun attach(recordingId: Long, tagId: Long) {
        addCrossRef(RecordingTagCrossRef(recordingId, tagId))
    }

    /** Removes the cross-ref, then deletes the Tag row if that was its last usage — matches the Mac's auto-pruning (no user-facing "delete tag" action exists). */
    @Transaction
    suspend fun detachAndPruneIfOrphaned(recordingId: Long, tagId: Long) {
        removeCrossRef(recordingId, tagId)
        if (countUsages(tagId) == 0) deleteRow(tagId)
    }
}
