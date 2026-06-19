package com.athletedata.openAthleteMetrics.ble.sync

import com.athletedata.openAthleteMetrics.data.db.BloodPressureReadingEntity
import com.athletedata.openAthleteMetrics.data.db.toActiveCalorieEntity
import com.athletedata.openAthleteMetrics.data.db.toBloodPressureEntityOrNull
import com.athletedata.openAthleteMetrics.data.db.toGlucoseEntity
import com.athletedata.openAthleteMetrics.data.db.toHrEntity
import com.athletedata.openAthleteMetrics.data.db.toHrvEntity
import com.athletedata.openAthleteMetrics.data.db.toRespirationEntity
import com.athletedata.openAthleteMetrics.data.db.toSkinTempEntity
import com.athletedata.openAthleteMetrics.data.db.toSpO2Entity
import com.athletedata.openAthleteMetrics.data.db.toStepsEntity
import com.athletedata.openAthleteMetrics.data.db.toTotalCalorieEntity
import com.athletedata.openAthleteMetrics.data.model.MetricReading
import com.athletedata.openAthleteMetrics.data.model.MetricType
import com.athletedata.openAthleteMetrics.data.repository.ActiveCalorieReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.BloodPressureReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.GlucoseReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.HrReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.HrvReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.MetricReadingStagingRepository
import com.athletedata.openAthleteMetrics.data.repository.RespirationReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.SkinTempReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.SpO2ReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.StepsReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.TotalCalorieReadingRepository
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject
import timber.log.Timber

data class RouteAllResult(
    val newRecordsInserted: Int,
    val accumulatorUpdates: Int,
)

@Singleton
class MetricRouter @Inject constructor(
    private val hrReadingRepository: HrReadingRepository,
    private val hrvReadingRepository: HrvReadingRepository,
    private val spo2ReadingRepository: SpO2ReadingRepository,
    private val respirationReadingRepository: RespirationReadingRepository,
    private val skinTempReadingRepository: SkinTempReadingRepository,
    private val stepsReadingRepository: StepsReadingRepository,
    private val activeCalorieReadingRepository: ActiveCalorieReadingRepository,
    private val totalCalorieReadingRepository: TotalCalorieReadingRepository,
    private val bloodPressureReadingRepository: BloodPressureReadingRepository,
    private val glucoseReadingRepository: GlucoseReadingRepository,
    private val stagingRepository: MetricReadingStagingRepository,
) {
    suspend fun route(reading: MetricReading) {
        when (reading.metricType) {
            MetricType.HR -> hrReadingRepository.insert(reading.toHrEntity())
            MetricType.HRV -> hrvReadingRepository.insert(reading.toHrvEntity())
            MetricType.SPO2 -> spo2ReadingRepository.insert(reading.toSpO2Entity())
            MetricType.RESPIRATION -> respirationReadingRepository.insert(reading.toRespirationEntity())
            MetricType.SKIN_TEMP -> skinTempReadingRepository.insert(reading.toSkinTempEntity())
            MetricType.STEPS -> stepsReadingRepository.insert(reading.toStepsEntity())
            MetricType.ACTIVE_CALORIES -> activeCalorieReadingRepository.insert(reading.toActiveCalorieEntity())
            MetricType.TOTAL_CALORIES -> totalCalorieReadingRepository.insert(reading.toTotalCalorieEntity())
            MetricType.BLOOD_PRESSURE -> {
                val entity = reading.toBloodPressureEntityOrNull()
                if (entity != null) {
                    bloodPressureReadingRepository.insert(entity)
                } else {
                    Timber.w("MetricRouter: BLOOD_PRESSURE reading missing diastolic in metaJson — falling back to staging")
                    stagingRepository.insert(reading)
                }
            }
            MetricType.GLUCOSE -> glucoseReadingRepository.insert(reading.toGlucoseEntity())
            MetricType.SLEEP_STAGE -> stagingRepository.insert(reading.withPendingSleepStageFlag())
            MetricType.BATTERY -> {
                // BATTERY is handled by DeviceSyncProcessor.updateLastBatteryPct()
                // and stored in the devices table. No dedicated metric table exists.
                // Explicitly discarded here to prevent staging accumulation.
            }
            MetricType.RHR,
            MetricType.BODY_TEMP,
            MetricType.TEMP_DEVIATION,
            MetricType.VO2_MAX,
            MetricType.DISTANCE,
            MetricType.ELEVATION_GAIN,
            MetricType.ELEVATION_LOSS,
            MetricType.CALORIES,
            MetricType.BASAL_CALORIES -> {
                // No dedicated table yet for these types — staged pending a future
                // schema decision. See MetricRouter audit F-8.
                stagingRepository.insert(reading)
            }
        }
    }

    /**
     * Inserts [readings] using IGNORE-on-conflict semantics and returns a [RouteAllResult]
     * that counts only genuinely new rows. Used by processFromRaw() so that
     * recordsImported is computed the same way as the live-sync process() path.
     *
     * Staging fallbacks (SLEEP_STAGE, BP with missing diastolic, and types without a
     * dedicated table) are counted as 1 each — staging uses REPLACE so exact dedup is
     * not available, but these types are uncommon on the raw-replay path.
     */
    suspend fun routeAll(readings: List<MetricReading>): RouteAllResult {
        var newRecordsInserted = 0
        val byType = readings.groupBy { it.metricType }

        byType[MetricType.HR]?.let { list ->
            newRecordsInserted += hrReadingRepository.insertAllOrIgnore(list.map { it.toHrEntity() }).count { it != -1L }
        }

        byType[MetricType.HRV]?.let { list ->
            newRecordsInserted += hrvReadingRepository.insertAllOrIgnore(list.map { it.toHrvEntity() }).count { it != -1L }
        }

        byType[MetricType.SPO2]?.let { list ->
            newRecordsInserted += spo2ReadingRepository.insertAllOrIgnore(list.map { it.toSpO2Entity() }).count { it != -1L }
        }

        byType[MetricType.RESPIRATION]?.let { list ->
            newRecordsInserted += respirationReadingRepository.insertAllOrIgnore(list.map { it.toRespirationEntity() }).count { it != -1L }
        }

        byType[MetricType.SKIN_TEMP]?.let { list ->
            newRecordsInserted += skinTempReadingRepository.insertAllOrIgnore(list.map { it.toSkinTempEntity() }).count { it != -1L }
        }

        byType[MetricType.STEPS]?.let { list ->
            newRecordsInserted += stepsReadingRepository.insertAllOrIgnore(list.map { it.toStepsEntity() }).count { it != -1L }
        }

        byType[MetricType.ACTIVE_CALORIES]?.let { list ->
            newRecordsInserted += activeCalorieReadingRepository.insertAllOrIgnore(list.map { it.toActiveCalorieEntity() }).count { it != -1L }
        }

        byType[MetricType.TOTAL_CALORIES]?.let { list ->
            newRecordsInserted += totalCalorieReadingRepository.insertAllOrIgnore(list.map { it.toTotalCalorieEntity() }).count { it != -1L }
        }

        byType[MetricType.BLOOD_PRESSURE]?.let { list ->
            val validEntities = mutableListOf<BloodPressureReadingEntity>()
            list.forEach { reading ->
                val entity = reading.toBloodPressureEntityOrNull()
                if (entity != null) {
                    validEntities += entity
                } else {
                    Timber.w("MetricRouter: BLOOD_PRESSURE reading missing diastolic in metaJson — falling back to staging")
                    stagingRepository.insert(reading)
                    newRecordsInserted += 1
                }
            }
            if (validEntities.isNotEmpty()) {
                newRecordsInserted += bloodPressureReadingRepository.insertAllOrIgnore(validEntities).count { it != -1L }
            }
        }

        byType[MetricType.GLUCOSE]?.let { list ->
            newRecordsInserted += glucoseReadingRepository.insertAllOrIgnore(list.map { it.toGlucoseEntity() }).count { it != -1L }
        }

        byType[MetricType.SLEEP_STAGE]?.forEach { reading ->
            stagingRepository.insert(reading.withPendingSleepStageFlag())
            newRecordsInserted += 1
        }

        // BATTERY is discarded — handled by DeviceSyncProcessor, not counted.
        // Remaining types have no dedicated table yet; staged and counted as 1 each.
        val stagingFallbackTypes = setOf(
            MetricType.RHR, MetricType.BODY_TEMP, MetricType.TEMP_DEVIATION,
            MetricType.VO2_MAX, MetricType.DISTANCE, MetricType.ELEVATION_GAIN,
            MetricType.ELEVATION_LOSS, MetricType.CALORIES, MetricType.BASAL_CALORIES,
        )
        stagingFallbackTypes.forEach { type ->
            byType[type]?.forEach { reading ->
                stagingRepository.insert(reading)
                newRecordsInserted += 1
            }
        }

        return RouteAllResult(newRecordsInserted = newRecordsInserted, accumulatorUpdates = 0)
    }

    /** Inserts [readings] using REPLACE-on-conflict. Used by DeviceReprocessor to
     *  force-overwrite existing records when the driver output has been corrected. */
    suspend fun routeAllForceReplace(readings: List<MetricReading>) {
        readings.forEach { route(it) }
    }

    private fun MetricReading.withPendingSleepStageFlag(): MetricReading {
        val merged = runCatching {
            JSONObject(metaJson ?: "{}").put("pending_sleep_stage", true).toString()
        }.getOrElse { """{"pending_sleep_stage": true}""" }
        return copy(metaJson = merged)
    }
}
