package com.techducat.kabusquared.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * KabuDatabase — single on-device SQLite database.
 *
 * All data stays on the user's device. No cloud sync. No remote backup.
 * Users can export a local JSON dump from Settings if they wish to
 * self-archive their trip history.
 *
 * ## Migration policy
 *
 * Trip history is the *only* persistent user data in this app.  Silently
 * wiping it on a schema bump ([fallbackToDestructiveMigration]) would
 * delete records that cannot be recovered from any server (there is none).
 *
 * All schema changes MUST be covered by an explicit [Migration] or an
 * [AutoMigration] annotation.  [fallbackToDestructiveMigration] is
 * intentionally absent — a missing migration causes a build-time error
 * (Room schema validation) rather than a silent data loss at runtime.
 *
 * To add a migration:
 *   1. Bump [version] by 1.
 *   2. Add an [AutoMigration] annotation if the change is additive (new
 *      columns with defaults, new tables), or write an explicit [Migration]
 *      object and register it in [MIGRATIONS].
 *   3. Run `./gradlew :app:kaptDebugKotlin` and commit the generated
 *      schema JSON in `app/schemas/`.
 */
@Database(
    entities     = [TripEntity::class, PeerReviewEntity::class],
    version      = 1,
    exportSchema = true
)
abstract class KabuDatabase : RoomDatabase() {

    abstract fun tripDao(): TripDao

    companion object {
        @Volatile private var INSTANCE: KabuDatabase? = null

        /**
         * Explicit migration list.  Add new [Migration] objects here as the
         * schema evolves.  Do NOT use [fallbackToDestructiveMigration] — see
         * the class KDoc above.
         */
        private val MIGRATIONS = arrayOf<Migration>(
            // Example (keep for reference; remove or replace for real migrations):
            // object : Migration(1, 2) {
            //     override fun migrate(db: SupportSQLiteDatabase) {
            //         db.execSQL("ALTER TABLE trips ADD COLUMN dropoff_note TEXT NOT NULL DEFAULT ''")
            //     }
            // }
        )

        fun getInstance(context: Context): KabuDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    KabuDatabase::class.java,
                    "kabu_kabu.db"
                )
                    .addMigrations(*MIGRATIONS)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
