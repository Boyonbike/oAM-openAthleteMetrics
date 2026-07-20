package com.athletedata.openAthleteMetrics.ble.sync

import com.athletedata.openAthleteMetrics.ble.driver.CaloriesMode // CALORIES-MODE
import com.athletedata.openAthleteMetrics.ble.driver.StepsMode // STEPS-MODE
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
import java.time.ZoneId // STEPS-MODE
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject
import timber.log.Timber

private const val TAG = "data-pathway-tracker" // DPT

// CHANGED — STAGE-4 MAGNITUDE BOUNDS: generic, device-agnostic physiological rate-of-change
// ceilings for DELTA-mode accumulator metrics (STEPS / ACTIVE_CALORIES), expressed per
// minute. These bound how fast a human can plausibly generate the metric, with margin above
// realistic elite-effort sustained rates, so they hold for any driver regardless of its sync
// cadence. They exist to catch a stale/carried-over BLE frame value (e.g. a leftover reading
// from when a device wasn't worn) that passes every other check because it's a well-formed,
// finite, in-range-timestamp number — see incident 2026-06-22 (STEPS delta=64767 against a
// normal 10-850/5min range, correlated ACTIVE_CALORIES=501.83). The actual per-sync-interval
// ceiling used by implausibleDeltaReason() is this rate scaled by the calling driver's
// declared syncIntervalMs (see DEFAULT_SYNC_INTERVAL_MS below for drivers that don't declare
// one):
//   STEPS: elite sprint cadence (~240/min) plus headroom → 400/min ceiling.
//   ACTIVE_CALORIES: ~30 kcal/min sustained by a large/fit athlete at extreme intensity is
//          already generous.
// Only meaningful for DELTA-mode readings; a driver reporting ABSOLUTE running totals is
// exempt (see implausibleDeltaReason()). DISTANCE is intentionally not included here — it has
// no dedicated table/consumer in the app today (staging only), so filtering it is out of scope.
private val MAX_PLAUSIBLE_RATE_PER_MINUTE: Map<MetricType, Double> = mapOf(
    MetricType.STEPS to 400.0,
    MetricType.ACTIVE_CALORIES to 30.0,
)

// CHANGED: fallback sync interval for drivers that declare no syncIntervalMs in their
// manifest — matches the fixed 5-minute interval this bound was originally hardcoded
// against, preserving existing behavior for drivers that predate the syncIntervalMs field.
private const val DEFAULT_SYNC_INTERVAL_MS: Long = 300_000L

// CHANGED: scales the per-minute rate ceiling by the effective sync interval to get an
// absolute per-sync-interval delta ceiling. Returns null for metric types with no declared
// rate (not subject to magnitude filtering).
private fun maxPlausibleDelta(metricType: MetricType, syncIntervalMs: Long): Double? =
    MAX_PLAUSIBLE_RATE_PER_MINUTE[metricType]?.let { it * syncIntervalMs / 60_000.0 }

// REMOVED: dead-code-archaeology

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
    // DATA-PATHWAY: route() performs no content transformation beyond STAGE-4 filtering.
    // Two kinds of hard reject happen here: BATTERY (always discarded — handled by
    // DeviceSyncProcessor and stored in the devices table) and implausible-magnitude
    // DELTA-mode accumulator readings (see implausibleDeltaReason()). All other types are
    // routed to their table or staging.
    suspend fun route( // METRIC-OWNERSHIP
        reading: MetricReading,
        stepsMode: StepsMode = StepsMode.DELTA, // STEPS-MODE
        caloriesMode: CaloriesMode = CaloriesMode.DELTA, // CALORIES-MODE
        syncIntervalMs: Long? = null, // CHANGED
    ) {
        if (reading.metricType == MetricType.BATTERY) { // DPT
            Timber.tag(TAG).d("[STAGE-4 FILTER] DROP metric=%s timestamp=%d reason=battery reading discarded — stored in device metadata, not metric tables", reading.metricType, reading.recordedAt.toEpochMilli()) // DPT
        } else { // DPT
            val implausibleReason = implausibleDeltaReason(reading, stepsMode, caloriesMode, syncIntervalMs) // DPT / CHANGED
            if (implausibleReason != null) { // DPT
                Timber.tag(TAG).w("[STAGE-4 FILTER] DROP metric=%s timestamp=%d value=%s reason=%s", reading.metricType, reading.recordedAt.toEpochMilli(), reading.value, implausibleReason) // DPT
                return // DPT — skip insertion entirely; mirrors BATTERY's hard-reject via an
                       // explicit return since (unlike BATTERY) the branches below are not empty.
            } // DPT
            Timber.tag(TAG).d("[STAGE-4 FILTER] PASS metric=%s timestamp=%d value=%s", reading.metricType, reading.recordedAt.toEpochMilli(), reading.value) // DPT
        } // DPT
        when (reading.metricType) {
            MetricType.HR -> hrReadingRepository.insert(reading.toHrEntity())
            MetricType.HRV -> hrvReadingRepository.insert(reading.toHrvEntity())
            MetricType.SPO2 -> spo2ReadingRepository.insert(reading.toSpO2Entity())
            MetricType.RESPIRATION -> respirationReadingRepository.insert(reading.toRespirationEntity())
            MetricType.SKIN_TEMP -> skinTempReadingRepository.insert(reading.toSkinTempEntity())
            MetricType.STEPS -> {
                // CHANGED — STEPS ACCUMULATION MODE:
                // DELTA — driver sends per-interval counts; we accumulate.
                // ABSOLUTE — driver sends running total; we replace. Used by devices that report
                //            cumulative steps directly. stepsMode is declared in the driver manifest.
                Timber.tag(TAG).d("[STAGE-4 ROUTER] STEPS stepsMode=%s", stepsMode) // STEPS-MODE / DPT
                if (stepsMode == StepsMode.ABSOLUTE) { // STEPS-MODE
                    val zone = ZoneId.systemDefault() // STEPS-MODE
                    val localDate = reading.recordedAt.atZone(zone).toLocalDate() // STEPS-MODE
                    val startMs = localDate.atStartOfDay(zone).toInstant().toEpochMilli() // STEPS-MODE
                    val endMs = localDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() // STEPS-MODE
                    stepsReadingRepository.deleteDeviceReadingsForDay(reading.driverId, startMs, endMs) // STEPS-MODE
                    Timber.tag(TAG).d("[STAGE-4 ROUTER] STEPS ABSOLUTE — deleted existing readings for date=%s", localDate) // STEPS-MODE / DPT
                }
                stepsReadingRepository.insert(reading.toStepsEntity()) // STEPS-MODE
            }
            MetricType.ACTIVE_CALORIES, MetricType.TOTAL_CALORIES -> {
                // CALORIES ACCUMULATION MODE:
                // DELTA — driver sends per-interval counts; insert each as a distinct row.
                //         Daily total is computed by summing at query time.
                // ABSOLUTE — driver sends running total; replace the existing day's reading.
                // caloriesMode is declared in the driver manifest (default: DELTA).
                Timber.tag(TAG).d("[STAGE-4 ROUTER] CALORIES metric=%s caloriesMode=%s", reading.metricType, caloriesMode) // CALORIES-MODE / DPT
                if (caloriesMode == CaloriesMode.ABSOLUTE) { // CALORIES-MODE
                    val zone = ZoneId.systemDefault() // CALORIES-MODE
                    val localDate = reading.recordedAt.atZone(zone).toLocalDate() // CALORIES-MODE
                    val startMs = localDate.atStartOfDay(zone).toInstant().toEpochMilli() // CALORIES-MODE
                    val endMs = localDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() // CALORIES-MODE
                    when (reading.metricType) { // CALORIES-MODE
                        MetricType.ACTIVE_CALORIES -> activeCalorieReadingRepository.deleteDeviceReadingsForDay(reading.driverId, startMs, endMs) // CALORIES-MODE
                        MetricType.TOTAL_CALORIES -> totalCalorieReadingRepository.deleteDeviceReadingsForDay(reading.driverId, startMs, endMs) // CALORIES-MODE
                        else -> Unit
                    }
                    Timber.tag(TAG).d("[STAGE-4 ROUTER] CALORIES ABSOLUTE — deleted existing reading for date=%s", localDate) // CALORIES-MODE / DPT
                }
                when (reading.metricType) { // CALORIES-MODE
                    MetricType.ACTIVE_CALORIES -> activeCalorieReadingRepository.insert(reading.toActiveCalorieEntity()) // METRIC-OWNERSHIP
                    MetricType.TOTAL_CALORIES -> totalCalorieReadingRepository.insert(reading.toTotalCalorieEntity()) // METRIC-OWNERSHIP
                    else -> Unit
                }
            }
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
            MetricType.ELEVATION_LOSS -> {
                // No dedicated table yet for these types — staged pending a future
                // schema decision. See MetricRouter audit F-8.
                stagingRepository.insert(reading)
            }
        }
    }

    // REMOVED: dead-code-archaeology

    /** Inserts [readings] using REPLACE-on-conflict. Used by DeviceReprocessor to
     *  force-overwrite existing records when the driver output has been corrected. */
    suspend fun routeAllForceReplace( // POST-AUDIT-FIX
        readings: List<MetricReading>,
        stepsMode: StepsMode = StepsMode.DELTA, // POST-AUDIT-FIX
        caloriesMode: CaloriesMode = CaloriesMode.DELTA, // CALORIES-MODE
        syncIntervalMs: Long? = null, // CHANGED
    ) {
        readings.forEach { route(it, stepsMode = stepsMode, caloriesMode = caloriesMode, syncIntervalMs = syncIntervalMs) } // POST-AUDIT-FIX / CHANGED
    }

    /**
     * Returns a short DROP reason if [reading] is an implausible-magnitude delta for a
     * DELTA-mode accumulator metric (STEPS / ACTIVE_CALORIES), or null if the reading should
     * pass through normally.
     */
    private fun implausibleDeltaReason(
        reading: MetricReading,
        stepsMode: StepsMode,
        caloriesMode: CaloriesMode,
        syncIntervalMs: Long?, // CHANGED
    ): String? {
        // CHANGED: ceiling is now derived from the driver's declared syncIntervalMs (falling
        // back to DEFAULT_SYNC_INTERVAL_MS), not a fixed constant.
        val effectiveIntervalMs = syncIntervalMs ?: DEFAULT_SYNC_INTERVAL_MS
        val maxPerInterval = maxPlausibleDelta(reading.metricType, effectiveIntervalMs) ?: return null
        val isDeltaValued = when (reading.metricType) {
            MetricType.STEPS -> stepsMode == StepsMode.DELTA
            MetricType.ACTIVE_CALORIES -> caloriesMode == CaloriesMode.DELTA
            else -> false
        }
        if (!isDeltaValued) return null
        return when {
            reading.value < 0.0 ->
                "negative-delta value=${reading.value} — accumulator deltas cannot be negative"
            reading.value > maxPerInterval ->
                "delta-exceeds-max value=${reading.value} max=$maxPerInterval — implausible for a " +
                    "single sync interval, likely a stale/carried-over frame value"
            else -> null
        }
    }

    private fun MetricReading.withPendingSleepStageFlag(): MetricReading {
        val merged = runCatching {
            JSONObject(metaJson ?: "{}").put("pending_sleep_stage", true).toString()
        }.getOrElse { """{"pending_sleep_stage": true}""" }
        return copy(metaJson = merged)
    }
}
