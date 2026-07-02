package com.athletedata.openAthleteMetrics.ble.driver

import com.athletedata.openAthleteMetrics.data.model.MetricType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// STEPS-MODE: How a driver reports step counts. Declared in the driver manifest.
@Serializable
enum class StepsMode {
    @SerialName("delta") DELTA,
    @SerialName("absolute") ABSOLUTE,
} // STEPS-MODE

// CALORIES-MODE: How a driver reports calorie counts. Declared in the driver manifest.
@Serializable
enum class CaloriesMode {
    @SerialName("delta") DELTA,
    @SerialName("absolute") ABSOLUTE,
} // CALORIES-MODE

/**
 * Top-level in-memory representation of a driver manifest file.
 *
 * Each driver is a single `.json` file on device storage. The file must conform to the
 * schema documented in `app/src/main/assets/drivers/example_wasm.json`.
 * Use [ManifestValidator.validate] before trusting any field.
 */
/**
 * Declares which context fields a driver requires before building dynamic sync commands.
 * Absent or empty means the WASM [WasmExports.buildSyncCommands] export receives only a
 * minimal datetime context (no DB access). Set [datetime] or populate [userProfile] to
 * trigger a full [SyncContext] fetch from the user-profile repository.
 */
@Serializable
data class SyncRequirements(
    val datetime: Boolean = false,
    val userProfile: List<String> = emptyList(),
)

@Serializable
data class WasmDriverManifest(
    /** Stable, globally unique driver identifier. Never change this after publishing. */
    val id: String,
    /** Human-readable name shown in the Devices screen. */
    @SerialName("displayName") val displayName: String,
    /** Semantic version of this driver file, e.g. "1.0.0". */
    val version: String,
    val author: String,
    /** Metrics this driver can produce. Must not be empty. */
    val supportedMetrics: List<MetricType>,
    val ble: BleConfig,
    val syncCommands: List<SyncCommand> = emptyList(),
    val parsing: ParsingConfig,
    val specVersion: String = "1",
    // CHANGED: optional block; absent = no context needed for sync command building
    val syncRequirements: SyncRequirements? = null,
    val stepsMode: StepsMode = StepsMode.DELTA,     // STEPS-MODE: JSON "delta"/"absolute"; absent → DELTA
    val caloriesMode: CaloriesMode = CaloriesMode.DELTA, // CALORIES-MODE: JSON "delta"/"absolute"; absent → DELTA
)

/**
 * BLE discovery and service configuration.
 *
 * At least one of [matchByName] or [matchByServiceUuid] should be set so the scanner can
 * identify candidate devices. [services] and [characteristics] are used after connection to
 * discover the GATT profile.
 */
@Serializable
data class BleConfig(
    /** Exact display name or prefix to match during scanning. */
    val matchByName: String? = null,
    /** 128-bit service UUID used as an advertisement filter. */
    @SerialName("matchByServiceUuid") val matchByServiceUuid: String? = null,
    /** How confident the driver is that a name/UUID match identifies the correct device. */
    val matchConfidence: MatchConfidence,
    /** Service UUIDs to discover after connecting. Must not be empty. */
    val services: List<String>,
    /**
     * Map of role name → 128-bit characteristic UUID.
     * Standard roles are "notify" and "write"; drivers may define additional named roles.
     * Role names are referenced by [SyncCommand] and parse rules.
     */
    val characteristics: Map<String, String>,
)

/** How reliably a name or UUID match identifies the correct device model. */
@Serializable
enum class MatchConfidence {
    /** The identifier is unique to this device model — no false positives expected. */
    CERTAIN,
    /** The identifier may match other devices; additional filtering may be needed. */
    PROBABLE,
}
