package org.koin.sample.database.di

import android.content.Context
import androidx.room.Room
import org.koin.core.annotation.Module
import org.koin.core.annotation.Singleton
import org.koin.sample.database.AppDatabase

@Module
internal object DatabaseModule {

    // createdAtStart: open the Room database eagerly at startKoin rather than on first
    // injection (exercises per-definition createdAtStart on a @Singleton function — koin#2425).
    @Singleton(createdAtStart = true)
    fun providesDatabase(context: Context): AppDatabase =
        Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "stress-test-database",
        ).build()
}
