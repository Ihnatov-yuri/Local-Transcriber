package nl.ihnatov.transcriber.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [Recording::class, Segment::class, OutputDoc::class, PendingTask::class],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recordings(): RecordingDao
    abstract fun segments(): SegmentDao
    abstract fun outputs(): OutputDao
    abstract fun pendingTasks(): PendingTaskDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "transcriber.db",
            )
                // Pre-1.0 app: schema migrations are noise. Drop-and-recreate
                // is fine — recordings live in filesystem (re-importable) and
                // we don't have user data worth migrating yet. Add real
                // migrations once we ship a stable version.
                .fallbackToDestructiveMigration(false)
                .build()
    }
}
