package com.athletedata.openAthleteMetrics.data.model

/** The physiological or activity metric that a [MetricReading] represents. */
enum class MetricType {
    HR,          // heart rate (bpm)
    HRV,         // heart rate variability (ms)
    RHR,         // resting heart rate (bpm)
    SPO2,        // blood oxygen saturation (%)
    STEPS,       // step count
    SLEEP_STAGE, // sleep stage marker (value maps to SleepStage ordinal)
    BATTERY,     // device battery level (%)
}
