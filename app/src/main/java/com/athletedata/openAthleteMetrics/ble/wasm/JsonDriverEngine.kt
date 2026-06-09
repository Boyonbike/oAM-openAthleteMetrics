package com.athletedata.openAthleteMetrics.ble.wasm

import com.athletedata.openAthleteMetrics.ble.driver.JsonParseRule
import com.athletedata.openAthleteMetrics.ble.driver.ParseField
import com.athletedata.openAthleteMetrics.ble.driver.MatchRule
import com.athletedata.openAthleteMetrics.data.model.DataSource
import com.athletedata.openAthleteMetrics.data.model.MetricReading
import java.time.Instant

/**
 * Pure-Kotlin parsing engine for [ParsingConfig.JsonParsing] drivers.
 *
 * Stateless — create a new instance per call site or share freely.
 * Sleep and activity parsing are not supported in JSON mode; use WASM for those.
 */
class JsonDriverEngine {

    /**
     * Walks [rules] in order, applies [matchesCondition] on [data], extracts all matching
     * fields, and returns a [MetricReading] for each successfully extracted value.
     *
     * [characteristicUuid] is passed for future use and logging; rule selection relies
     * entirely on each rule's [MatchRule] byte condition. The caller is responsible for
     * passing only rules relevant to this characteristic.
     */
    fun parseMetrics(
        characteristicUuid: String,
        data: ByteArray,
        rules: List<JsonParseRule>,
        driverId: String,
    ): List<MetricReading> {
        val now = Instant.now()
        return rules
            .filter { matchesCondition(data, it.match) }
            .flatMap { rule ->
                rule.fields.mapNotNull { field ->
                    extractField(data, field)?.let { value ->
                        MetricReading(
                            metricType = field.metric,
                            value = value,
                            unit = field.unit,
                            recordedAt = now,
                            createdAt = now,
                            source = DataSource.DEVICE,
                            driverId = driverId,
                        )
                    }
                }
            }
    }

    private fun matchesCondition(data: ByteArray, match: MatchRule): Boolean {
        if (match.byte >= data.size) return false
        val expected = match.value.removePrefix("0x").toIntOrNull(16) ?: return false
        return data[match.byte].toInt() and 0xFF == expected
    }

    private fun extractField(data: ByteArray, field: ParseField): Double? {
        if (field.byteOffset + field.byteLength > data.size) return null
        var raw = 0
        for (i in 0 until field.byteLength) {
            raw = raw or ((data[field.byteOffset + i].toInt() and 0xFF) shl (8 * i))
        }
        val value: Double = if (field.signed) {
            when (field.byteLength) {
                1 -> raw.toByte().toDouble()
                2 -> raw.toShort().toDouble()
                else -> raw.toDouble()
            }
        } else {
            raw.toDouble()
        }
        return if (field.scale != null) value * field.scale else value
    }
}
