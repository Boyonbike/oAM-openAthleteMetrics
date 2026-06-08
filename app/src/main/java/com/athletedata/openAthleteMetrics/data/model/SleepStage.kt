package com.athletedata.openAthleteMetrics.data.model

/** A discrete sleep stage, used inside the [SleepSession.stagesJson] stage-breakdown array. */
enum class SleepStage {
    DEEP,
    LIGHT,
    REM,
    AWAKE,
}
