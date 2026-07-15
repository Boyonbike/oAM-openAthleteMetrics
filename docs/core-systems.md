# Core Systems Technical Reference

Three foundational systems underpin the app: the **BLE System** that connects to and streams data from wearable devices, the **Driver System** that abstracts device-specific parsing, and the **Database Architecture** that stores every observation. This document is the authoritative reference for all three.

---

## BLE System

### Responsibility Boundary

| Layer | Owns |
|-------|------|
| `BleEngine` | Scan, GATT lifecycle, MTU negotiation, service discovery, CCCD writes, sync command execution, packet reassembly, quiescence detection, retry scheduling, state emission |
| Driver (WASM) | Device matching criteria, sync command list, packet parsing, metric semantics |
| ViewModel / UI | User intent: connect, disconnect, trigger sync, acknowledge completion, recover interrupted session |

`BleEngine` never calls UI code directly. It exposes `StateFlow<BleConnectionState>` and other `StateFlow`s that `DevicesViewModel` observes and maps to UI state.

---

### Data-Flow Diagram

```
BLE Hardware
    │  ATT notification (raw bytes, ≤ negotiatedMtu-3 per packet)
    ▼
BleEngine.onCharacteristicChanged(gatt, characteristic, bytes)
    │  notificationChannel: Channel<Pair<uuid, ByteArray>>
    │  capacity=512, overflow=DROP_OLDEST
    ▼
IO coroutine consumer: handleNotification(uuid, bytes)
    │
    ├── reassemblyBuffers[uuid] += bytes
    │   if bytes.size < (negotiatedMtu - 3): packet complete, emit assembled bytes
    │
    ▼
RawDeviceDataRepository.insertAll()          ← persist raw bytes before parsing
    │
    ▼
sessionCache.add(SessionFrame(characteristic, opcode, assembledBytes))
    │  buffered only — nothing is parsed yet. Repeats for every notification
    │  until the sync completes (quiescence, manual trigger, or the device's
    │  own awaitEndOfStream signal).
    ▼
── sync completion ──────────────────────────────────────────────────────
    ▼
DriverRegistry.parseSession(manifest, frames)
    │  WasmDriverEngine: serialise all buffered frames to one JSON array
    │  (chunked above ~50,000 bytes) → write to WASM memory (no metadata
    │  header for this call) → call exported parseSession(offset, length)
    │  once per chunk → read UTF-8 JSON from output region
    │  → deserialize to List<MetricWasmDto> → map to List<MetricReading>
    ▼
SyncValidator.validateReadings(readings)     ← bounds check, timestamp sanity
    │
    ▼
BleEngine.routeReading(reading) → MetricRouter.route(reading), once per reading:
    ├── HR             → HrReadingRepository          → hr_readings
    ├── HRV            → HrvReadingRepository         → hrv_readings
    ├── SPO2           → SpO2ReadingRepository        → spo2_readings
    ├── RESPIRATION    → RespirationReadingRepository → respiration_readings
    ├── SKIN_TEMP      → SkinTempReadingRepository    → skin_temp_readings
    ├── STEPS          → StepsReadingRepository       → steps_readings
    ├── ACTIVE_CALORIES→ ActiveCalorieRepository      → active_calorie_readings
    ├── TOTAL_CALORIES → TotalCalorieRepository       → total_calorie_readings
    ├── BLOOD_PRESSURE → BloodPressureRepository      → blood_pressure_readings
    │   (requires diastolic in metaJson["diastolic"]; falls through to staging if absent)
    ├── GLUCOSE        → GlucoseRepository            → glucose_readings
    ├── SLEEP_STAGE    → MetricReadingStagingRepository (with pending_sleep_stage flag)
    ├── BATTERY        → discarded by MetricRouter; DeviceSyncProcessor.process()
    │                     later reads it from the driver-sync result and calls
    │                     deviceRepository.updateLastBatteryPct() (device metadata only)
    └── all others     → MetricReadingStagingRepository → metric_readings_staging
    │
    ▼
BleEngine.affectedDates.add(reading.date)
    │
DailySummaryWorker enqueued for that date (ExistingWorkPolicy.REPLACE)
    │
    ▼
daily_summary row upserted
```

The identical `sessionCache` → `parseSession` → `SyncValidator` → `MetricRouter.route()`
sequence also runs for the "Reprocess from raw data" action, via `DeviceReprocessor`
(see [Driver System](#driver-system) below) — the only differences are that frames come
from stored `raw_device_data` rows instead of a live GATT connection, and routing uses
`MetricRouter.routeAllForceReplace()` (REPLACE-on-conflict) instead of `route()`
(insert-or-ignore / value-guarded).

---

### Connection State Machine

**Source:** `BleConnectionState.kt`

```kotlin
sealed class BleConnectionState {
    object Idle : BleConnectionState()
    object Scanning : BleConnectionState()
    data class Connecting(val deviceAddress: String) : BleConnectionState()
    data class Connected(
        val deviceAddress: String,
        val driverName: String,
        val packetsReceived: Int = 0,
        val isQuiescent: Boolean = false,
    ) : BleConnectionState()
    data class Syncing(val deviceAddress: String, val progress: Float) : BleConnectionState()
    data class SyncComplete(val summary: SyncSummary, val deviceAddress: String) : BleConnectionState()
    data class Disconnected(val deviceAddress: String, val reason: String?) : BleConnectionState()
    data class Error(val message: String) : BleConnectionState()
    data class GattCacheError(val deviceAddress: String, val deviceName: String) : BleConnectionState()
}
```

**State transitions:**

| From | To | Trigger |
|------|----|---------|
| `Idle` | `Scanning` | `startScan()` called |
| `Scanning` | `Error` | Scan timeout (10 s) or Bluetooth disabled |
| `Scanning` | `Connecting` | `connectToCandidate()` or `connectToDevice()` |
| `Connecting` | `Error` | Permission denied or adapter off |
| `Connecting` | `Connected` | All CCCD writes and sync commands complete; packets arriving |
| `Connecting` | `GattCacheError` | Silent sync timeout with 0 packets; `gatt.refresh()` fails or already retried |
| `Connected` | `Syncing` | `triggerSync()` called (manual or auto-trigger on connect) |
| `Connected` | `Disconnected` | User calls `disconnect()`; `userDisconnecting = true` |
| `Connected` | `Connecting` | Unexpected `STATE_DISCONNECTED`; retry scheduled |
| `Syncing` | `SyncComplete` | `syncProcessor.process()` returns |
| `SyncComplete` | `Connected` | `acknowledgeSyncComplete()` called by ViewModel |
| `Connecting` (retry) | `Disconnected` | `retryCount >= MAX_RETRIES` (3) |
| `Disconnected` | `Idle` | `resetToIdle()` called |
| `Error` | `Idle` | `resetToIdle()` called |
| `GattCacheError` | `Connecting` | `gatt.refresh()` succeeds; reconnect attempted |

---

### Scanning and Device Matching

`startScan()` builds `ScanFilter` objects from every registered driver's `matchByName` and `matchByServiceUuid` fields. Android's `BluetoothLeScanner` applies these as a hardware-side filter before any scan result reaches the app.

**Scan parameters:**

| Constant | Value |
|----------|-------|
| `SCAN_TIMEOUT_MS` | 10 000 ms |
| Scan mode | `SCAN_MODE_LOW_LATENCY` |

When a scan result arrives, `DriverRegistry.resolve(deviceName, serviceUuids)` picks the best matching driver:

- If a driver sets **both** `matchByName` and `matchByServiceUuid`, both must match (AND).
- If a driver sets **only one**, either alone is sufficient (OR).
- `MatchConfidence.CERTAIN` drivers are preferred over `PROBABLE`. The first `CERTAIN` match wins; if none, the first `PROBABLE` match wins.
- `matchByName` performs a prefix match (name must *start with* the value).

Results are stored in `candidateMap: LinkedHashMap<MAC, DiscoveredCandidate>` and emitted as `discoveredCandidates: StateFlow<List<DiscoveredCandidate>>` for the device-picker UI. The map is cleared on each new scan start and when a candidate is selected for connection.

---

### GATT Connection Sequence

The sequence is driven entirely by GATT callbacks. Each phase is triggered by the completion of the previous one.

1. `connectToDevice(address, manifest)` or `connectToCandidate(candidate)` — calls `device.connectGatt(context, false, gattCallback, TRANSPORT_LE)`.
2. `onConnectionStateChange(STATE_CONNECTED, GATT_SUCCESS)` — calls `gatt.requestMtu(512)`.
3. `onMtuChanged(mtu, GATT_SUCCESS)` — stores `negotiatedMtu = mtu`; calls `gatt.discoverServices()`. On failure, proceeds with default `negotiatedMtu = 23`.
4. `onServicesDiscovered(GATT_SUCCESS)` — iterates `manifest.ble.characteristics`, finds characteristics with `PROPERTY_NOTIFY`, enqueues them in `notifySetupQueue`, then calls `enableNextNotification()`.
5. Each `onDescriptorWrite(GATT_SUCCESS)` — dequeues the next entry and calls `enableNextNotification()` until the queue is empty.
6. Queue empty → `beginSyncCommandExecution()` — calls `DriverRegistry.buildEffectiveSyncCommands(manifest)` then `executeNextSyncCommand()` starting at index 0.
7. All sync commands executed → state becomes `Connected`; `silentSyncTimeoutJob` starts (15 s).

On `GATT_ERROR` at steps 4–6, `scheduleRetry()` is called.

**CCCD write:** For each characteristic, `gatt.setCharacteristicNotification(char, true)` enables the local stack notification, then `gatt.writeDescriptor(CCCD_UUID, ENABLE_NOTIFICATION_VALUE)` enables the remote indication. This is done serially (one at a time) to avoid GATT write conflicts.

---

### Sync Command Execution

**Types (sealed class `SyncCommand`):**

| Type | Fields | Purpose |
|------|--------|---------|
| `Write` | `characteristic: String`, `bytes: String` | Write raw bytes to a characteristic; `bytes` is space-separated hex, e.g. `"0x01 0xAB"` |
| `Delay` | `millis: Long` | Pause for `millis` milliseconds before executing the next command |
| `EnableNotify` | `characteristic: String` | Enable notifications on a characteristic mid-sequence (for devices that require it after a write) |

**Effective command list:** `BleEngine.beginSyncCommandExecution()` picks one source exclusively — it does not merge the two. If the driver's WASM exports `buildSyncCommands`, the engine calls it at connection time and its output (the WASM function receives current time and UTC offset in the metadata header and writes a JSON array of commands) **is** the complete command sequence; the manifest's static `syncCommands` list is not read at all in that case. If `buildSyncCommands` is absent, the static list is used unchanged. A manifest that declares `buildSyncCommands` has no reason to also carry a static `syncCommands` array, since it will never be executed.

Commands execute serially. `onCharacteristicWrite` advances the index for `Write`; `onDescriptorWrite` advances it for `EnableNotify`; a `delay()` coroutine advances it for `Delay`.

---

### Packet Reassembly — "Short-Packet Terminal"

Each ATT notification carries at most `negotiatedMtu - 3` bytes. Long logical packets are fragmented by the BLE stack.

Fragments for each characteristic UUID accumulate in `reassemblyBuffers: Map<String, ByteArray>`. When an incoming `bytes.size < (negotiatedMtu - 3)`, the engine treats the packet as complete, emits the assembled buffer for parsing, and resets the buffer to empty.

This strategy assumes devices send fixed-size or variable-but-short packets where the last fragment is shorter than the maximum. Devices that use a length-prefix or continuation-flag framing would require a different completeness check.

`maxPayload = negotiatedMtu - 3` (default 20 bytes when MTU is the baseline 23).

---

### Quiescence and Silent-Sync Timeout

**Quiescence:** After each assembled packet, the engine resets `quiescenceJob` — a coroutine that fires after `STREAM_QUIESCENCE_MS = 3 000 ms` of silence. When it fires: `isQuiescent = true`, the current `Connected` state is re-emitted with that flag set, and `DailySummaryWorker` is enqueued for all affected dates.

**Silent-sync timeout:** After all sync commands complete, if `packetCount == 0` after 15 seconds (`SILENT_SYNC_TIMEOUT_MS`):
- If `gattCacheRefreshAttempted == false`: call `gatt.refresh()` (hidden Android API that clears the GATT service/handle cache), close the GATT connection, and reconnect. This resolves stale handles caused by device firmware updates.
- If refresh has already been tried or `refresh()` returns `false`: emit `GattCacheError`.

---

### Retry and Reconnect Logic

Unexpected disconnects (when `userDisconnecting == false`) trigger `scheduleRetry()`.

**Exponential backoff:**

| Retry count | Delay |
|-------------|-------|
| 1 | 2 000 ms |
| 2 | 4 000 ms |
| 3 | 8 000 ms |
| > `MAX_RETRIES` (3) | Emit `Disconnected`; clear `activeManifest`, `activeDeviceAddress`, reset counter |

`retryCount` is preserved across retry attempts within a single connection attempt. `resetRetries = false` is passed when reconnecting after a GATT cache refresh so the retry budget is not wasted.

Retry is also triggered by `GATT_ERROR` status on `onServicesDiscovered`, `onDescriptorWrite`, and `onCharacteristicWrite`.

---

### BleEngine Public API

| Method | When called | Effect |
|--------|-------------|--------|
| `autoConnectOnStartup()` | App launch | Finds the device with the most recent `lastSyncMs` or `lastSeenMs`; skips if state is not `Idle` |
| `connectToDevice(address, manifest)` | ViewModel (tap known device) | Resets retry counter, clears accumulators, sets state → `Connecting` |
| `connectToCandidate(candidate)` | ViewModel (tap scan result) | Clears candidate map, stops scan, connects |
| `startScan()` | ViewModel (Add Device tapped) | Builds scan filters, starts 10 s scan |
| `disconnect()` | ViewModel | Sets `userDisconnecting = true`, cancels jobs, closes GATT |
| `triggerSync()` | ViewModel or auto on connect | Drains pending accumulators, calls `syncProcessor.process()` |
| `acknowledgeSyncComplete()` | ViewModel (user dismisses summary) | Transitions `SyncComplete` → `Connected` |
| `resetToIdle()` | ViewModel (user dismisses disconnect/error modal) | Transitions `Disconnected` or `Error` → `Idle` |
| `shutdown()` | App lifecycle | Cancels all coroutine scope jobs |

**Key constants:**

| Constant | Value |
|----------|-------|
| `CCCD_UUID` | `"00002902-0000-1000-8000-00805f9b34fb"` |
| `MTU_REQUEST` | 512 |
| `SCAN_TIMEOUT_MS` | 10 000 |
| `MAX_RETRIES` | 3 |
| `STREAM_QUIESCENCE_MS` | 3 000 |
| `SILENT_SYNC_TIMEOUT_MS` | 15 000 |

---

### Devices Menu — User-Facing BLE Actions

`DevicesViewModel` observes `BleEngine` state flows and exposes:

| StateFlow | Content |
|-----------|---------|
| `connectionState` | Direct pass-through of `BleEngine.connectionState` |
| `devices` | All known devices from `DeviceRepository` |
| `discoveredCandidates` | Devices found during an active scan |
| `reprocessState` | `ReprocessState` (`Idle`/`Running(progress)`/`Done`/`Failed`) for the "Reprocess from raw data" action |
| `reprocessingDeviceId` | ID of the device currently being reprocessed, or `null` |

**User actions and their engine calls:**

| UI action | ViewModel call | Engine/processor call |
|-----------|---------------|-----------------------|
| Tap "Add Device" | `onAddDeviceTapped()` | `bleEngine.startScan()` |
| Tap a scan result | `onCandidateSelected(candidate)` | `bleEngine.connectToCandidate(candidate)` |
| Tap a known device | `onDeviceCellTapped(device)` | `bleEngine.connectToDevice(address, manifest)` |
| Tap "Sync" | `onSyncTapped()` | `bleEngine.triggerSync()` |
| Tap "Disconnect" | `onDisconnectTapped()` | `bleEngine.disconnect()` |
| Dismiss sync summary | `onSyncAcknowledged()` | `bleEngine.acknowledgeSyncComplete()` |
| Confirm "Reprocess from raw data" | `onReprocessConfirmed(device)` | `deviceReprocessor.reprocess(device, since, onProgress)` |
| Load manifest file | `onManifestFilePicked(uri)` | `DriverRegistry.register(manifest)` → `DriverStorage.save()` |
| Delete a driver | `onDeleteDriverTapped(driverId)` | `DriverStorage.delete()` → `DriverRegistry.unregister()` |

---

## Driver System

### Only Supported Format: WASM Manifest

`DeviceDriver` and `MetricProcessor` exist in source as scaffolding for a possible future native Kotlin driver path. They are **not currently functional** and are not intended for external contributors to implement.

The only supported driver format is a JSON manifest file with an embedded WebAssembly binary (`parsing.mode = "WASM"`). The app's `WasmDriverEngine` (backed by the Chicory WASM runtime) loads and calls the binary at runtime.

---

### WasmDriverManifest — Full Schema

**Top-level fields (`WasmDriverManifest.kt`):**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | `String` | yes | Stable, globally unique driver identifier. Never change after publishing — used for deduplication and foreign-key references |
| `displayName` | `String` | yes | Human-readable name shown in the Devices screen |
| `version` | `String` | yes | Semantic version, e.g. `"1.0.0"` |
| `author` | `String` | yes | Author name or handle |
| `supportedMetrics` | `List<MetricType>` | yes | Non-empty list of metric types this driver can produce |
| `ble` | `BleConfig` | yes | BLE discovery and GATT configuration |
| `syncCommands` | `List<SyncCommand>` | no | Ordered BLE operations executed after CCCD setup; default empty |
| `parsing` | `ParsingConfig` | yes | Parsing mode and WASM binary |
| `specVersion` | `String` | no | Free-form string, default `"1"` (Hume Band uses `"4"`). Only read by a dead legacy per-packet code path — has no effect on `parseSession` or `buildSyncCommands`. |

**`BleConfig` fields:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `matchByName` | `String?` | no* | Device advertisement name prefix to match during scanning |
| `matchByServiceUuid` | `String?` | no* | 128-bit service UUID used as an advertisement filter |
| `matchConfidence` | `MatchConfidence` | yes | `CERTAIN` — unique to this model; `PROBABLE` — may match other devices |
| `services` | `List<String>` | yes | Service UUIDs to discover after connecting; must not be empty |
| `characteristics` | `Map<String, String>` | yes | Role name → 128-bit characteristic UUID; standard roles are `"notify"` and `"write"` |

\* At least one of `matchByName` or `matchByServiceUuid` must be set.

**`ParsingConfig` (discriminated by `mode` field):**

Only one variant exists:

| Field | Type | Description |
|-------|------|-------------|
| `mode` | `"WASM"` | Discriminator; always `"WASM"` |
| `wasmBase64` | `ByteArray` | WASM binary, Base64-encoded in the JSON file; decoded to raw bytes in memory |
| `exports` | `WasmExports` | Export function name mapping |

**`WasmExports` fields:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `parseSession` | `String?` | yes* | Name of the WASM export that bulk-parses an entire buffered sync session at once. This is the only export the engine actually calls to produce readings. |
| `buildSyncCommands` | `String?` | no | Name of the WASM export that dynamically builds sync commands at connection time; `null` means static list only |
| `parseMetrics` | `String?` | no | Legacy. `ManifestValidator` accepts a manifest with `parseMetrics` set instead of `parseSession`, but no engine code path ever calls a `parseMetrics` export. |
| `parseSleep` | `String?` | no | Legacy, declared for schema compatibility only. Never called — sleep is reported via `SLEEP_STAGE` readings inside `parseSession`'s output. |
| `parseActivity` | `String?` | no | Legacy, declared for schema compatibility only. Never called — there is currently no live path for activity data at all. |

\* Either `parseSession` or `parseMetrics` must be present per `ManifestValidator`; in
practice write new drivers against `parseSession`.

---

### SyncCommand Types

```kotlin
sealed class SyncCommand {
    data class Write(val characteristic: String, val bytes: String) : SyncCommand()
    data class EnableNotify(val characteristic: String) : SyncCommand()
    data class Delay(val millis: Long) : SyncCommand()
}
```

`characteristic` references a key in `ble.characteristics` (e.g. `"notify"`, `"write"`), not a raw UUID. `bytes` is space-separated hex literals, e.g. `"0x01 0xAB 0xFF"`.

---

### WASM Memory Layout and Runtime Contract

The WASM module receives JSON input and writes parsed JSON back using a shared linear
memory layout. The layout differs between `parseSession` and `buildSyncCommands` — they
are not the same call, and `manifest.specVersion` has no effect on either (it's read
only by a dead legacy per-packet code path).

**`parseSession` — no metadata header:**

```
Offset 0x0010 (   16)  INPUT REGION — up to ~50 000 bytes per call
  UTF-8 JSON array of buffered session frames:
  [{"characteristic": "...", "opcode": "0xNN", "bytes": "<base64>"}, ...]
  Large sessions are split into multiple chunks; parseSession is called once per
  chunk and the app concatenates the results.

Offset 0x1000 (4 096)  OUTPUT REGION — up to 61 440 bytes
  WASM writes a UTF-8 JSON array of readings here; export function returns byte count as i32
```

**`buildSyncCommands` — has a metadata header, called once at connect time:**

```
Offset 0x0000 (    0)  METADATA HEADER — 16 bytes
  Bytes 0–7:   currentTimeMs (i64, little-endian) — captured fresh at call time
  Bytes 8–9:   utcOffsetMinutes (i16, little-endian) — captured fresh at call time
  Bytes 10–15: reserved (zero-filled)

Offset 0x0010 (   16)  INPUT REGION
  UTF-8 SyncContext JSON string (fields populated per the manifest's syncRequirements)

Offset 0x0400 (1 024)  OUTPUT REGION
  WASM writes a UTF-8 JSON array of Write commands here; export function returns byte count as i32
```

**Export function signatures:**

```wat
(func (param i32 i32) (result i32))
  ; param 1 — input offset in linear memory
  ; param 2 — input byte length
  ; result  — number of bytes written to the export's output region
```

Both `parseSession` and `buildSyncCommands` share this shape; only the input content,
input offset semantics, and output offset differ as shown above.

**Security model:** The WASM sandbox (Chicory runtime) provides the security boundary. The WASM module cannot call Android APIs, access the network, read the filesystem, or execute arbitrary native code. Only the linear memory and declared imports are accessible. The app validates the WASM magic header (`0x00 0x61 0x73 0x6D`) before loading.

---

### WASM Output DTOs

**`MetricWasmDto` (output of `parseSession` — a JSON array of these):**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `metricType` | `MetricType` (enum name) | yes | The metric being reported. Sleep uses `SLEEP_STAGE` with stage details in `metaJson`; there is no separate sleep object. |
| `value` | `Double` | yes | Numeric measurement |
| `unit` | `String` | yes | Human-readable unit, e.g. `"bpm"`, `"ms"`, `"%"` |
| `recordedAtMs` | `Long` | yes | UTC epoch milliseconds when the sensor captured the value |
| `confidence` | `Float?` | no | Signal quality in `[0.0, 1.0]` |
| `metaJson` | `String?` | no | Driver-specific extras as a JSON object string |

This is the only DTO the live pipeline decodes from a WASM module's output. Two more
DTOs exist in source (`ble/wasm/WasmParseDto.kt`) but are never deserialized by any
code path — they're vestigial from the retired per-notification contract:

- **`SleepWasmDto`** — was the output of the legacy `parseSleep` export (end-of-night
  summary with `dateIso`/`sleepStartMs`/`sleepEndMs`/`stagesJson`). Dead.
- **`ActivityWasmDto`** — was the output of the legacy `parseActivity` export
  (`startTimeMs`/`endTimeMs`/`deviceName`/HR-and-calorie fields). Dead — there is
  currently no live path for activity data at all.

---

### MetricReading — Domain Object

Every number the system tracks flows through `MetricReading`.

```kotlin
data class MetricReading(
    val id: Long = 0,               // auto-generated PK; 0 = unsaved
    val metricType: MetricType,
    val value: Double,
    val unit: String,               // e.g. "bpm", "ms", "%", "steps"
    val recordedAt: Instant,        // when the sensor captured the value
    val createdAt: Instant,         // when the row was inserted
    val source: DataSource,         // DEVICE, MANUAL, or SEEDER
    val driverId: String? = null,   // null for manual/seeder rows
    val confidence: Float? = null,  // [0.0, 1.0]; null if device doesn't provide it
    val metaJson: String? = null,   // driver-specific JSON object string
)
```

`BLOOD_PRESSURE` readings encode diastolic in `metaJson["diastolic"]` because the domain object is designed for scalar values. The app extracts the diastolic value during routing.

---

### MetricType Enum

```kotlin
enum class MetricType {
    HR,               // heart rate (bpm)
    HRV,              // heart rate variability — RMSSD (ms)
    RHR,              // resting heart rate (bpm)
    SPO2,             // blood oxygen saturation (%)
    STEPS,            // step count — accumulator; daily running total
    SLEEP_STAGE,      // sleep stage marker; value maps to SleepStage ordinal
    BATTERY,          // device battery level (%); routed to device metadata only
    SKIN_TEMP,        // skin surface temperature (°C)
    BODY_TEMP,        // core body temperature (°C)
    TEMP_DEVIATION,   // deviation from baseline temperature (°C)
    VO2_MAX,          // aerobic capacity (mL/kg/min)
    DISTANCE,         // distance covered (metres) — accumulator
    ELEVATION_GAIN,   // cumulative ascent (metres) — accumulator
    ELEVATION_LOSS,   // cumulative descent (metres) — accumulator
    CALORIES,         // total calories burned (kcal) — accumulator
    ACTIVE_CALORIES,  // active (non-basal) calories (kcal) — accumulator
    BASAL_CALORIES,   // basal metabolic rate calories (kcal) — accumulator
    RESPIRATION,      // respiratory rate (breaths/min); routes to respiration_readings
    TOTAL_CALORIES,   // full-day expenditure including resting metabolic rate (kcal)
    BLOOD_PRESSURE,   // value = systolic (mmHg); diastolic in metaJson["diastolic"]
    GLUCOSE,          // blood glucose; unit field specifies "mmol" or "mg_dl"
}
```

**Companion sets:**

- `ACCUMULATOR_METRICS`: `{STEPS, CALORIES, ACTIVE_CALORIES, BASAL_CALORIES, DISTANCE, ELEVATION_GAIN, ELEVATION_LOSS}` — These accumulate over a UTC day. On device sync, a later record with a higher total replaces any earlier partial value using a value-guard upsert strategy.
- `DEDICATED_METRIC_TYPES`: `{HR, HRV, SPO2, RESPIRATION, SKIN_TEMP, STEPS, ACTIVE_CALORIES, TOTAL_CALORIES, BLOOD_PRESSURE, GLUCOSE, SLEEP_STAGE}` — `MetricRouter` routes these directly to a typed table or applies a special flag before staging. All others go to `metric_readings_staging` without special handling.

---

### Passive Driver Architecture

The engine calls the driver — never the reverse. The driver (WASM module) is a pure function: given bytes, return parsed values. It has no way to initiate any action.

- `BleEngine` decides when to call `parseSession` — once per sync (or once per
  chunk, for large sessions), from either `triggerSync()` (manual/quiescence-driven
  syncs) or `dispatchPostStreamParse()` (`awaitEndOfStream`-driven syncs) — and when
  to call `buildSyncCommands`, once at connection time.
- The WASM module executes synchronously within a coroutine on `Dispatchers.IO`.
- The module has no access to Android APIs, network, filesystem, or any external state beyond the memory region the engine provides.
- The engine owns the connection lifecycle entirely; the driver's `syncCommands` list is consumed by the engine, not executed by the driver.

---

### DriverRegistry Lifecycle

`DriverRegistry` is a Hilt `@Singleton`. It maintains a `CopyOnWriteArrayList<WasmDriverManifest>` for thread-safe reads without locking.

- **At app startup:** `initialiseDrivers()` loads all persisted driver manifests from `DriverStorage` and calls `register()` for each.
- **At registration:** If a driver with the same `id` already exists, it is replaced. If the newly registered driver matches the currently loaded WASM instance, that instance is unloaded so it will be recompiled from the new bytes on next use.
- **WASM loading:** Only one WASM instance is active at a time. `ensureWasmLoaded()` is guarded by a `Mutex`. If loading fails, the driver ID is added to a blacklist and no further load attempts are made for that session.
- **Driver switch:** When the active device uses driver A and a new device connects with driver B, `WasmDriverEngine.unload()` is called before loading B.

---

### Hume Band v1 — Reference Implementation

**File:** `Driver Builds/Hume Band 1/HumeBandDriver.json`

| Field | Value |
|-------|-------|
| `id` | `hume_band_j2208_v1` |
| `specVersion` | `"4"` |
| `displayName` | `"Hume Band J2208"` |
| `matchByName` | `"Hume Band 434B"` |
| `matchConfidence` | `CERTAIN` |
| `supportedMetrics` | HR, HRV, SPO2, STEPS, BATTERY, SKIN_TEMP, ACTIVE_CALORIES, BLOOD_PRESSURE, SLEEP_STAGE |

Sync command sequence: entirely generated by the WASM `buildSyncCommands` export at
connection time (because that export is present, its return value is the complete
command sequence — this holds regardless of `specVersion`; the manifest carries no
static `syncCommands` array). It builds `ENABLE_NOTIFY` on
the notify characteristic, then a series of `WRITE` commands (`0x13`, `0x01`, `0x02`,
`0x55`, `0x52`, `0x53`, `0x66`, `0x56`, `0x65`) — pacing uses `awaitReply`/
`awaitEndOfStream` (`IN_STREAM_TERMINATOR`), not `DELAY`. These byte sequences put the
J2208 chipset into streaming mode.

WASM exports: `parseSession` (required), `buildSyncCommands` (optional, dynamic
command generation).

This driver is the baseline for testing the full BLE-to-database pipeline. Any new driver should produce comparable output for shared metric types to validate that the routing and aggregation layers handle it correctly.

---

### User Manifest Management

**Loading a driver manifest:**
1. User taps "Add Driver" in DevicesScreen; system file picker opens.
2. JSON is read from the selected URI.
3. `ManifestValidator.validate()` checks: `id` non-blank, `version` is semver, `supportedMetrics` non-empty, `ble.services` non-empty, WASM magic header valid (`0x00 0x61 0x73 0x6D`), and at least one of `exports.parseSession` or `exports.parseMetrics` non-blank.
4. On success: `DriverRegistry.register(manifest)` → `DriverStorage.save()` persists the JSON to app-private storage.

**Viewing and deleting:**
- Listed in DevicesScreen with `displayName`, `version`, author, and `supportedMetrics`.
- Delete: `DriverStorage.delete(driverId)` → `DriverRegistry.unregister(driverId)`. If a device is currently connected using this driver, the connection is dropped.

---

## Database Architecture

### Schema Overview

**Database name:** `athlete_data.db` (Room, SQLite)
**Current version:** 13

| Table | Purpose |
|-------|---------|
| `hr_readings` | Dedicated time-series for heart rate (bpm) |
| `hrv_readings` | Dedicated time-series for HRV RMSSD (ms) |
| `spo2_readings` | Dedicated time-series for blood oxygen (%) |
| `respiration_readings` | Dedicated time-series for respiratory rate (brpm) |
| `skin_temp_readings` | Dedicated time-series for skin surface temperature (°C) |
| `steps_readings` | Dedicated time-series for cumulative daily step count |
| `blood_pressure_readings` | Dedicated time-series for blood pressure (systolic + diastolic) |
| `glucose_readings` | Dedicated time-series for blood glucose |
| `active_calorie_readings` | Dedicated time-series for active calorie expenditure |
| `total_calorie_readings` | Dedicated time-series for total daily calorie expenditure |
| `sleep_sessions` | One row per sleep night |
| `sleep_stages` | One row per discrete stage block within a session |
| `metric_readings_staging` | Landing zone for unknown/unrouted metric types |
| `daily_summary` | Pre-computed daily aggregates (one row per day) |
| `daily_context` | User-entered subjective scores and wellness data |
| `devices` | Known BLE devices |
| `sync_sessions` | Sync attempt log with status and counts |
| `raw_device_data` | Raw characteristic payloads for reprocessing |
| `activities` | Activity/workout records |
| `question_definitions` | Custom subjective questions |
| `question_responses` | User answers to questions, keyed by date |
| `widget_layout` | Dashboard widget grid configuration |

All typed reading tables (`hr_readings` through `total_calorie_readings`) and `sleep_stages` were added in `MIGRATION_10_11`. The old flat `metric_readings` table was renamed to `metric_readings_staging` at the same migration.

---

### Table: `metric_readings_staging`

**Purpose:** Landing zone for metric types that do not yet have a dedicated typed table (e.g. `RHR`, `BODY_TEMP`, `VO2_MAX`). Also receives `SLEEP_STAGE` readings pending promotion to `sleep_stages`, and blood pressure readings where diastolic is missing.

| Column | Type | Nullable | Notes |
|--------|------|----------|-------|
| `id` | `INTEGER` PK autoincrement | no | |
| `metric_type` | `TEXT` | no | `MetricType` enum name |
| `value` | `REAL` | no | Numeric measurement |
| `unit` | `TEXT` | no | Human-readable unit |
| `recorded_at` | `INTEGER` | no | Unix epoch ms (`Instant`) |
| `created_at` | `INTEGER` | no | Unix epoch ms (`Instant`) |
| `source` | `TEXT` | no | `DataSource` enum name: `DEVICE`, `MANUAL`, `SEEDER` |
| `driver_id` | `TEXT` | yes | Null for manual/seeder rows |
| `confidence` | `REAL` | yes | Signal quality `[0.0, 1.0]` |
| `meta_json` | `TEXT` | yes | Driver-specific JSON; `SLEEP_STAGE` rows carry `{"pending_sleep_stage": true}` here |

**Indices:**
- `(metric_type, recorded_at ASC/DESC)` — for type-filtered range queries
- `(driver_id, metric_type, recorded_at)` UNIQUE — deduplication key; prevents duplicate records for the same driver, type, and timestamp

**Writers:** `MetricRouter` (via `MetricReadingStagingRepository`); seeder; manual entry.
**Readers:** `HistoryViewModel`, `SleepStagePromoter`, `DeviceSyncProcessor` (reprocess path).

---

### Table: `sleep_sessions`

**Purpose:** One row per sleep night, keyed by the morning wake-up date. Stage data is normalized into the separate `sleep_stages` table.

| Column | Type | Nullable | Notes |
|--------|------|----------|-------|
| `id` | `INTEGER` PK autoincrement | no | |
| `date` | `TEXT` | no | `LocalDate` as `"YYYY-MM-DD"` (ISO-8601) — the morning date |
| `sleep_start_ms` | `INTEGER` | no | Unix epoch ms (`Instant`) |
| `sleep_end_ms` | `INTEGER` | no | Unix epoch ms (`Instant`) |
| `duration_minutes` | `INTEGER` | no | Total sleep duration |
| `source` | `TEXT` | no | `DataSource` enum name |
| `driver_id` | `TEXT` | yes | Null for manual/seeder rows |

**Index:** `(driver_id, date)` UNIQUE — prevents duplicate sessions from the same driver for the same night.

**Writers:** `RoomSleepRepository` (device sync via `DeviceSyncProcessor`, seeder, manual entry). After every insert, the repository enqueues `DailySummaryWorker` for the session date.
**Readers:** `DailySummaryWorker`, `HistoryViewModel`, `HomeViewModel`.

---

### Table: `daily_summary`

**Purpose:** Pre-computed daily aggregate roll-up. One row per calendar day. Written exclusively by `DailySummaryWorker`; never written directly by device sync or UI.

| Column | Type | Nullable | Notes |
|--------|------|----------|-------|
| `date` | `TEXT` PK | no | `LocalDate` as `"YYYY-MM-DD"` |
| `avg_hr_bpm` | `REAL` | yes | Mean of all HR readings for the day |
| `resting_hr_bpm` | `REAL` | yes | Min of 5-minute bucketed averages before 06:00 UTC |
| `avg_hrv_ms` | `REAL` | yes | Mean RMSSD across all HRV readings |
| `morning_hrv_ms` | `REAL` | yes | Earliest HRV reading after 05:00 UTC |
| `hrv_min_ms` | `REAL` | yes | Min RMSSD for the day |
| `hrv_max_ms` | `REAL` | yes | Max RMSSD for the day |
| `avg_spo2_pct` | `REAL` | yes | Mean blood oxygen |
| `spo2_min_pct` | `REAL` | yes | Min blood oxygen |
| `spo2_max_pct` | `REAL` | yes | Max blood oxygen |
| `steps` | `INTEGER` | yes | From the latest `steps_readings` row (cumulative daily total) |
| `sleep_minutes` | `INTEGER` | yes | Total sleep from `sleep_sessions.duration_minutes` |
| `sleep_deep_minutes` | `INTEGER` | yes | Sum of `sleep_stages` rows with `stage = DEEP` |
| `sleep_light_minutes` | `INTEGER` | yes | Sum of `sleep_stages` rows with `stage = LIGHT` |
| `sleep_rem_minutes` | `INTEGER` | yes | Sum of `sleep_stages` rows with `stage = REM` |
| `sleep_awake_minutes` | `INTEGER` | yes | Sum of `sleep_stages` rows with `stage = AWAKE` |
| `skin_temp_avg_c` | `REAL` | yes | Mean skin temperature |
| `skin_temp_min_c` | `REAL` | yes | Min skin temperature |
| `skin_temp_max_c` | `REAL` | yes | Max skin temperature |
| `respiration_avg` | `REAL` | yes | Mean breaths per minute |
| `steps_active_minutes` | `INTEGER` | yes | Sum of intervals with >500 step delta and gap ≤ 60 min |
| `total_calories` | `REAL` | yes | From the latest `total_calorie_readings` row |
| `active_calories` | `REAL` | yes | Sum of `active_calorie_readings` for the day |
| `computed_by_version` | `INTEGER` | no | Aggregation algorithm version; default 0 |
| `source` | `TEXT` | no | Dominant `DataSource` across all readings for the day |
| `computed_at` | `INTEGER` | no | Unix epoch ms when the worker ran |

**Primary key:** `date` — upsert with `OnConflictStrategy.REPLACE` is safe and idempotent.
**Writers:** `DailySummaryWorker` only.
**Readers:** `HomeViewModel`, `HistoryViewModel`, `DailyDetailViewModel`.

---

### Table: `daily_context`

**Purpose:** User-entered subjective wellness state. One row per day. No `source` column — context is always user-entered or seeder-generated; the cleanup path calls `deleteAll()` rather than `deleteBySource()`.

| Column | Type | Nullable | Notes |
|--------|------|----------|-------|
| `date` | `TEXT` PK | no | `LocalDate` as `"YYYY-MM-DD"` |
| `fatigue` | `INTEGER` | yes | Subjective fatigue score |
| `stress` | `INTEGER` | yes | Subjective stress score |
| `motivation` | `INTEGER` | yes | Subjective motivation score |
| `sleep_quality` | `INTEGER` | yes | Subjective sleep quality score |
| `performance_feel` | `INTEGER` | yes | Subjective performance feel score |
| `is_ill` | `INTEGER` | no | Boolean; default `0` (false) |
| `illness_notes` | `TEXT` | yes | Free-text illness description |
| `habits_json` | `TEXT` | yes | JSON-serialized habits checklist |
| `weight_kg` | `REAL` | yes | Body mass in kg |
| `body_fat_pct` | `REAL` | yes | Body fat percentage |
| `notes` | `TEXT` | yes | Free-text daily notes |
| `updated_at` | `INTEGER` | no | Unix epoch ms (`Instant`) of last update |

**Primary key:** `date` — upsert replaces all fields for a given date.
**Writers:** Daily Questions screen, Weight sheet (via UI ViewModels).
**Readers:** `DailyDetailViewModel`, `HistoryViewModel`.

---

### Typed Time-Series Tables (v11+)

All typed tables share a common base column set. Each adds one or more type-specific columns.

**Common base columns (all typed tables):**

| Column | Type | Nullable | Notes |
|--------|------|----------|-------|
| `id` | `INTEGER` PK autoincrement | no | |
| `recorded_at` | `INTEGER` | no | Unix epoch ms |
| `created_at` | `INTEGER` | no | Unix epoch ms |
| `source` | `TEXT` | no | `DataSource` enum name |
| `driver_id` | `TEXT` | yes | |
| `confidence` | `REAL` | yes | `[0.0, 1.0]` |
| `meta_json` | `TEXT` | yes | |

**Index on all typed tables:** `(driver_id, recorded_at)` UNIQUE — deduplication key.

**Type-specific columns:**

| Table | Extra column(s) | Type |
|-------|-----------------|------|
| `hr_readings` | `bpm` | `INTEGER` |
| `hrv_readings` | `rmssd_ms`, `computed_by_version` | `REAL`, `INTEGER` |
| `spo2_readings` | `percentage` | `REAL` |
| `respiration_readings` | `breaths_per_minute` | `REAL` |
| `skin_temp_readings` | `celsius` | `REAL` |
| `steps_readings` | `cumulative_steps` | `INTEGER` |
| `blood_pressure_readings` | `systolic`, `diastolic` | `INTEGER`, `INTEGER` |
| `glucose_readings` | `value`, `unit` | `REAL`, `TEXT` (`"mmol"` or `"mg_dl"`) |
| `active_calorie_readings` | `calories` | `REAL` |
| `total_calorie_readings` | `calories` | `REAL` |

**Writers:** `MetricRouter` via the corresponding typed repository, during BLE packet processing.
**Readers:** `DailySummaryWorker` (one-shot range query per table per day), `HistoryViewModel` (live range queries).

---

### Table: `sleep_stages`

**Purpose:** One row per discrete sleep stage block. Normalized from `sleep_sessions`; cascade-deleted when the parent session is removed.

| Column | Type | Nullable | Notes |
|--------|------|----------|-------|
| `id` | `INTEGER` PK autoincrement | no | |
| `session_id` | `INTEGER` FK → `sleep_sessions(id)` CASCADE | no | |
| `stage` | `TEXT` | no | `SleepStage` enum name: `DEEP`, `LIGHT`, `REM`, `AWAKE` |
| `start_ms` | `INTEGER` | no | Unix epoch ms |
| `end_ms` | `INTEGER` | no | Unix epoch ms |
| `duration_minutes` | `INTEGER` | no | |
| `source` | `TEXT` | no | `DataSource` enum name |
| `driver_id` | `TEXT` | yes | |
| `computed_by_version` | `INTEGER` | no | Algorithm version for the stage computation |

**Index:** `(session_id, start_ms)` UNIQUE — prevents duplicate stage blocks within a session.

Intentionally omits standard time-series columns (`recorded_at`, `created_at`, `confidence`, `meta_json`) because stage data is always computed from the session rather than recorded as a point-in-time reading.

**Writers:** `SleepStagePromoter` — promotes `SLEEP_STAGE` rows from `metric_readings_staging` into this table after the sync window is confirmed complete.
**Readers:** `DailySummaryWorker` (fetches all stages for a session to compute stage-minute breakdowns), `DailyDetailViewModel`.

---

### The `source` Field

`DataSource` enum values: `DEVICE`, `MANUAL`, `SEEDER`.

**Present on:** all typed time-series tables, `metric_readings_staging`, `sleep_sessions`, `sleep_stages`, `activities`, `daily_summary`.

**Absent from:** `daily_context` — user context is always user-entered or seeder-generated; the seeder cleanup calls `deleteAll()` instead of `deleteBySource()`.

**Why it exists:**
1. **Seeder cleanup:** All seeder-generated data can be removed in one `deleteBySource(SEEDER)` call across every table, without touching device or manual data.
2. **Dominant source for daily summary:** `DailySummaryWorker` counts the frequency of each `DataSource` value across all readings for the day and stores the most common value as `daily_summary.source`, allowing the UI to indicate whether a day's data came primarily from a device or was manually entered.
3. **Manual entry protection:** Accumulator metric upserts respect `source`; a device reading does not replace a manually entered value if it is lower.

---

### TypeConverter Implementations

Registered at the `AppDatabase` level (`@TypeConverters(Converters::class)`) so they apply to every entity field, every `@Query` parameter, and every query result column.

| Kotlin type | SQLite type | Conversion |
|-------------|-------------|-----------|
| `LocalDate` | `TEXT` | ISO-8601 string `"YYYY-MM-DD"` via `LocalDate.toString()` / `LocalDate.parse()` |
| `Instant` | `INTEGER` | Unix epoch milliseconds via `toEpochMilli()` / `Instant.ofEpochMilli()` |
| `MetricType` | `TEXT` | Enum name via `.name` / `MetricType.valueOf()` |
| `DataSource` | `TEXT` | Enum name via `.name` / `DataSource.valueOf()` |
| `UserCategory` | `TEXT` | Nullable enum name; null → null |
| `SyncStatus` | `TEXT` | Enum name |
| `SleepStage` | `TEXT` | Enum name |

---

### DailySummaryWorker

**Trigger:** Any write to a typed time-series table or `sleep_sessions` enqueues this worker for the affected date via `enqueueSummaryWorker(date, workManager)` using `ExistingWorkPolicy.REPLACE`. Multiple triggers for the same date coalesce into a single execution.

**Input:** Date as an ISO string in `inputData`.

**Day boundaries:** Midnight UTC → next midnight UTC (full 24-hour window).

**Special windows:**
- `night_end = 06:00 UTC` — upper bound for resting HR computation
- `morning_start = 05:00 UTC` — lower bound for morning HRV

**Computation per metric:**

| Metric | Aggregation |
|--------|-------------|
| HR average | Mean of all `hr_readings.bpm` for the day |
| Resting HR | Minimum of 5-minute bucket averages for readings before 06:00 UTC |
| HRV average | Mean of all `hrv_readings.rmssd_ms` |
| Morning HRV | Earliest `hrv_readings` row after 05:00 UTC |
| HRV min/max | Min and max `rmssd_ms` for the day |
| SpO2 average/min/max | Mean, min, max of `spo2_readings.percentage` |
| Skin temp average/min/max | Mean, min, max of `skin_temp_readings.celsius` |
| Respiration | Mean of `respiration_readings.breaths_per_minute` |
| Steps | `cumulative_steps` from the latest `steps_readings` row |
| Steps active minutes | Sum of intervals where the step delta > 500 and the gap between consecutive readings is ≤ 60 min |
| Active calories | Sum of `active_calorie_readings.calories` |
| Total calories | `calories` from the latest `total_calorie_readings` row |
| Sleep minutes | `sleep_sessions.duration_minutes` for the session with `date = today` |
| Sleep stage minutes | Sum of `sleep_stages.duration_minutes` grouped by `stage` |
| Dominant source | Mode (most frequent) `DataSource` across all readings |

**Upsert strategy:** Calls `dailySummaryRepository.upsert()` which uses `OnConflictStrategy.REPLACE`. Re-running the worker for the same date always produces a consistent result; there is no partial-update risk.

**Return:** `Result.success()` on completion; `Result.retry()` on exception (WorkManager handles re-scheduling).

---

### Repository Interfaces

**`MetricReadingStagingRepository`**

```kotlin
interface MetricReadingStagingRepository {
    suspend fun insert(reading: MetricReading)
    suspend fun insertAll(readings: List<MetricReading>)
    suspend fun insertAllFromDevice(readings: List<MetricReading>): DeviceInsertResult
    suspend fun replaceAllFromDevice(readings: List<MetricReading>): Int
    suspend fun insertManual(reading: MetricReading)
    fun getReadingsForDay(date: LocalDate, type: MetricType): Flow<List<MetricReading>>
    fun getReadingsForRange(from: LocalDate, to: LocalDate, type: MetricType): Flow<List<MetricReading>>
    fun getLatestReading(type: MetricType): Flow<MetricReading?>
    suspend fun deleteBySource(source: DataSource)
    fun hasSeederDataForDate(date: LocalDate): Flow<Boolean>
    suspend fun hasSeederReadingsForDateOnce(date: LocalDate): Boolean
    suspend fun getPendingSleepStages(source: DataSource, driverId: String, startMs: Long, endMs: Long): List<MetricReading>
    suspend fun deleteByIds(ids: List<Long>)
}
```

`insertAllFromDevice`: point-in-time metrics skip duplicates (insert-or-ignore); accumulator metrics use a value guard that keeps the stored record if it is already higher than the incoming value.

`replaceAllFromDevice`: uses REPLACE for all types — for reprocess runs where corrected driver output must overwrite prior incorrect records.

**`SleepRepository`**

```kotlin
interface SleepRepository {
    suspend fun insert(session: SleepSession)
    suspend fun insertFromDevice(session: SleepSession): Int    // 1 if already existed, 0 if new
    suspend fun insertOrReplace(session: SleepSession)
    fun getSessionForDate(date: LocalDate): Flow<SleepSession?>
    fun getSessionsForRange(from: LocalDate, to: LocalDate): Flow<List<SleepSession>>
    suspend fun getSessionForDateOnce(date: LocalDate): SleepSession?
    suspend fun getByDriverAndDate(driverId: String, date: LocalDate): SleepSession?
    suspend fun deleteBySource(source: DataSource)
}
```

`insertOrReplace`: a correct final morning session always wins over a prior partial record from the same driver.

**`DailySummaryRepository`**

```kotlin
interface DailySummaryRepository {
    suspend fun upsert(summary: DailySummary)
    fun getSummaryForDate(date: LocalDate): Flow<DailySummary?>
    fun getSummariesForRange(from: LocalDate, to: LocalDate): Flow<List<DailySummary>>
    suspend fun getSummaryForDateOnce(date: LocalDate): DailySummary?
    suspend fun deleteAll()
}
```

`DailySummaryWorker` is the only writer. `deleteAll()` is called after seeder data is cleared so stale pre-computed rows don't persist.

**`DailyContextRepository`**

```kotlin
interface DailyContextRepository {
    suspend fun upsert(context: DailyContext)
    fun getForDate(date: LocalDate): Flow<DailyContext?>
    fun getForRange(from: LocalDate, to: LocalDate): Flow<List<DailyContext>>
    suspend fun getForDateOnce(date: LocalDate): DailyContext?
    suspend fun deleteAll()
}
```

A second `upsert()` for the same date replaces all fields in place (full row replacement, not a patch).

**`SettingsRepository`**

```kotlin
interface SettingsRepository {
    fun getThemePreference(): Flow<ThemePreference>          // defaults to SYSTEM
    suspend fun setThemePreference(pref: ThemePreference)
    suspend fun clearAllPreferences()
    fun getHistoryMetricKey(): Flow<String?>
    suspend fun setHistoryMetricKey(key: String?)
    fun getDailyDetailTileConfig(): Flow<List<TileConfig>>
    suspend fun setDailyDetailTileConfig(configs: List<TileConfig>)
}
```

Backed by `DataStore<Preferences>` (`"settings.preferences_pb"`). Not Room — no `@Entity`.

**Typed reading repositories** (one per typed table: `HrReadingRepository`, `HrvReadingRepository`, `SpO2ReadingRepository`, `RespirationReadingRepository`, `SkinTempReadingRepository`, `StepsReadingRepository`, `BloodPressureReadingRepository`, `GlucoseReadingRepository`, `ActiveCalorieReadingRepository`, `TotalCalorieReadingRepository`, `SleepStageRepository`) all implement the `BaseReadingDao` contract:

```kotlin
interface BaseReadingDao<T> {
    suspend fun insert(entity: T)
    suspend fun insertAll(entities: List<T>)
    suspend fun insertAllOrIgnore(entities: List<T>): List<Long>
    suspend fun deleteBySource(source: DataSource)
    suspend fun deleteAll()
    suspend fun getReadingsInRangeOnce(startMs: Long, endMs: Long): List<T>
}
```

---

### Hilt Module Structure

**`DatabaseModule`** (`@InstallIn(SingletonComponent::class)`)

Provides:
- `AppDatabase` — built with `Room.databaseBuilder`, all migrations registered, database name `"athlete_data.db"`
- All DAO instances — one `@Provides @Singleton` method per DAO, calling the corresponding `AppDatabase` abstract getter

Binds (via `@Binds @Singleton`):
- Every Room-backed repository implementation to its interface: `RoomMetricReadingStagingRepository` → `MetricReadingStagingRepository`, `RoomSleepRepository` → `SleepRepository`, `RoomDailySummaryRepository` → `DailySummaryRepository`, `RoomDailyContextRepository` → `DailyContextRepository`, and all typed reading repositories.

**`DataStoreModule`** (`@InstallIn(SingletonComponent::class)`)

Kept separate from `DatabaseModule` so that DataStore's dependency (`"androidx.datastore:datastore-preferences"`) does not pull Room into the DataStore graph.

Provides:
- `DataStore<Preferences>` — created by `PreferenceDataStoreFactory`, backed by `"settings.preferences_pb"` in the app's private files directory

Binds:
- `DataStoreSettingsRepository` → `SettingsRepository`
