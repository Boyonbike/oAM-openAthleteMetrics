package com.athletedata.openAthleteMetrics.ble

import com.athletedata.openAthleteMetrics.data.repository.UserProfileRepository
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncContextFactory @Inject constructor(
    private val userProfileRepository: UserProfileRepository,
) {
    suspend fun build(): SyncContext {
        val epochMs = System.currentTimeMillis()
        val instant = Instant.ofEpochMilli(epochMs)
        val zone = ZoneId.systemDefault()
        val utcOffsetMinutes = zone.rules.getOffset(instant).totalSeconds / 60
        val isoDateTime = DateTimeFormatter.ISO_LOCAL_DATE_TIME
            .format(LocalDateTime.ofInstant(instant, zone))
        val profile = userProfileRepository.get()
        return SyncContext(
            epochMs = epochMs,
            utcOffsetMinutes = utcOffsetMinutes,
            isoDateTime = isoDateTime,
            name = profile?.name,
            dateOfBirth = profile?.dateOfBirth,
            biologicalSex = profile?.biologicalSex,
            heightCm = profile?.heightCm,
            weightKg = profile?.weightKg,
            strideLengthCm = profile?.strideLengthCm,
            wristCircumferenceMm = profile?.wristCircumferenceMm,
            restingMetabolicRate = profile?.restingMetabolicRate,
            vo2Max = profile?.vo2Max,
            maxHr = profile?.maxHr,
            hrZones = profile?.hrZones ?: emptyList(),
        )
    }
}
