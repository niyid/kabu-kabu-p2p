package com.techducat.kabusquared.di

import android.content.Context
import androidx.room.Room
import com.techducat.kabusquared.db.KabuDatabase
import com.techducat.kabusquared.db.TripDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * AppModule — Hilt dependency injection bindings for Kabu-Kabu.
 *
 * All provided singletons are on-device only:
 *  - [KabuDatabase]  — Room SQLite, never synced to a cloud DB.
 *  - [TripDao]       — DAO for on-device trip history.
 *
 * There is intentionally no "ApiService" or "RemoteDataSource" binding
 * here — this app has no central server to call.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideKabuDatabase(
        @ApplicationContext context: Context
    ): KabuDatabase = KabuDatabase.getInstance(context)

    @Provides
    @Singleton
    fun provideTripDao(db: KabuDatabase): TripDao = db.tripDao()
}
