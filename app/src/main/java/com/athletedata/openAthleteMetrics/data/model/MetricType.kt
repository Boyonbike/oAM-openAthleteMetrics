package com.athletedata.openAthleteMetrics.data.model

import kotlinx.serialization.Serializable

/** The physiological or activity metric that a [MetricReading] represents. */
@Serializable
enum class MetricType {
    HR,               // heart rate (bpm)
    HRV,              // heart rate variability (ms)
    RHR,              // resting heart rate (bpm)
    SPO2,             // blood oxygen saturation (%)
    STEPS,            // step count
    SLEEP_STAGE,      // sleep stage marker (value maps to SleepStage ordinal)
    BATTERY,          // device battery level (%)
    SKIN_TEMP,        // skin surface temperature (°C)
    BODY_TEMP,        // core body temperature (°C)
    TEMP_DEVIATION,   // deviation from baseline temperature (°C)
    VO2_MAX,          // aerobic capacity (mL/kg/min)
    DISTANCE,         // distance covered (metres)
    ELEVATION_GAIN,   // cumulative ascent (metres)
    ELEVATION_LOSS,   // cumulative descent (metres)
    CALORIES,         // total calories burned (kcal)
    ACTIVE_CALORIES,  // active (non-basal) calories burned (kcal)
    BASAL_CALORIES,   // basal metabolic rate calories (kcal)
    /**
     * Canonical value for respiratory rate (breaths per minute). Routes to the dedicated
     * respiration_readings table via BleEngine.routeReading().
     *
     * Driver authors: if your driver previously emitted [RESPIRATORY_RATE] (removed),
     * update your supportedMetrics and metric type output to use RESPIRATION instead.
     */
    RESPIRATION,
    TOTAL_CALORIES,   // full-day calorie expenditure including resting metabolic rate (kcal)
    BLOOD_PRESSURE,   // blood pressure; value = systolic (mmHg), diastolic encoded in metaJson["diastolic"]
    GLUCOSE,          // blood glucose; value in units described by the unit field (mmol or mg_dl)
    ;

    companion object {
        /**
         * Metrics that accumulate over a UTC day. Their deduplication key (type + UTC-midnight
         * timestamp) is stable across syncs, so a later sync with a higher total must replace
         * any earlier partial value. Fix is forward-looking — existing partial records written
         * before this change remain but will be replaced on the user's next sync.
         */
        val ACCUMULATOR_METRICS: Set<MetricType> = setOf(
            STEPS, CALORIES, ACTIVE_CALORIES, BASAL_CALORIES,
            DISTANCE, ELEVATION_GAIN, ELEVATION_LOSS,
        )
    }
}
