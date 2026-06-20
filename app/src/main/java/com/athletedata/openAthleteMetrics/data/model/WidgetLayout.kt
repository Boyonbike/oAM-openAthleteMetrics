package com.athletedata.openAthleteMetrics.data.model

sealed class WidgetType {
    // Device metric widgets — navigate to DailyDetail on tap
    object Hr : WidgetType()
    object Hrv : WidgetType()
    object Rhr : WidgetType()
    object Sleep : WidgetType()
    object Spo2 : WidgetType()
    object Steps : WidgetType()
    // User-input widgets — navigate to input location if not filled for the date
    object Weight : WidgetType()
    data class LifestyleQuestion(val questionId: Long) : WidgetType()
    data class HabitQuestion(val questionId: Long) : WidgetType()
    // Composite bar widgets (always WIDE)
    object StarredLifestyleBar : WidgetType()
    object StarredHabitsBar : WidgetType()
    object Activities : WidgetType()
    // Forward-compatibility: unknown discriminators render as a placeholder
    data class Unknown(val discriminator: String) : WidgetType()

    val metricKey: String? get() = when (this) {
        is Hr    -> "HR"
        is Hrv   -> "HRV"
        is Rhr   -> "RHR"
        is Sleep -> "SLEEP"
        is Spo2  -> "SPO2"
        is Steps -> "STEPS"
        else     -> null
    }

    companion object {
        fun discriminator(type: WidgetType): String = when (type) {
            is Hr                  -> "HR"
            is Hrv                 -> "HRV"
            is Rhr                 -> "RHR"
            is Sleep               -> "SLEEP"
            is Spo2                -> "SPO2"
            is Steps               -> "STEPS"
            is Weight              -> "WEIGHT"
            is LifestyleQuestion   -> "LIFESTYLE_Q"
            is HabitQuestion       -> "HABIT_Q"
            is StarredLifestyleBar -> "STARRED_LIFESTYLE"
            is StarredHabitsBar    -> "STARRED_HABITS"
            is Activities          -> "ACTIVITIES"
            is Unknown             -> type.discriminator
        }

        fun extraId(type: WidgetType): Long? = when (type) {
            is LifestyleQuestion -> type.questionId
            is HabitQuestion     -> type.questionId
            else                 -> null
        }

        fun fromDiscriminator(s: String, extraId: Long?): WidgetType = when (s) {
            "HR"                 -> Hr
            "HRV"                -> Hrv
            "RHR"                -> Rhr
            "SLEEP"              -> Sleep
            "SPO2"               -> Spo2
            "STEPS"              -> Steps
            "WEIGHT"             -> Weight
            "LIFESTYLE_Q"        -> LifestyleQuestion(extraId ?: 0L)
            "HABIT_Q"            -> HabitQuestion(extraId ?: 0L)
            "STARRED_LIFESTYLE"  -> StarredLifestyleBar
            "STARRED_HABITS"     -> StarredHabitsBar
            "ACTIVITIES"         -> Activities
            else                 -> Unknown(s)
        }
    }
}

enum class WidgetSize { SMALL, WIDE }

data class WidgetLayout(
    val id: Long,
    val type: WidgetType,
    val size: WidgetSize,
    val sortOrder: Int,
)
