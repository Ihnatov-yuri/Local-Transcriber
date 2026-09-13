package nl.ihnatov.transcriber.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        Recording::class, Segment::class, OutputDoc::class, PendingTask::class, TranscriptVersion::class,
        Folder::class, Tag::class, RecordingTagCrossRef::class,
    ],
    version = 6,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recordings(): RecordingDao
    abstract fun segments(): SegmentDao
    abstract fun outputs(): OutputDao
    abstract fun pendingTasks(): PendingTaskDao
    abstract fun transcriptVersions(): TranscriptVersionDao
    abstract fun folders(): FolderDao
    abstract fun tags(): TagDao

    companion object {
        /**
         * First real migration this project has ever written — every
         * schema change up to here went through destructive fallback
         * (see the removed comment this replaced: "Add real migrations
         * once we ship a stable version"). Adds `transcript_versions`
         * (Phase 4 of the 2026-09 plan: versioned transcripts) without
         * touching anything else, so existing recordings/segments/outputs
         * survive the update untouched.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `transcript_versions` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `recordingId` INTEGER NOT NULL,
                        `engineId` TEXT NOT NULL,
                        `engineLabel` TEXT NOT NULL,
                        `createdAtMillis` INTEGER NOT NULL,
                        `segmentCount` INTEGER NOT NULL,
                        `segmentsJson` TEXT NOT NULL,
                        FOREIGN KEY(`recordingId`) REFERENCES `recordings`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_transcript_versions_recordingId` ON `transcript_versions` (`recordingId`)"
                )
            }
        }

        /**
         * Adds `recordings.category` (Phase 4: auto-classify — Meeting /
         * Interview / Note / Idea). A single nullable column with no
         * default-value backfill needed: existing rows just read back as
         * null ("not classified yet"), same as a recording that predates
         * this feature or whose classify call failed/was skipped.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `recordings` ADD COLUMN `category` TEXT DEFAULT NULL")
            }
        }

        /**
         * Phase 6: folders and tags. Adds `recordings.folderId` (plain
         * nullable column, no FK constraint — SQLite can't add one to an
         * existing populated table without a full recreate-copy-drop
         * dance, and every folder-delete path already goes through
         * [FolderDao.delete], which unfiles recordings itself before
         * dropping the row; a DB-level constraint would be redundant
         * belt-and-suspenders here, not a correctness requirement), plus
         * the new `folders`/`tags`/`recording_tag_cross_ref` tables. The
         * cross-ref table DOES get real foreign keys (cascade both ways)
         * since it's brand new — nothing existing to migrate around.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `recordings` ADD COLUMN `folderId` INTEGER DEFAULT NULL")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `folders` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `sortOrder` INTEGER NOT NULL,
                        `createdAtMillis` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `tags` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `createdAtMillis` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `recording_tag_cross_ref` (
                        `recordingId` INTEGER NOT NULL,
                        `tagId` INTEGER NOT NULL,
                        PRIMARY KEY(`recordingId`, `tagId`),
                        FOREIGN KEY(`recordingId`) REFERENCES `recordings`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`tagId`) REFERENCES `tags`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_recording_tag_cross_ref_tagId` ON `recording_tag_cross_ref` (`tagId`)"
                )
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "transcriber.db",
            )
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build()
    }
}
