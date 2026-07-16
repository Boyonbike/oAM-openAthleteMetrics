package com.athletedata.openAthleteMetrics.data.repository

import android.content.Context
import com.athletedata.openAthleteMetrics.data.db.DailyContextDao
import com.athletedata.openAthleteMetrics.data.db.toEntity
import com.athletedata.openAthleteMetrics.data.db.toModel
import com.athletedata.openAthleteMetrics.data.model.DailyContext
import com.athletedata.openAthleteMetrics.glance.MetricGlanceWidget
import androidx.glance.appwidget.updateAll
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomDailyContextRepository @Inject constructor(
    private val dao: DailyContextDao,
    private val userProfileRepository: UserProfileRepository,
    @ApplicationContext private val androidContext: Context,
) : DailyContextRepository {

    override suspend fun upsert(context: DailyContext) {
        try {
            dao.upsert(context.toEntity())
        } catch (e: Exception) {
            Timber.e(e, "Failed to upsert daily context")
            throw e
        }
        // Only sync when weight was explicitly provided; null means "not entered",
        // not "clear the profile weight".
        context.weightKg?.let { kg ->
            try {
                userProfileRepository.updateWeight(kg)
            } catch (e: Exception) {
                Timber.e(e, "Failed to sync weight to user profile")
                // Swallow — a profile-sync failure must not roll back the daily context save.
            }
        }
        // Home-screen widgets only ever show "today" - an upsert for a past date can't
        // change what's rendered, so skip the refresh in that case.
        if (context.date == LocalDate.now()) {
            MetricGlanceWidget().updateAll(androidContext)
        }
    }

    override fun getForDate(date: LocalDate): Flow<DailyContext?> =
        dao.getContextForDate(date).map { it?.toModel() }

    override fun getForRange(from: LocalDate, to: LocalDate): Flow<List<DailyContext>> =
        dao.getContextsForRange(from, to).map { entities -> entities.map { it.toModel() } }

    override suspend fun getForDateOnce(date: LocalDate): DailyContext? =
        dao.getContextForDateOnce(date)?.toModel()

    override suspend fun deleteAll() {
        try {
            dao.deleteAll()
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete all daily contexts")
            throw e
        }
    }
}
