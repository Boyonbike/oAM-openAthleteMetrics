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
            else -> stagingRepository.insert(reading)
        }
    }

    suspend fun routeAll(readings: List<MetricReading>) {
        readings.forEach { route(it) }
    }
}
