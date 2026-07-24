package com.athletedata.openAthleteMetrics.data.model

import java.time.Instant

/**
 * A single time-bounded event recorded by a device.
 *
 * Drivers populate this from raw device data and return it inside a
 * [DriverSyncResult]. The app does not interpret [deviceName]; the user
 * classifies the activity via [userCategory] later.
 *
 * @property startTime        When the activity began, as reported by the device.
 * @property endTime          When the activity ended, as reported by the device.
 * @property durationMinutes  Duration in whole minutes; derived from start/end by the driver.
 * @property deviceName       Raw activity label from the device (e.g. "Outdoor Run", "Cycling").
 * @property userCategory     Classification set by the user after import; null until the user acts.
 * @property notes            Free-text annotation from the device or user; null if absent.
 * @property source           Who produced this record — always [DataSource.DEVICE] for driver output.
 * @property driverId         Identifies the driver that produced this record (e.g. "hume_band_v1").
 * @property avgHrBpm         Average heart rate over the activity in bpm; null if device did not provide it.
 * @property maxHrBpm         Peak heart rate in bpm; null if device did not provide it.
 * @property minHrBpm         Lowest heart rate in bpm; null if device did not provide it.
 * @property calories         Total energy expenditure in kcal; null if device did not provide it.
 * @property activeCalories   Active (non-resting) energy expenditure in kcal; null if device did not provide it.
 * @property distanceMeters   Total distance covered in metres; null if device did not provide it.
 * @property steps            Total step count during the activity; null if device did not provide it.
 * @property hrZonesJson      Heart-rate zone breakdown as a JSON array, e.g.
 *                            `[{"zone":1,"seconds":120},{"zone":2,"seconds":300}]`;
 *                            null if device did not provide it.
 * @property deviceId         Physical device (numeric devices.id) this activity came from; not the driver.
 */
data class Activity(
    val id: Long = 0,
    val startTime: Instant,
    val endTime: Instant,
    val durationMinutes: Int,
    val deviceName: String,
    val userCategory: UserCategory? = null,
    val notes: String? = null,
    val source: DataSource,
    val driverId: String? = null,
    val avgHrBpm: Double? = null,
    val maxHrBpm: Double? = null,
    val minHrBpm: Double? = null,
    val calories: Double? = null,
    val activeCalories: Double? = null,
    val distanceMeters: Double? = null,
    val steps: Int? = null,
    val hrZonesJson: String? = null,
    val deviceId: Long? = null,
)
