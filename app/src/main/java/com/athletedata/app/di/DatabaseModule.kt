package com.athletedata.app.di

import android.content.Context
import androidx.room.Room
import com.athletedata.app.data.db.AppDatabase
import com.athletedata.app.data.db.DailyContextDao
import com.athletedata.app.data.db.DailySummaryDao
import com.athletedata.app.data.db.MetricReadingDao
import com.athletedata.app.data.db.SleepSessionDao
import com.athletedata.app.data.repository.DailyContextRepository
import com.athletedata.app.data.repository.DailySummaryRepository
import com.athletedata.app.data.repository.MetricRepository
import com.athletedata.app.data.repository.RoomDailyContextRepository
import com.athletedata.app.data.repository.RoomDailySummaryRepository
import com.athletedata.app.data.repository.RoomMetricRepository
import com.athletedata.app.data.repository.RoomSleepRepository
import com.athletedata.app.data.repository.SleepRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module that provides the Room database, all DAOs, and binds
 * repository interfaces to their Room-backed implementations.
 *
 * Installed in [SingletonComponent] so the database and all singletons
 * live for the lifetime of the application process.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DatabaseModule {

    // ── Repository bindings ───────────────────────────────────────────────────
    // @Binds tells Hilt: "when something asks for the interface, give it the impl."
    // The impl's @Inject constructor provides all its dependencies automatically.

    /** Binds [MetricRepository] → [RoomMetricRepository]. */
    @Binds @Singleton
    abstract fun bindMetricRepository(impl: RoomMetricRepository): MetricRepository

    /** Binds [SleepRepository] → [RoomSleepRepository]. */
    @Binds @Singleton
    abstract fun bindSleepRepository(impl: RoomSleepRepository): SleepRepository

    /** Binds [DailySummaryRepository] → [RoomDailySummaryRepository]. */
    @Binds @Singleton
    abstract fun bindDailySummaryRepository(impl: RoomDailySummaryRepository): DailySummaryRepository

    /** Binds [DailyContextRepository] → [RoomDailyContextRepository]. */
    @Binds @Singleton
    abstract fun bindDailyContextRepository(impl: RoomDailyContextRepository): DailyContextRepository

    companion object {

        // ── Database & DAO providers ──────────────────────────────────────────
        // @Provides is used here (not @Binds) because we're constructing objects
        // ourselves rather than delegating to an @Inject constructor.

        /**
         * Provides the [AppDatabase] singleton.
         * Room creates the SQLite file on first access; subsequent calls return
         * the same instance via Hilt's singleton scope.
         */
        @Provides
        @Singleton
        fun provideAppDatabase(
            @ApplicationContext context: Context,
        ): AppDatabase = Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME,
        ).build()

        /**
         * Provides [MetricReadingDao] extracted from the singleton [AppDatabase].
         * Injected into [RoomMetricRepository].
         */
        @Provides
        @Singleton
        fun provideMetricReadingDao(db: AppDatabase): MetricReadingDao =
            db.metricReadingDao()

        /**
         * Provides [SleepSessionDao] extracted from the singleton [AppDatabase].
         * Injected into [RoomSleepRepository].
         */
        @Provides
        @Singleton
        fun provideSleepSessionDao(db: AppDatabase): SleepSessionDao =
            db.sleepSessionDao()

        /**
         * Provides [DailySummaryDao] extracted from the singleton [AppDatabase].
         * Injected into [RoomDailySummaryRepository] and DailySummaryWorker.
         */
        @Provides
        @Singleton
        fun provideDailySummaryDao(db: AppDatabase): DailySummaryDao =
            db.dailySummaryDao()

        /**
         * Provides [DailyContextDao] extracted from the singleton [AppDatabase].
         * Injected into [RoomDailyContextRepository].
         */
        @Provides
        @Singleton
        fun provideDailyContextDao(db: AppDatabase): DailyContextDao =
            db.dailyContextDao()
    }
}
