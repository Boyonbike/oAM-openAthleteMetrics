package com.athletedata.openAthleteMetrics.ble.driver

import android.util.Base64
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonClassDiscriminator

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("mode")
sealed class ParsingConfig {

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
    /** Optional: dynamically builds sync commands at connection time. Null = driver uses static syncCommands only. */
    val buildSyncCommands: String? = null,
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
