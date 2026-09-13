package nl.ihnatov.transcriber.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Flat, user-created folder — a [Recording] lives in at most one (see
 * [Recording.folderId]). Port of the Mac app's SwiftData `Folder` model
 * (`Data/Schema.swift`), with two deliberate platform adaptations:
 *
 *   - `Long` autoGenerate id instead of `UUID`, matching every other
 *     entity in this Room schema (Recording, Segment, ... all use Long).
 *   - Name uniqueness is enforced in [RecordingRepository], case-
 *     insensitively, same as the Mac — NOT a `UNIQUE` column constraint.
 *     The Mac's own doc comment explains why: a unique constraint turns
 *     a duplicate insert into a silent upsert instead of a rejected one.
 *
 * No color or icon field — the Mac model doesn't have one either.
 */
@Entity(tableName = "folders")
data class Folder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sortOrder: Int = 0,
    val createdAtMillis: Long,
)

/**
 * Free-form tag, many-to-many with [Recording] via
 * [RecordingTagCrossRef] — Room has no SwiftData-style implicit
 * relationship, so (unlike the Mac's schema, which never names a join
 * table) this project needs one as a real entity. Tags have no
 * independent lifecycle: [RecordingRepository.removeTag] deletes a Tag
 * row the moment its last cross-ref is removed (see that function's doc
 * comment) — there is no user-facing "delete tag" action, matching the
 * Mac exactly.
 */
@Entity(tableName = "tags")
data class Tag(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAtMillis: Long,
)

/** Join row for the Recording↔Tag many-to-many. Cascade both ways: the cross-ref is meaningless once either side is gone. */
@Entity(
    tableName = "recording_tag_cross_ref",
    primaryKeys = ["recordingId", "tagId"],
    foreignKeys = [
        ForeignKey(entity = Recording::class, parentColumns = ["id"], childColumns = ["recordingId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Tag::class, parentColumns = ["id"], childColumns = ["tagId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("tagId")],
)
data class RecordingTagCrossRef(
    val recordingId: Long,
    val tagId: Long,
)
