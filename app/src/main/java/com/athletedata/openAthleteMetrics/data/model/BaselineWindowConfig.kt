package com.athletedata.openAthleteMetrics.data.model

/** Resolved window/minimum pair actually used for a baseline recalculation. */
data class BaselineWindowConfig(
    val windowDays: Int,
    val minimumDays: Int,
)

/**
 * Factory-default window/minimum for a [BaselineMetric], used when no explicit
 * override row exists in `baseline_window_config`. This is NOT the override
 * mechanism — it's the value a metric starts at before any override is written.
 * Adding a new metric's default is one line here; adjusting it at runtime is a
 * database write via BaselineWindowConfigRepository.setOverride().
 */
object BaselineFallbackDefaults {
    val values: Map<BaselineMetric, BaselineWindowConfig> = mapOf(
        BaselineMetric.HRV to BaselineWindowConfig(windowDays = 60, minimumDays = 15),
        // Sleep-detail display averages: mirrors the HRV default as a placeholder pattern-match.
        // No sleep-specific rationale has been validated for a 60-day/15-day window — flagged
        // for product review.
        BaselineMetric.SLEEP_DEEP to BaselineWindowConfig(windowDays = 60, minimumDays = 15),
        BaselineMetric.SLEEP_LIGHT to BaselineWindowConfig(windowDays = 60, minimumDays = 15),
        BaselineMetric.SLEEP_REM to BaselineWindowConfig(windowDays = 60, minimumDays = 15),
        BaselineMetric.SLEEP_AWAKE to BaselineWindowConfig(windowDays = 60, minimumDays = 15),
        BaselineMetric.SLEEP_ONSET to BaselineWindowConfig(windowDays = 60, minimumDays = 15),
        BaselineMetric.SLEEP_WAKE to BaselineWindowConfig(windowDays = 60, minimumDays = 15),
    )
}
