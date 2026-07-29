package com.athletedata.openAthleteMetrics.data.model

enum class SyncStatus {
    SUCCESS,
    /** In-flight sentinel written when a sync session starts. Converted to FAILED by the
     *  startup cleanup worker if still present after the stale threshold (24 h). */
    IN_PROGRESS,
    /** Final outcome: validation accepted some records and rejected others
     *  (totalRejected > 0 && totalAccepted > 0). endedAt is always set. */
    PARTIAL,
    FAILED,
    /** Sync/parse was aborted by the user's Cancel action. endedAt is always set. */
    CANCELLED,
}
