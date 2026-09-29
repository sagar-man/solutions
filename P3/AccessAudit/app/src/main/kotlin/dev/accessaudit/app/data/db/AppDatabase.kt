package dev.accessaudit.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ScanEntity::class,
        AppEntity::class,
        AccessEventEntity::class,
        FindingEntity::class,
        AssessmentEntity::class,
        SuppressionEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
public abstract class AppDatabase : RoomDatabase() {
    public abstract fun scans(): ScanDao
    public abstract fun apps(): AppDao
    public abstract fun access(): AccessDao
    public abstract fun findings(): FindingDao
    public abstract fun assessments(): AssessmentDao
    public abstract fun suppressions(): SuppressionDao

    public companion object {
        @Volatile
        private var instance: AppDatabase? = null

        public fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "access-audit.db",
            )
                // This database is a local audit log, not a source of truth we
                // must migrate at all costs; losing history on a schema change is
                // preferable to refusing to start. Real migrations get added here
                // as the schema evolves.
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}
