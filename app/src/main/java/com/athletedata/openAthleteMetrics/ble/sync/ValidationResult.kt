package com.athletedata.openAthleteMetrics.ble.sync

sealed class ValidationResult<out T> {
    data class Accepted<T>(val item: T) : ValidationResult<T>()
    data class Rejected<T>(val item: T, val reason: String) : ValidationResult<T>()
}
