package com.athletedata.openAthleteMetrics.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.athletedata.openAthleteMetrics.data.db.ActiveCalorieReadingDao
import com.athletedata.openAthleteMetrics.data.db.ActivityDao
import com.athletedata.openAthleteMetrics.data.db.AppDatabase
import com.athletedata.openAthleteMetrics.data.db.BaselineWindowConfigDao
import com.athletedata.openAthleteMetrics.data.db.BloodPressureReadingDao
import com.athletedata.openAthleteMetrics.data.db.DailyContextDao
import com.athletedata.openAthleteMetrics.data.db.DailySummaryDao
import com.athletedata.openAthleteMetrics.data.db.DeviceDao
import com.athletedata.openAthleteMetrics.data.db.GlucoseReadingDao
import com.athletedata.openAthleteMetrics.data.db.HrReadingDao
import com.athletedata.openAthleteMetrics.data.db.HrvReadingDao
import com.athletedata.openAthleteMetrics.data.db.MetricDailyStatsDao
import com.athletedata.openAthleteMetrics.data.db.MetricReadingStagingDao
import com.athletedata.openAthleteMetrics.data.db.QuestionDefinitionDao
import com.athletedata.openAthleteMetrics.data.db.QuestionResponseDao
import com.athletedata.openAthleteMetrics.data.db.RawDeviceDataDao
import com.athletedata.openAthleteMetrics.data.db.RespirationReadingDao
import com.athletedata.openAthleteMetrics.data.db.SkinTempReadingDao
import com.athletedata.openAthleteMetrics.data.db.SleepSessionDao
import com.athletedata.openAthleteMetrics.data.db.SleepStageDao
import com.athletedata.openAthleteMetrics.data.db.SpO2ReadingDao
import com.athletedata.openAthleteMetrics.data.db.StepsReadingDao
import com.athletedata.openAthleteMetrics.data.db.SyncSessionDao
import com.athletedata.openAthleteMetrics.data.db.TotalCalorieReadingDao
import com.athletedata.openAthleteMetrics.data.repository.ActiveCalorieReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.ActivityRepository
import com.athletedata.openAthleteMetrics.data.repository.BaselineWindowConfigRepository
import com.athletedata.openAthleteMetrics.data.repository.BloodPressureReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.DailyContextRepository
import com.athletedata.openAthleteMetrics.data.repository.DailySummaryRepository
import com.athletedata.openAthleteMetrics.data.repository.DeviceRepository
import com.athletedata.openAthleteMetrics.data.repository.GlucoseReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.HrReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.HrvReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.MetricReadingStagingRepository
import com.athletedata.openAthleteMetrics.data.repository.QuestionRepository
import com.athletedata.openAthleteMetrics.data.repository.RawDeviceDataRepository
import com.athletedata.openAthleteMetrics.data.repository.RespirationReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.RoomActiveCalorieReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.RoomActivityRepository
import com.athletedata.openAthleteMetrics.data.repository.RoomBaselineWindowConfigRepository
import com.athletedata.openAthleteMetrics.data.repository.RoomBloodPressureReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.RoomDailyContextRepository
import com.athletedata.openAthleteMetrics.data.repository.RoomDailySummaryRepository
import com.athletedata.openAthleteMetrics.data.repository.RoomDeviceRepository
import com.athletedata.openAthleteMetrics.data.repository.RoomGlucoseReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.RoomHrReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.RoomHrvReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.RoomMetricReadingStagingRepository
import com.athletedata.openAthleteMetrics.data.repository.RoomQuestionRepository
import com.athletedata.openAthleteMetrics.data.repository.RoomRawDeviceDataRepository
import com.athletedata.openAthleteMetrics.data.repository.RoomRespirationReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.RoomSkinTempReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.RoomSleepRepository
import com.athletedata.openAthleteMetrics.data.repository.RoomSleepStageRepository
import com.athletedata.openAthleteMetrics.data.repository.RoomSpO2ReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.RoomStepsReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.RoomSyncSessionRepository
import com.athletedata.openAthleteMetrics.data.repository.RoomTotalCalorieReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.SkinTempReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.SleepRepository
import com.athletedata.openAthleteMetrics.data.repository.SleepStageRepository
import com.athletedata.openAthleteMetrics.data.repository.SpO2ReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.StepsReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.SyncSessionRepository
import com.athletedata.openAthleteMetrics.data.repository.TotalCalorieReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.WidgetLayoutRepository
import com.athletedata.openAthleteMetrics.data.repository.RoomWidgetLayoutRepository
import com.athletedata.openAthleteMetrics.data.db.WidgetLayoutDao
import com.athletedata.openAthleteMetrics.data.db.UserProfileDao
import com.athletedata.openAthleteMetrics.data.repository.UserProfileRepository
import com.athletedata.openAthleteMetrics.data.repository.RoomUserProfileRepository
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
    abstract fun bindMetricReadingStagingRepository(impl: RoomMetricReadingStagingRepository): MetricReadingStagingRepository

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

    @Binds @Singleton
    abstract fun bindHrReadingRepository(impl: RoomHrReadingRepository): HrReadingRepository

    @Binds @Singleton
    abstract fun bindHrvReadingRepository(impl: RoomHrvReadingRepository): HrvReadingRepository

    @Binds @Singleton
    abstract fun bindSpO2ReadingRepository(impl: RoomSpO2ReadingRepository): SpO2ReadingRepository

    @Binds @Singleton
    abstract fun bindRespirationReadingRepository(impl: RoomRespirationReadingRepository): RespirationReadingRepository

    @Binds @Singleton
    abstract fun bindSkinTempReadingRepository(impl: RoomSkinTempReadingRepository): SkinTempReadingRepository

    @Binds @Singleton
    abstract fun bindStepsReadingRepository(impl: RoomStepsReadingRepository): StepsReadingRepository

    @Binds @Singleton
    abstract fun bindBloodPressureReadingRepository(impl: RoomBloodPressureReadingRepository): BloodPressureReadingRepository

    @Binds @Singleton
    abstract fun bindGlucoseReadingRepository(impl: RoomGlucoseReadingRepository): GlucoseReadingRepository

    @Binds @Singleton
    abstract fun bindActiveCalorieReadingRepository(impl: RoomActiveCalorieReadingRepository): ActiveCalorieReadingRepository

    @Binds @Singleton
    abstract fun bindTotalCalorieReadingRepository(impl: RoomTotalCalorieReadingRepository): TotalCalorieReadingRepository

    @Binds @Singleton
    abstract fun bindSleepStageRepository(impl: RoomSleepStageRepository): SleepStageRepository

    @Binds @Singleton
    abstract fun bindWidgetLayoutRepository(impl: RoomWidgetLayoutRepository): WidgetLayoutRepository

    @Binds @Singleton
    abstract fun bindUserProfileRepository(impl: RoomUserProfileRepository): UserProfileRepository

    @Binds @Singleton
    abstract fun bindBaselineWindowConfigRepository(impl: RoomBaselineWindowConfigRepository): BaselineWindowConfigRepository

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
            .addMigrations(
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9,
                AppDatabase.MIGRATION_9_10,
                AppDatabase.MIGRATION_10_11,
                AppDatabase.MIGRATION_11_12,
                AppDatabase.MIGRATION_12_13,
                AppDatabase.MIGRATION_13_14,
                AppDatabase.MIGRATION_14_15, // BP-GLUCOSE-SUMMARY
                AppDatabase.MIGRATION_15_16,
                AppDatabase.MIGRATION_16_17,
                AppDatabase.MIGRATION_17_18,
                AppDatabase.MIGRATION_18_19,
                AppDatabase.MIGRATION_19_20,
                AppDatabase.MIGRATION_20_21,
                AppDatabase.MIGRATION_21_22,
            )
            // MIGRATION_1_2 was never authored and the true v1->v2 schema diff is undocumented
            // (see AppDatabase's schema-history comment). Rather than guess at it, destructively
            // reset only databases still stuck on the original v1 schema so they recover via
            // onCreate seeding below instead of crashing on open with "no migration found".
            .fallbackToDestructiveMigrationFrom(dropAllTables = true, 1)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    super.onCreate(db)
                    // Migrations never run for a brand-new install (Room creates tables at the
                    // current version directly), so widget_layout is otherwise left empty and
                    // the dashboard has no default widgets. Seed the same default layout that
                    // MIGRATION_18_19 seeds for upgrading installs.
                    AppDatabase.DEFAULT_WIDGET_LAYOUT.forEach { (template, colSpan, rowSpan, order) ->
                        db.execSQL(
                            "INSERT INTO widget_layout (template_id, col_span, row_span, sequence_order) VALUES (?, ?, ?, ?)",
                            arrayOf(template, colSpan, rowSpan, order)
                        )
                    }
                }
            })
            .build()

        @Provides @Singleton
        fun provideMetricReadingStagingDao(db: AppDatabase): MetricReadingStagingDao = db.metricReadingStagingDao()

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

        @Provides @Singleton
        fun provideHrReadingDao(db: AppDatabase): HrReadingDao = db.hrReadingDao()

        @Provides @Singleton
        fun provideHrvReadingDao(db: AppDatabase): HrvReadingDao = db.hrvReadingDao()

        @Provides @Singleton
        fun provideSpO2ReadingDao(db: AppDatabase): SpO2ReadingDao = db.spO2ReadingDao()

        @Provides @Singleton
        fun provideRespirationReadingDao(db: AppDatabase): RespirationReadingDao = db.respirationReadingDao()

        @Provides @Singleton
        fun provideSkinTempReadingDao(db: AppDatabase): SkinTempReadingDao = db.skinTempReadingDao()

        @Provides @Singleton
        fun provideStepsReadingDao(db: AppDatabase): StepsReadingDao = db.stepsReadingDao()

        @Provides @Singleton
        fun provideBloodPressureReadingDao(db: AppDatabase): BloodPressureReadingDao = db.bloodPressureReadingDao()

        @Provides @Singleton
        fun provideGlucoseReadingDao(db: AppDatabase): GlucoseReadingDao = db.glucoseReadingDao()

        @Provides @Singleton
        fun provideActiveCalorieReadingDao(db: AppDatabase): ActiveCalorieReadingDao = db.activeCalorieReadingDao()

        @Provides @Singleton
        fun provideTotalCalorieReadingDao(db: AppDatabase): TotalCalorieReadingDao = db.totalCalorieReadingDao()

        @Provides @Singleton
        fun provideSleepStageDao(db: AppDatabase): SleepStageDao = db.sleepStageDao()

        @Provides @Singleton
        fun provideWidgetLayoutDao(db: AppDatabase): WidgetLayoutDao = db.widgetLayoutDao()

        @Provides @Singleton
        fun provideUserProfileDao(db: AppDatabase): UserProfileDao = db.userProfileDao()

        @Provides @Singleton
        fun provideBaselineWindowConfigDao(db: AppDatabase): BaselineWindowConfigDao = db.baselineWindowConfigDao()

        @Provides @Singleton
        fun provideMetricDailyStatsDao(db: AppDatabase): MetricDailyStatsDao = db.metricDailyStatsDao()
    }
}
