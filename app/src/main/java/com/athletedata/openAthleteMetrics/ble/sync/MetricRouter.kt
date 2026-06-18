package com.athletedata.openAthleteMetrics.ble.sync

import com.athletedata.openAthleteMetrics.data.db.ActiveCalorieReadingEntity
import com.athletedata.openAthleteMetrics.data.db.BloodPressureReadingEntity
import com.athletedata.openAthleteMetrics.data.db.GlucoseReadingEntity
import com.athletedata.openAthleteMetrics.data.db.HrReadingEntity
import com.athletedata.openAthleteMetrics.data.db.HrvReadingEntity
import com.athletedata.openAthleteMetrics.data.db.RespirationReadingEntity
import com.athletedata.openAthleteMetrics.data.db.SkinTempReadingEntity
import com.athletedata.openAthleteMetrics.data.db.SpO2ReadingEntity
import com.athletedata.openAthleteMetrics.data.db.StepsReadingEntity
import com.athletedata.openAthleteMetrics.data.db.TotalCalorieReadingEntity
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
            MetricType.HR -> hrReadingRepository.insert(
                HrReadingEntity(
                    recordedAt = reading.recordedAt, createdAt = reading.createdAt,
                    source = reading.source, driverId = reading.driverId,
                    confidence = reading.confidence, metaJson = reading.metaJson,
                    bpm = reading.value.toInt(),
                )
            )
            MetricType.HRV -> hrvReadingRepository.insert(
                HrvReadingEntity(
                    recordedAt = reading.recordedAt, createdAt = reading.createdAt,
                    source = reading.source, driverId = reading.driverId,
                    confidence = reading.confidence, metaJson = reading.metaJson,
                    rmssdMs = reading.value,
                    computedByVersion = 1,
                )
            )
            MetricType.SPO2 -> spo2ReadingRepository.insert(
                SpO2ReadingEntity(
                    recordedAt = reading.recordedAt, createdAt = reading.createdAt,
                    source = reading.source, driverId = reading.driverId,
                    confidence = reading.confidence, metaJson = reading.metaJson,
                    percentage = reading.value,
                )
            )
            MetricType.RESPIRATION -> respirationReadingRepository.insert(
                RespirationReadingEntity(
                    recordedAt = reading.recordedAt, createdAt = reading.createdAt,
                    source = reading.source, driverId = reading.driverId,
                    confidence = reading.confidence, metaJson = reading.metaJson,
                    breathsPerMinute = reading.value,
                )
            )
            MetricType.SKIN_TEMP -> skinTempReadingRepository.insert(
                SkinTempReadingEntity(
                    recordedAt = reading.recordedAt, createdAt = reading.createdAt,
                    source = reading.source, driverId = reading.driverId,
                    confidence = reading.confidence, metaJson = reading.metaJson,
                    celsius = reading.value,
                )
            )
            MetricType.STEPS -> stepsReadingRepository.insert(
                StepsReadingEntity(
                    recordedAt = reading.recordedAt, createdAt = reading.createdAt,
                    source = reading.source, driverId = reading.driverId,
                    confidence = reading.confidence, metaJson = reading.metaJson,
                    cumulativeSteps = reading.value.toInt(),
                )
            )
            MetricType.ACTIVE_CALORIES -> activeCalorieReadingRepository.insert(
                ActiveCalorieReadingEntity(
                    recordedAt = reading.recordedAt, createdAt = reading.createdAt,
                    source = reading.source, driverId = reading.driverId,
                    confidence = reading.confidence, metaJson = reading.metaJson,
                    calories = reading.value,
                )
            )
            MetricType.TOTAL_CALORIES -> totalCalorieReadingRepository.insert(
                TotalCalorieReadingEntity(
                    recordedAt = reading.recordedAt, createdAt = reading.createdAt,
                    source = reading.source, driverId = reading.driverId,
                    confidence = reading.confidence, metaJson = reading.metaJson,
                    calories = reading.value,
                )
            )
            MetricType.BLOOD_PRESSURE -> {
                val diastolic = runCatching {
                    JSONObject(reading.metaJson ?: "").getInt("diastolic")
                }.getOrNull()
                if (diastolic != null) {
                    bloodPressureReadingRepository.insert(
                        BloodPressureReadingEntity(
                            recordedAt = reading.recordedAt, createdAt = reading.createdAt,
                            source = reading.source, driverId = reading.driverId,
                            confidence = reading.confidence, metaJson = reading.metaJson,
                            systolic = reading.value.toInt(),
                            diastolic = diastolic,
                        )
                    )
                } else {
                    Timber.w("MetricRouter: BLOOD_PRESSURE reading missing diastolic in metaJson — falling back to staging")
                    stagingRepository.insert(reading)
                }
            }
            MetricType.GLUCOSE -> glucoseReadingRepository.insert(
                GlucoseReadingEntity(
                    recordedAt = reading.recordedAt, createdAt = reading.createdAt,
                    source = reading.source, driverId = reading.driverId,
                    confidence = reading.confidence, metaJson = reading.metaJson,
                    value = reading.value,
                    unit = reading.unit,
                )
            )
            MetricType.SLEEP_STAGE -> {
                val merged = runCatching {
                    val base = JSONObject(reading.metaJson ?: "{}")
                    base.put("pending_sleep_stage", true)
                    base.toString()
                }.getOrElse { """{"pending_sleep_stage": true}""" }
                stagingRepository.insert(reading.copy(metaJson = merged))
            }
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
            newRecordsInserted += hrReadingRepository.insertAllOrIgnore(list.map {
                HrReadingEntity(
                    recordedAt = it.recordedAt, createdAt = it.createdAt,
                    source = it.source, driverId = it.driverId,
                    confidence = it.confidence, metaJson = it.metaJson,
                    bpm = it.value.toInt(),
                )
            }).count { it != -1L }
        }

        byType[MetricType.HRV]?.let { list ->
            newRecordsInserted += hrvReadingRepository.insertAllOrIgnore(list.map {
                HrvReadingEntity(
                    recordedAt = it.recordedAt, createdAt = it.createdAt,
                    source = it.source, driverId = it.driverId,
                    confidence = it.confidence, metaJson = it.metaJson,
                    rmssdMs = it.value,
                    computedByVersion = 1,
                )
            }).count { it != -1L }
        }

        byType[MetricType.SPO2]?.let { list ->
            newRecordsInserted += spo2ReadingRepository.insertAllOrIgnore(list.map {
                SpO2ReadingEntity(
                    recordedAt = it.recordedAt, createdAt = it.createdAt,
                    source = it.source, driverId = it.driverId,
                    confidence = it.confidence, metaJson = it.metaJson,
                    percentage = it.value,
                )
            }).count { it != -1L }
        }

        byType[MetricType.RESPIRATION]?.let { list ->
            newRecordsInserted += respirationReadingRepository.insertAllOrIgnore(list.map {
                RespirationReadingEntity(
                    recordedAt = it.recordedAt, createdAt = it.createdAt,
                    source = it.source, driverId = it.driverId,
                    confidence = it.confidence, metaJson = it.metaJson,
                    breathsPerMinute = it.value,
                )
            }).count { it != -1L }
        }

        byType[MetricType.SKIN_TEMP]?.let { list ->
            newRecordsInserted += skinTempReadingRepository.insertAllOrIgnore(list.map {
                SkinTempReadingEntity(
                    recordedAt = it.recordedAt, createdAt = it.createdAt,
                    source = it.source, driverId = it.driverId,
                    confidence = it.confidence, metaJson = it.metaJson,
                    celsius = it.value,
                )
            }).count { it != -1L }
        }

        byType[MetricType.STEPS]?.let { list ->
            newRecordsInserted += stepsReadingRepository.insertAllOrIgnore(list.map {
                StepsReadingEntity(
                    recordedAt = it.recordedAt, createdAt = it.createdAt,
                    source = it.source, driverId = it.driverId,
                    confidence = it.confidence, metaJson = it.metaJson,
                    cumulativeSteps = it.value.toInt(),
                )
            }).count { it != -1L }
        }

        byType[MetricType.ACTIVE_CALORIES]?.let { list ->
            newRecordsInserted += activeCalorieReadingRepository.insertAllOrIgnore(list.map {
                ActiveCalorieReadingEntity(
                    recordedAt = it.recordedAt, createdAt = it.createdAt,
                    source = it.source, driverId = it.driverId,
                    confidence = it.confidence, metaJson = it.metaJson,
                    calories = it.value,
                )
            }).count { it != -1L }
        }

        byType[MetricType.TOTAL_CALORIES]?.let { list ->
            newRecordsInserted += totalCalorieReadingRepository.insertAllOrIgnore(list.map {
                TotalCalorieReadingEntity(
                    recordedAt = it.recordedAt, createdAt = it.createdAt,
                    source = it.source, driverId = it.driverId,
                    confidence = it.confidence, metaJson = it.metaJson,
                    calories = it.value,
                )
            }).count { it != -1L }
        }

        byType[MetricType.BLOOD_PRESSURE]?.let { list ->
            val validEntities = mutableListOf<BloodPressureReadingEntity>()
            list.forEach { reading ->
                val diastolic = runCatching {
                    JSONObject(reading.metaJson ?: "").getInt("diastolic")
                }.getOrNull()
                if (diastolic != null) {
                    validEntities += BloodPressureReadingEntity(
                        recordedAt = reading.recordedAt, createdAt = reading.createdAt,
                        source = reading.source, driverId = reading.driverId,
                        confidence = reading.confidence, metaJson = reading.metaJson,
                        systolic = reading.value.toInt(),
                        diastolic = diastolic,
                    )
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
            newRecordsInserted += glucoseReadingRepository.insertAllOrIgnore(list.map {
                GlucoseReadingEntity(
                    recordedAt = it.recordedAt, createdAt = it.createdAt,
                    source = it.source, driverId = it.driverId,
                    confidence = it.confidence, metaJson = it.metaJson,
                    value = it.value,
                    unit = it.unit,
                )
            }).count { it != -1L }
        }

        byType[MetricType.SLEEP_STAGE]?.forEach { reading ->
            val merged = runCatching {
                val base = JSONObject(reading.metaJson ?: "{}")
                base.put("pending_sleep_stage", true)
                base.toString()
            }.getOrElse { """{"pending_sleep_stage": true}""" }
            stagingRepository.insert(reading.copy(metaJson = merged))
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
}
