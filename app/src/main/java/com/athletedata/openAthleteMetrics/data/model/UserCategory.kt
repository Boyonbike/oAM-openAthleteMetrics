package com.athletedata.openAthleteMetrics.data.model

/**
 * The user-assigned classification for an [Activity].
 *
 * Drivers never set this — they populate [Activity.deviceName] with the raw
 * device label. The user (or a future auto-classifier) sets [UserCategory]
 * manually after the activity is imported.
 */
enum class UserCategory {
    /** Structured exercise or sport session. */
    TRAINING,
    /** Everyday movement not tied to sport (commute, errands, etc.). */
    LIFE,
    /** A competitive event. */
    RACE,
}

fun UserCategory.toLabel(): String = when (this) {
    UserCategory.TRAINING -> "Training"
    UserCategory.LIFE -> "Life"
    UserCategory.RACE -> "Race"
}
