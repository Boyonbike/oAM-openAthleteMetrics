package com.athletedata.openAthleteMetrics.data.model

enum class SyncStatus {
    SUCCESS,
    // 1. Validation-based: some records accepted, some rejected (totalRejected > 0 && totalAccepted > 0).
    // 2. In-flight sentinel: written at sync start; startup worker marks rows older than 1 h as FAILED.
    PARTIAL,
    FAILED,
}
