package com.athletedata.openAthleteMetrics.data.model

/**
 * Closed whitelist of every metric a widget can bind to. Resolution against this
 * enum must always be a static when/enum-membership dispatch (see
 * [com.athletedata.openAthleteMetrics.data.repository.resolveDataSource]) — never
 * reflection, never a dynamic string-keyed lookup.
 */
enum class DataSourceKey {
    // ── DailySummary (precomputed daily rollup) ──
    HR,
    RHR,
    SLEEP,
    SPO2,
    STEPS,

    /**
     * Overnight HRV headline value. Resolved via HRV's own bespoke composition
     * (sleep session + baseline + hrv-reading-range) — never through the generic
     * single-repository dispatcher. See [DAILY_AVG_HRV_MS] for the plain rollup field.
     */
    HRV,

    /** DailySummary.avgHrvMs — distinct from the overnight headline [HRV] value. */
    DAILY_AVG_HRV_MS,

    SKIN_TEMP_AVG_C,
    SKIN_TEMP_MIN_C,
    SKIN_TEMP_MAX_C,
    RESPIRATION_AVG,
    HRV_MIN_MS,
    HRV_MAX_MS,
    SPO2_MIN_PCT,
    SPO2_MAX_PCT,
    STEPS_ACTIVE_MINUTES,
    SUMMARY_TOTAL_CALORIES,
    SUMMARY_ACTIVE_CALORIES,
    AVG_SYSTOLIC_MMHG,
    AVG_DIASTOLIC_MMHG,
    AVG_GLUCOSE_MMOLL,
    SLEEP_DEEP_MINUTES,
    SLEEP_LIGHT_MINUTES,
    SLEEP_REM_MINUTES,
    SLEEP_AWAKE_MINUTES,

    // ── DailyContext (user-entered) ──
    WEIGHT_KG,
    BODY_FAT_PCT,

    /** Free-text notes — non-chartable, text-typed. */
    NOTES,

    FATIGUE,
    STRESS,
    MOTIVATION,
    SLEEP_QUALITY,
    PERFORMANCE_FEEL,
    IS_ILL,
    ILLNESS_NOTES,

    // ── Activity (resolves against the day's first/primary activity) ──
    /** Falls back to the raw device name if the activity has no user-assigned category. */
    ACTIVITY_CATEGORY_LABEL,
    ACTIVITY_DURATION_MIN,

    /** Count of activities recorded for the day — not a field on Activity itself. */
    ACTIVITY_COUNT_TODAY,

    ACTIVITY_AVG_HR_BPM,
    ACTIVITY_MAX_HR_BPM,
    ACTIVITY_MIN_HR_BPM,
    ACTIVITY_CALORIES,
    ACTIVITY_ACTIVE_CALORIES,
    ACTIVITY_DISTANCE_M,
    ACTIVITY_STEPS,

    // ── Native-only — never importable. Real enum members so native rendering can
    // still use them, but a future file-import validator must explicitly reject both. ──

    /** Live join of starred+visible lifestyle questions against today's responses. */
    STARRED_LIFESTYLE_QUESTIONS,

    /**
     * Live join of all custom questions (not filtered to starred) against today's
     * responses — matches [com.athletedata.openAthleteMetrics.data.repository.QuestionRepository.getCustomQuestions],
     * the query the CustomQuestionsBar widget actually uses.
     */
    STARRED_CUSTOM_QUESTIONS,
}
