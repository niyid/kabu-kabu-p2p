package com.techducat.kabukabu.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * KabuDatabase — single on-device SQLite database.
 *
 * All data stays on the user's device. No cloud sync. No remote backup.
 * Users can export a local JSON dump from Settings if they wish to
 * self-archive their trip history.
 */
@Database(
    entities  = [TripEntity::class, PeerReviewEntity::class],
    version   = 1,
    exportSchema = true
)
abstract class KabuDatabase : RoomDatabase() {

    abstract fun tripDao(): TripDao

    companion object {
        @Volatile private var INSTANCE: KabuDatabase? = null

        fun getInstance(context: Context): KabuDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    KabuDatabase::class.java,
                    "kabu_kabu.db"
                )
                    .fallbackToDestructiveMigration(dropAllTables = false)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
