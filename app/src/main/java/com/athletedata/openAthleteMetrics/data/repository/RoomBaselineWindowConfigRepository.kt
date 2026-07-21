package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.BaselineWindowConfigDao
import com.athletedata.openAthleteMetrics.data.db.BaselineWindowConfigEntity
import com.athletedata.openAthleteMetrics.data.model.BaselineFallbackDefaults
import com.athletedata.openAthleteMetrics.data.model.BaselineMetric
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomBaselineWindowConfigRepository @Inject constructor(
    private val dao: BaselineWindowConfigDao,
    private val settingsRepository: SettingsRepository,
) : BaselineWindowConfigRepository {

    override suspend fun getEffectiveWindow(metric: BaselineMetric): Int {
        val overrideRow = dao.observe(metric).first()
        overrideRow?.windowDays?.let { return it }
        BaselineFallbackDefaults.values[metric]?.let { return it.windowDays }
        return settingsRepository.getBaselineWindowDays().first()
    }

    override suspend fun getEffectiveMinimum(metric: BaselineMetric): Int {
        val overrideRow = dao.observe(metric).first()
        overrideRow?.minimumDays?.let { return it }
        BaselineFallbackDefaults.values[metric]?.let { return it.minimumDays }
        return GLOBAL_DEFAULT_MINIMUM_DAYS
    }

    override suspend fun setOverride(metric: BaselineMetric, windowDays: Int, minimumDays: Int) {
        dao.upsert(
            BaselineWindowConfigEntity(
                metricType = metric,
                windowDays = windowDays,
                minimumDays = minimumDays.coerceAtLeast(1),
                updatedAt = Instant.now().toEpochMilli(),
            )
        )
    }

    override suspend fun clearOverride(metric: BaselineMetric) {
        dao.delete(metric)
    }

    override fun observeOverride(metric: BaselineMetric): Flow<BaselineWindowConfigEntity?> =
        dao.observe(metric)

    private companion object {
        /** Global fallback minimum-days requirement when no override or fallback default applies. */
        const val GLOBAL_DEFAULT_MINIMUM_DAYS = 14
    }
}
