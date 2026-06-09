package com.athletedata.openAthleteMetrics.ble.driver

import android.util.Base64
import com.athletedata.openAthleteMetrics.data.model.MetricType
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Describes how raw BLE characteristic payloads are converted to [MetricType] values.
 *
 * The JSON `"mode"` field selects the subclass:
 * - `"JSON"` — declarative byte-offset rules interpreted by the runtime
 * - `"WASM"` — a WASM binary is called to perform parsing
 */
@Serializable
@JsonClassDiscriminator("mode")
sealed class ParsingConfig {

    /**
     * Pure-JSON parsing: an ordered list of [rules] matched against incoming characteristic
     * payloads. Rules are evaluated in order; the first matching rule wins.
     */
    @Serializable
    @SerialName("JSON")
    data class JsonParsing(
        val rules: List<JsonParseRule>,
    ) : ParsingConfig()

    /**
     * WASM-based parsing: the embedded [wasmBytes] binary is loaded into the Chicory runtime
     * and its exported functions are called to convert payloads into metric values.
     *
     * [wasmBytes] is decoded from the JSON `"wasmBase64"` field on deserialisation — callers
     * always work with raw bytes, never the Base64 string.
     */
    @Serializable
    @SerialName("WASM")
    data class WasmParsing(
        @SerialName("wasmBase64")
        @Serializable(with = Base64ByteArraySerializer::class)
        val wasmBytes: ByteArray,
        val exports: WasmExports,
    ) : ParsingConfig() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is WasmParsing) return false
            return wasmBytes.contentEquals(other.wasmBytes) && exports == other.exports
        }

        override fun hashCode(): Int = 31 * wasmBytes.contentHashCode() + exports.hashCode()

        override fun toString(): String =
            "WasmParsing(wasmBytes=${wasmBytes.size} bytes, exports=$exports)"
    }
}

/**
 * Names of functions the WASM module must export.
 * [parseMetrics] is required; the others are optional capabilities.
 */
@Serializable
data class WasmExports(
    /** Required: parses point-in-time metric readings from a characteristic payload. */
    val parseMetrics: String,
    /** Optional: parses sleep session data. */
    val parseSleep: String? = null,
    /** Optional: parses activity/workout data. */
    val parseActivity: String? = null,
)

/**
 * One entry in the JSON-mode rules array. Matched against a single characteristic notification.
 *
 * [characteristic] is a role name from [BleConfig.characteristics]. [match] identifies which
 * packet type this rule applies to. [fields] describes how to extract individual metrics.
 */
@Serializable
data class JsonParseRule(
    val characteristic: String,
    val match: MatchRule,
    val fields: List<ParseField>,
)

/**
 * Identifies a packet type by inspecting a single byte at a known offset.
 *
 * A payload matches this rule when `payload[byte] == value` (value is a hex string, e.g. "0x04").
 */
@Serializable
data class MatchRule(
    /** Zero-based index of the byte to inspect. */
    val byte: Int,
    /** Expected value as a hex string, e.g. "0x04". */
    val value: String,
)

/**
 * Extracts one metric value from a matched characteristic payload.
 *
 * Raw bytes at [[byteOffset]..[byteOffset]+[byteLength]) are interpreted as a little-endian
 * integer (signed or unsigned per [signed]), then multiplied by [scale] if provided.
 */
@Serializable
data class ParseField(
    val metric: MetricType,
    /** Zero-based index of the first byte of this field. */
    val byteOffset: Int,
    /** Number of bytes in this field (1–4). */
    val byteLength: Int,
    /** Whether to interpret the raw integer as two's-complement signed. */
    val signed: Boolean,
    /** Optional multiplier applied to the raw integer to produce the final value. */
    val scale: Double? = null,
    /** Physical unit of the resulting value, e.g. "bpm", "°C", "m". */
    val unit: String,
)

/**
 * Custom serializer that converts the JSON `"wasmBase64"` string field to a [ByteArray]
 * on read, and encodes a [ByteArray] back to Base64 on write.
 */
object Base64ByteArraySerializer : KSerializer<ByteArray> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Base64ByteArray", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): ByteArray =
        Base64.decode(decoder.decodeString(), Base64.DEFAULT)

    override fun serialize(encoder: Encoder, value: ByteArray) =
        encoder.encodeString(Base64.encodeToString(value, Base64.NO_WRAP))
}
