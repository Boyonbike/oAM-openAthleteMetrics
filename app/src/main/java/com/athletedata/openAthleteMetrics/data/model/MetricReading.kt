package com.athletedata.openAthleteMetrics.data.model

import java.time.Instant

/**
 * A single sensor measurement from any source (device, manual entry, or seeder).
 *
 * This is the central domain object — every number the system tracks flows
 * through here. When the BLE driver layer is added it will produce
 * [MetricReading]s with [source] = [DataSource.DEVICE]; the schema does not
 * change.
 *
 * @property id Auto-generated primary key; 0 signals an unsaved instance.
 * @property metricType The physiological or activity metric being recorded.
 * @property value Numeric measurement in [unit].
 * @property unit Human-readable unit string (e.g. "bpm", "ms", "%", "steps").
 * @property recordedAt When the sensor captured this value.
 * @property createdAt When this row was inserted into the database.
 * @property source Who produced this reading.
 * @property driverId Populated by the device driver (e.g. "hume_band_v1"); null for manual/seeder rows.
 * @property confidence Signal-quality estimate in [0.0, 1.0] if the device provides it.
 * @property metaJson Driver-specific extras serialised as a JSON object string.
 * @property deviceId Physical device (numeric devices.id) this reading came from; not the driver.
 */
data class MetricReading(
    val id: Long = 0,
    val metricType: MetricType,
    val value: Double,
    val unit: String,
    val recordedAt: Instant,
    val createdAt: Instant,
    val source: DataSource,
    val driverId: String? = null,
    val confidence: Float? = null,
    val metaJson: String? = null,
    val deviceId: Long? = null,
)
