package com.athletedata.openAthleteMetrics.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.athletedata.openAthleteMetrics.data.model.HrZone
import com.athletedata.openAthleteMetrics.data.model.UserProfile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Entity(tableName = "user_profile")
data class UserProfileEntity(
    /** Always 1 — enforces the single-row constraint. */
    @PrimaryKey
    val id: Int = 1,
    val name: String? = null,
    @ColumnInfo(name = "date_of_birth")
    val dateOfBirth: String? = null,
    @ColumnInfo(name = "biological_sex")
    val biologicalSex: String? = null,
    @ColumnInfo(name = "height_cm")
    val heightCm: Double? = null,
    @ColumnInfo(name = "weight_kg")
    val weightKg: Double? = null,
    @ColumnInfo(name = "stride_length_cm")
    val strideLengthCm: Double? = null,
    @ColumnInfo(name = "wrist_circumference_mm")
    val wristCircumferenceMm: Double? = null,
    @ColumnInfo(name = "resting_metabolic_rate")
    val restingMetabolicRate: Double? = null,
    @ColumnInfo(name = "vo2_max")
    val vo2Max: Double? = null,
    @ColumnInfo(name = "max_hr")
    val maxHr: Int? = null,
    @ColumnInfo(name = "hr_zones_json")
    val hrZonesJson: String? = null,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
)

// ── Mappers ──────────────────────────────────────────────────────────────────

/** Called by [com.athletedata.openAthleteMetrics.data.repository.RoomUserProfileRepository] to convert a Room row to the domain model. */
fun UserProfileEntity.toDomain(): UserProfile = UserProfile(
    id = id,
    name = name,
    dateOfBirth = dateOfBirth,
    biologicalSex = biologicalSex,
    heightCm = heightCm,
    weightKg = weightKg,
    strideLengthCm = strideLengthCm,
    wristCircumferenceMm = wristCircumferenceMm,
    restingMetabolicRate = restingMetabolicRate,
    vo2Max = vo2Max,
    maxHr = maxHr,
    hrZones = hrZonesJson?.let { Json.decodeFromString<List<HrZone>>(it) } ?: emptyList(),
    updatedAt = updatedAt,
)

/** Called by [com.athletedata.openAthleteMetrics.data.repository.RoomUserProfileRepository] to convert a domain model to a Room entity for persistence. */
fun UserProfile.toEntity(): UserProfileEntity = UserProfileEntity(
    id = id,
    name = name,
    dateOfBirth = dateOfBirth,
    biologicalSex = biologicalSex,
    heightCm = heightCm,
    weightKg = weightKg,
    strideLengthCm = strideLengthCm,
    wristCircumferenceMm = wristCircumferenceMm,
    restingMetabolicRate = restingMetabolicRate,
    vo2Max = vo2Max,
    maxHr = maxHr,
    hrZonesJson = hrZones.takeIf { it.isNotEmpty() }?.let { Json.encodeToString(it) },
    updatedAt = updatedAt,
)
