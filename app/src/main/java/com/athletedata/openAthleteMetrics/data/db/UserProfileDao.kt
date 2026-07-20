package com.athletedata.openAthleteMetrics.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface UserProfileDao {

    /** Called by [com.athletedata.openAthleteMetrics.data.repository.RoomUserProfileRepository.observe] to reactively expose the profile. */
    @Query("SELECT * FROM user_profile WHERE id = 1")
    fun observe(): Flow<UserProfileEntity?>

    /** Called by [com.athletedata.openAthleteMetrics.data.repository.RoomUserProfileRepository.get] for a one-shot profile read. */
    @Query("SELECT * FROM user_profile WHERE id = 1")
    suspend fun get(): UserProfileEntity?

    /** Called by [com.athletedata.openAthleteMetrics.data.repository.RoomUserProfileRepository.upsert] to create or replace the profile row. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: UserProfileEntity)

    /** Called by [com.athletedata.openAthleteMetrics.data.repository.RoomUserProfileRepository.updateWeight] when the daily weight log produces a new reading. */
    @Query("UPDATE user_profile SET weight_kg = :weightKg, updated_at = :updatedAt WHERE id = 1")
    suspend fun updateWeight(weightKg: Double, updatedAt: Long)

    /**
     * Same as [updateWeight] but seeds a default profile row first if none exists yet, so a
     * weight logged before the user ever opens Profile/Settings isn't silently dropped by a
     * 0-row UPDATE.
     */
    @Transaction
    suspend fun upsertWeight(weightKg: Double, updatedAt: Long) {
        if (get() == null) {
            upsert(UserProfileEntity(weightKg = weightKg, updatedAt = updatedAt))
        } else {
            updateWeight(weightKg, updatedAt)
        }
    }

    // RESET-SYSTEM
    @Query("DELETE FROM user_profile")
    suspend fun deleteAll()
}
