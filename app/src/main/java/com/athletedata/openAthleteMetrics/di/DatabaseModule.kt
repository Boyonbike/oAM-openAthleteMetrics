package com.athletedata.openAthleteMetrics.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.athletedata.openAthleteMetrics.data.db.ActivityDao
import com.athletedata.openAthleteMetrics.data.db.AppDatabase
import com.athletedata.openAthleteMetrics.data.db.AppDatabase.Companion.LIFESTYLE_SEEDS
import com.athletedata.openAthleteMetrics.data.db.DailyContextDao
import com.athletedata.openAthleteMetrics.data.db.DailySummaryDao
import com.athletedata.openAthleteMetrics.data.db.DeviceDao
import com.athletedata.openAthleteMetrics.data.db.MetricReadingDao
import com.athletedata.openAthleteMetrics.data.db.QuestionDefinitionDao
import com.athletedata.openAthleteMetrics.data.db.QuestionResponseDao
import com.athletedata.openAthleteMetrics.data.db.RawDeviceDataDao
import com.athletedata.openAthleteMetrics.data.db.SleepSessionDao
import com.athletedata.openAthleteMetrics.data.db.SyncSessionDao
import com.athletedata.openAthleteMetrics.data.repository.ActivityRepository
import com.athletedata.openAthleteMetrics.data.repository.DailyContextRepository
import com.athletedata.openAthleteMetrics.data.repository.DailySummaryRepository
import com.athletedata.openAthleteMetrics.data.repository.DeviceRepository
import com.athletedata.openAthleteMetrics.data.repository.MetricRepository
import com.athletedata.openAthleteMetrics.data.repository.QuestionRepository
import com.athletedata.openAthleteMetrics.data.repository.RawDeviceDataRepository
import com.athletedata.openAthleteMetrics.data.repository.RoomActivityRepository
import com.athletedata.openAthleteMetrics.data.repository.RoomDailyContextRepository
import com.athletedata.openAthleteMetrics.data.repository.RoomDailySummaryRepository
import com.athletedata.openAthleteMetrics.data.repository.RoomDeviceRepository
import com.athletedata.openAthleteMetrics.data.repository.RoomMetricRepository
import com.athletedata.openAthleteMetrics.data.repository.RoomQuestionRepository
import com.athletedata.openAthleteMetrics.data.repository.RoomRawDeviceDataRepository
import com.athletedata.openAthleteMetrics.data.repository.RoomSleepRepository
import com.athletedata.openAthleteMetrics.data.repository.RoomSyncSessionRepository
import com.athletedata.openAthleteMetrics.data.repository.SleepRepository
import com.athletedata.openAthleteMetrics.data.repository.SyncSessionRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DatabaseModule {

    // ── Repository bindings ───────────────────────────────────────────────────

    @Binds @Singleton
    abstract fun bindMetricRepository(impl: RoomMetricRepository): MetricRepository

    @Binds @Singleton
    abstract fun bindSleepRepository(impl: RoomSleepRepository): SleepRepository

    @Binds @Singleton
    abstract fun bindDailySummaryRepository(impl: RoomDailySummaryRepository): DailySummaryRepository

    @Binds @Singleton
    abstract fun bindDailyContextRepository(impl: RoomDailyContextRepository): DailyContextRepository

    @Binds @Singleton
    abstract fun bindQuestionRepository(impl: RoomQuestionRepository): QuestionRepository

    @Binds @Singleton
    abstract fun bindActivityRepository(impl: RoomActivityRepository): ActivityRepository

    @Binds @Singleton
    abstract fun bindDeviceRepository(impl: RoomDeviceRepository): DeviceRepository

    @Binds @Singleton
    abstract fun bindSyncSessionRepository(impl: RoomSyncSessionRepository): SyncSessionRepository

    @Binds @Singleton
    abstract fun bindRawDeviceDataRepository(impl: RoomRawDeviceDataRepository): RawDeviceDataRepository

    companion object {

        // ── Database & DAO providers ──────────────────────────────────────────

        @Provides
        @Singleton
        fun provideAppDatabase(
            @ApplicationContext context: Context,
        ): AppDatabase = Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME,
        )
            .addMigrations(AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7)
            .fallbackToDestructiveMigration()
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    val cursor = db.query(
                        "SELECT COUNT(*) FROM question_definitions WHERE category = 'LIFESTYLE'"
                    )
                    val count = if (cursor.moveToFirst()) cursor.getInt(0) else 0
                    cursor.close()
                    if (count == 0) {
                        LIFESTYLE_SEEDS.forEach { seed ->
                            db.execSQL(
                                "INSERT OR IGNORE INTO question_definitions (name, type, category, is_visible, is_starred, sort_order) VALUES (?, ?, 'LIFESTYLE', 1, ?, ?)",
                                arrayOf(seed.name, seed.type, if (seed.isStarred) 1 else 0, seed.sortOrder),
                            )
                        }
                    }
                }
            })
            .build()

        @Provides @Singleton
        fun provideMetricReadingDao(db: AppDatabase): MetricReadingDao = db.metricReadingDao()

        @Provides @Singleton
        fun provideSleepSessionDao(db: AppDatabase): SleepSessionDao = db.sleepSessionDao()

        @Provides @Singleton
        fun provideDailySummaryDao(db: AppDatabase): DailySummaryDao = db.dailySummaryDao()

        @Provides @Singleton
        fun provideDailyContextDao(db: AppDatabase): DailyContextDao = db.dailyContextDao()

        @Provides @Singleton
        fun provideQuestionDefinitionDao(db: AppDatabase): QuestionDefinitionDao =
            db.questionDefinitionDao()

        @Provides @Singleton
        fun provideQuestionResponseDao(db: AppDatabase): QuestionResponseDao =
            db.questionResponseDao()

        @Provides @Singleton
        fun provideActivityDao(db: AppDatabase): ActivityDao = db.activityDao()

        @Provides @Singleton
        fun provideDeviceDao(db: AppDatabase): DeviceDao = db.deviceDao()

        @Provides @Singleton
        fun provideSyncSessionDao(db: AppDatabase): SyncSessionDao = db.syncSessionDao()

        @Provides @Singleton
        fun provideRawDeviceDataDao(db: AppDatabase): RawDeviceDataDao = db.rawDeviceDataDao()
    }
}
