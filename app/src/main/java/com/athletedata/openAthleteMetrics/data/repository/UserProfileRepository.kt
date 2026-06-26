package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.model.UserProfile
import kotlinx.coroutines.flow.Flow

interface UserProfileRepository {

    /** Subscribed to by ViewModels that display or react to profile changes. */
    fun observe(): Flow<UserProfile?>

    /** Called by use-cases that need a one-shot profile read without subscribing. */
    suspend fun get(): UserProfile?

    /** Called by the profile-edit screen to persist user-entered fields. */
    suspend fun upsert(profile: UserProfile)

    /** Called by the weight-sync flow when a new daily weight reading is available. */
    suspend fun updateWeight(weightKg: Double)
}
