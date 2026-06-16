package com.athletedata.openAthleteMetrics.data.model

/** A discrete sleep stage, stored as rows in the `sleep_stages` table via [SleepStageEntity]. */
enum class SleepStage {
    DEEP,
    LIGHT,
    REM,
    AWAKE,
}
