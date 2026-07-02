package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.UserProfileDao
import com.athletedata.openAthleteMetrics.data.db.toDomain
import com.athletedata.openAthleteMetrics.data.db.toEntity
import com.athletedata.openAthleteMetrics.data.model.UserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomUserProfileRepository @Inject constructor(
    private val dao: UserProfileDao,
) : UserProfileRepository {

    /** Subscribed to by ViewModels that display or react to profile changes. */
    override fun observe(): Flow<UserProfile?> = dao.observe().map { it?.toDomain() }

    /** Called by use-cases that need a one-shot profile read without subscribing. */
    override suspend fun get(): UserProfile? = dao.get()?.toDomain()

    /** Called by the profile-edit screen to persist user-entered fields. */
    override suspend fun upsert(profile: UserProfile) {
        try {
            dao.upsert(profile.toEntity().copy(updatedAt = System.currentTimeMillis()))
        } catch (e: Exception) {
            Timber.e(e, "Failed to upsert user profile")
            throw e
        }
    }

    /** Called by the weight-sync flow when a new daily weight reading is available. */
    override suspend fun updateWeight(weightKg: Double) {
        try {
            dao.updateWeight(weightKg, System.currentTimeMillis())
        } catch (e: Exception) {
            Timber.e(e, "Failed to update weight in user profile")
            throw e
        }
    }

    // RESET-SYSTEM
    override suspend fun deleteProfile() { dao.deleteAll() }
}
