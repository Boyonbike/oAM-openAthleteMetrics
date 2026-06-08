package com.athletedata.openAthleteMetrics.data.model

/** The physiological or activity metric that a [MetricReading] represents. */
enum class MetricType {
    HR,               // heart rate (bpm)
    HRV,              // heart rate variability (ms)
    RHR,              // resting heart rate (bpm)
    SPO2,             // blood oxygen saturation (%)
    STEPS,            // step count
    SLEEP_STAGE,      // sleep stage marker (value maps to SleepStage ordinal)
    BATTERY,          // device battery level (%)
    RESPIRATORY_RATE, // breaths per minute
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
}
