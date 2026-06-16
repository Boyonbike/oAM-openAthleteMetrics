# Driver System Audit Report

**Date:** 2026-06-16
**Scope:** Read-only audit of `docs/DRIVER_AUTHORING_GUIDE.md` and the driver system
implementation. No files were modified during this audit.

---

## Guide Status

**File:** `docs/DRIVER_AUTHORING_GUIDE.md`
**Last updated:** No date in file; cannot determine from file content alone.
**Scope:** Covers exclusively the JSON+WASM manifest driver format. Opens with the statement
"All drivers use WASM mode for parsing." Long, well-structured, and comprehensive for its
stated scope. Covers WASM memory layout, JSON output schemas, sync command format,
deduplication rules, timestamp rules, and a submission checklist.

**Secondary sources checked:**
- `CONTRIBUTING.md` — references the guide and describes the PR workflow. Has two broken
  links (see below).
- `app/src/main/assets/drivers/example_simple.json` — a bundled example that cannot be
  loaded by the current app (see Implementation Gaps).
- `app/src/main/assets/drivers/example_wasm.json` (`polar_h10_wasm_v1`) — a valid WASM
  example driver; matches guide format correctly.
- `Driver Builds/Hume Band 1/HumeBandDriver.json` — a real driver in active use; valid WASM
  format.

---

## Accurate — Items correctly documented

1. **All WASM manifest top-level fields:** `id`, `displayName`, `version`, `specVersion`,
   `author`, `supportedMetrics`, `ble`, `syncCommands`, `parsing` — all present in
   `WasmDriverManifest.kt` and described correctly.
2. **The `ble` block** (`matchByName`, `matchByServiceUuid`, `matchConfidence`, `services`,
   `characteristics`) matches `BleConfig` exactly, including the optional/required distinction.
3. **`matchConfidence` values** `CERTAIN`/`PROBABLE` and the resolution semantics (CERTAIN wins
   before PROBABLE) match `DriverRegistry.resolve()` exactly.
4. **`syncCommands` format** — WRITE, ENABLE_NOTIFY, DELAY types and their fields match
   `SyncCommand.kt` exactly.
5. **`parsing` block** — `mode`, `wasmBase64`, `exports.parseMetrics`, `exports.parseSleep`,
   `exports.parseActivity` match `ParsingConfig.WasmParsing` and `WasmExports` exactly.
6. **WASM-only mode** — The guide correctly states that only `"mode": "WASM"` is accepted.
   `ManifestValidator.kt` line 47 confirms all other modes are rejected.
7. **Memory layout (spec v1 / spec v2)** — `WasmDriverEngine.callParse()` confirms: v2 writes
   metadata at offset 0–15, input at offset 16, output at offset 0x1000 (4096).
8. **Metadata region fields** — `syncStartMs` as i64 LE at offset 0, `utcOffsetMinutes` as
   i16 LE at offset 8, confirmed in `WasmDriverEngine.callParse()`.
9. **WASM function signature** `(param i32 i32) (result i32)` — confirmed by `callParse()`
   call: `inst.export(functionName).apply(inputOffset.toLong(), data.size.toLong())`.
10. **Stale-byte zeroing for spec v2** — confirmed in `WasmDriverEngine.callParse()`.
11. **JSON output schemas** for `parseMetrics`, `parseSleep`, `parseActivity` — match
    `MetricWasmDto`, `SleepWasmDto`, `ActivityWasmDto` in `WasmParseDto.kt` exactly. All
    fields including optional `confidence`, `metaJson`, `stagesJson`, `hrZonesJson` are
    correctly described.
12. **`durationMinutes: 0` guidance for parseSleep** — confirmed correct: `WasmDriverEngine
    .parseSleep()` overrides the DTO's `durationMinutes` with the computed value before the
    `SleepSession` is created, so the validator never sees `0`.
13. **`stageAwareEndInstant` behaviour** — `WasmDriverEngine.parseSleep()` extends `sleepEndMs`
    to cover stage blocks past the reported end. Matches the guide's note about not trimming
    AWAKE periods.
14. **ManifestValidator checks** — `id` not blank, semver `X.Y.Z`, `supportedMetrics` not
    empty, `ble.services` not empty, `parsing.mode` must be `"WASM"`, WASM magic header check,
    `exports.parseMetrics` not blank — all match `ManifestValidator.kt` exactly.
15. **Deduplication keys** — `driver_id + metric_type + recorded_at` for metric readings;
    `driver_id + date` for sleep sessions; `driver_id + startTimeMs` for activities —
    confirmed in `DeviceSyncProcessor` and the guide's table.
16. **ACCUMULATOR_METRICS set** — the guide names `STEPS, CALORIES, ACTIVE_CALORIES,
    BASAL_CALORIES, DISTANCE, ELEVATION_GAIN, ELEVATION_LOSS` as accumulators. The code's
    `MetricType.ACCUMULATOR_METRICS` companion set matches exactly.
17. **UTC epoch milliseconds requirement** — confirmed throughout `WasmDriverEngine` and
    `SyncValidator`.
18. **`SLEEP_STAGE` excluded from `supportedMetrics`** — correctly documented; sleep support
    is declared via `parseSleep` in exports.
19. **BATTERY routing to device metadata** — the documented intent is correct: battery is
    extracted in `DeviceSyncProcessor.process()` and written to the device row via
    `updateLastBatteryPct()`. (See Implementation Gaps for a code-level side effect.)
20. **Sleep session merge across syncs** — `DeviceSyncProcessor.buildMergedSessions()` fetches
    existing sessions by `(driverId, date)` and merges; confirmed correct.
21. **Multiple sessions per date merged** — `SleepMerger` (called by `DeviceSyncProcessor`)
    handles multiple incoming sessions per date.
22. **`specVersion: "2"` requirement** — guide says "All new drivers should use `"2"`". Default
    in `WasmDriverManifest` is `"1"` with a warning logged by `WasmDriverEngine` if v1 is
    used. Correct guidance.
23. **Scan timeout of 30 seconds** — `BleEngine.SCAN_TIMEOUT_MS = 30_000L`, confirmed.
24. **Raw BLE data retained for 7 days** — `DeviceSyncProcessor.schedulePrune()` deletes rows
    older than 7 days, confirmed.
25. **Sync processing steps** — `beginSession()` creates the PARTIAL session row on first
    packet; raw packets are persisted on arrival in `handleNotification()`. Both confirmed.
26. **Reprocess from raw data** — `DeviceSyncProcessor.processFromRaw()` confirmed. The
    guide's description of what Reprocess can and cannot fix is substantially correct (with
    one important caveat — see Implementation Gaps).
27. **Chicory trap → re-instantiation** — confirmed in `WasmDriverEngine.callParse()` catch
    block.
28. **Submission checklist** — all structural, timestamp, and WASM-correctness items are
    consistent with the code.

---

## Outdated — Items that were correct before the refactor but no longer are

### 1. `RESPIRATORY_RATE` renamed to `RESPIRATION` (critical for driver authors)

The guide's Supported Metric Types section lists:
```
RESPIRATORY_RATE   Breaths per minute
```

The actual `MetricType` enum has:
```kotlin
RESPIRATION,  // Canonical value for respiratory rate (breaths per minute).
```

The code comment on `RESPIRATION` in `MetricType.kt` explicitly states:
> "Driver authors: if your driver previously emitted `RESPIRATORY_RATE` (removed), update
> your `supportedMetrics` and metric type output to use `RESPIRATION` instead."

Any driver following the guide and emitting `"metricType": "RESPIRATORY_RATE"` from
`parseMetrics` will have every reading rejected by `SyncValidator` because `RESPIRATORY_RATE`
is not in `MetricType.entries`. The guide checklist says to only emit types from its own list
— but its list names the wrong string.

### 2. `metric_readings` table reference (critical for understanding routing)

The guide's Sync Processing step 5 says:
> "3. Inserts valid data into `metric_readings`, `sleep_sessions`, and `activities`"

The table `metric_readings` does not exist in the schema after the refactor. Readings are
routed to 10 dedicated typed tables or to `metric_readings_staging` (catch-all, also holds
SLEEP_STAGE entries). The table name `metric_readings` appears once in the guide and is the
only place the refactor introduced an outright factual error into existing prose.

---

## Missing — Items not documented at all

### A. Three MetricType values with dedicated routing

Three `MetricType` values exist in code and have dedicated routing paths that the guide does
not list as valid `supportedMetrics` values:

| Type | Table |
|---|---|
| `TOTAL_CALORIES` | `total_calorie_readings` |
| `BLOOD_PRESSURE` | `blood_pressure_readings` (conditional — see below) |
| `GLUCOSE` | `glucose_readings` |

A driver author consulting the guide cannot know these types exist or are supported.

### B. `BLOOD_PRESSURE` diastolic encoding contract

`BleEngine.routeReading()` implements special logic for BLOOD_PRESSURE:

```kotlin
MetricType.BLOOD_PRESSURE -> {
    val diastolic = runCatching {
        JSONObject(reading.metaJson ?: "").getInt("diastolic")
    }.getOrNull()
    if (diastolic != null) {
        bloodPressureReadingRepository.insert(...)
    } else {
        stagingRepository.insert(reading)  // silent fallback
    }
}
```

A `BLOOD_PRESSURE` reading **must** encode diastolic pressure as an integer in
`metaJson["diastolic"]`; `value` carries systolic. If `diastolic` is absent or unparseable,
the reading silently goes to `metric_readings_staging` instead of `blood_pressure_readings`.
This contract is defined only in a code comment on the `MetricType` enum and is entirely
absent from the guide.

### C. Table routing map for all dedicated types

The guide never tells a driver author where any given metric type lands. The complete routing
implemented in `BleEngine.routeReading()` is:

| MetricType | Table |
|---|---|
| `HR` | `hr_readings` |
| `HRV` | `hrv_readings` |
| `SPO2` | `spo2_readings` |
| `RESPIRATION` | `respiration_readings` |
| `SKIN_TEMP` | `skin_temp_readings` |
| `STEPS` | `steps_readings` |
| `ACTIVE_CALORIES` | `active_calorie_readings` |
| `TOTAL_CALORIES` | `total_calorie_readings` |
| `BLOOD_PRESSURE` | `blood_pressure_readings` (or staging if diastolic missing) |
| `GLUCOSE` | `glucose_readings` |
| `SLEEP_STAGE` | `metric_readings_staging` with `pending_sleep_stage` flag, then promoted |
| All others | `metric_readings_staging` |

### D. `SLEEP_STAGE` as a MetricType emittable from `parseMetrics`

The guide correctly says sleep capability is declared via `parseSleep` in exports, and that
`SLEEP_STAGE` should not appear in `supportedMetrics`. That is the correct description for
the `parseSleep` code path.

However, `BleEngine.routeReading()` implements a second, entirely undocumented path: a driver
can emit `metricType: "SLEEP_STAGE"` from `parseMetrics`. When it does, the engine merges
`{"pending_sleep_stage": true}` into the reading's `metaJson` and inserts to staging. After
quiescence, `SleepStagePromoter.promote()` reads those rows, extracts `stage`, `start_ms`,
and `end_ms` from `metaJson`, groups by UTC date, creates or reuses a `SleepSession`, and
inserts `SleepStageEntity` rows into `sleep_stages`.

For this path to work, the WASM module must encode stage data in `metaJson` as:
```json
{"stage": "DEEP", "start_ms": 1234567890000, "end_ms": 1234567891000}
```

Valid stage values are `DEEP`, `LIGHT`, `REM`, `AWAKE` (the `SleepStage` enum). None of this
is documented anywhere. The `SleepStagePromoter` itself is not mentioned in the guide.

### E. `DeviceDriver` and `MetricProcessor` interfaces

The guide states the system is WASM-only. The code contains a native Kotlin extension point.
`DeviceDriver.kt` defines:
```kotlin
interface DeviceDriver {
    fun createProcessor(): MetricProcessor? = null
}
```

`BleEngine.connect()` line 545:
```kotlin
currentProcessor = (activeManifest as? DeviceDriver)?.createProcessor()
```

`MetricProcessor` has two methods: `onReading(reading: MetricReading)` called for every
parsed reading during a sync, and `onSyncComplete(): List<MetricReading>` called at
quiescence, whose return value is routed through `routeReading()` as derived readings.

Currently `WasmDriverManifest` does not implement `DeviceDriver`, so `currentProcessor` is
always null and `MetricProcessor` is never instantiated. No class in the codebase implements
`DeviceDriver`. The interface exists as scaffolding for a native Kotlin driver path. The rich
DeviceDriver interface described as part of this project's architecture (`id`, `displayName`,
`matches()`, `buildSyncCommands()`, `parse()`, `onConnected()`, `onDisconnected()`) does not
exist in the current codebase — the current `DeviceDriver` interface has exactly one method.

### F. `computed_by_version` field

`HrvReadingEntity` and `SleepStageEntity` carry a `computedByVersion` column. `BleEngine`
hardcodes it to `1` for HRV readings; `SleepStagePromoter` hardcodes it to `1` for sleep
stages. The guide makes no mention of this field, its purpose, or any expectation that a
driver influence it. WASM drivers cannot control this value — it is set by the engine.

### G. `BATTERY` also writes to staging

The guide correctly states BATTERY goes to device metadata and is not stored in
`metric_readings`. In practice, `BATTERY` has no case in `routeReading()`'s `when` block, so
it falls to `else -> stagingRepository.insert(reading)`. BATTERY readings are written to
`metric_readings_staging` as a side effect, and separately used to update device metadata via
`DeviceSyncProcessor.updateLastBatteryPct()`. The guide's statement about routing is correct
in intent but incomplete about what actually happens at runtime.

### H. One-active-driver-at-a-time constraint

The system supports multiple loaded manifest drivers for scanning but only one active WASM
instance at a time (`WasmDriverEngine` holds a single `Instance`). The guide does not
document this constraint or its implications (e.g. a second device connection attempt while
one is active will be blocked by the connection state machine).

### I. Packet reassembly strategy

`BleEngine.handleNotification()` implements a "short-packet terminal" reassembly strategy:
ATT fragments whose size equals the negotiated MTU payload max are buffered; a fragment
shorter than the max is treated as the final fragment and the assembled packet is forwarded to
the WASM parser. The guide does not document this. For devices with larger packets or
different framing conventions (length-prefixed, fixed-frame, continuation-flag), this matters
for driver authors.

### J. Silent-sync timeout and GATT cache refresh

After sync commands complete, if no BLE notifications arrive within 15 seconds
(`SILENT_SYNC_TIMEOUT_MS`), the engine attempts `BluetoothGatt.refresh()` (a hidden Android
API) and reconnects. If `refresh()` is unavailable, the UI shows a `GattCacheError` state.
This is opaque to driver authors but relevant when debugging a driver that fails to receive
notifications on the first connection attempt after a firmware update.

### K. `CONTRIBUTING.md` broken references

- Line 27: "Submit finished drivers to the [`/drivers`](/drivers) directory via pull request"
  — no `/drivers` directory exists in the repo root. Actual driver files live in
  `Driver Builds/`. A contributor following this instruction will not know where to put their
  driver.
- Line 44: "See the architecture overview in [`/docs/architecture.md`](/docs/architecture.md)
  , to be written" — this file does not exist. Acknowledged as to-be-written but still a gap
  for app contributors.

---

## Implementation Gaps — Code aspects that may need attention regardless of documentation

### 1. `example_simple.json` is broken and cannot be loaded (critical)

`app/src/main/assets/drivers/example_simple.json` uses `"parsing": {"mode": "JSON",
"rules": [...]}`. `ParsingConfig` is a sealed class with one concrete subtype `WasmParsing`
(`@SerialName("WASM")`). The kotlinx.serialization discriminator for `"JSON"` has no
registered subtype, so deserialization always fails with a serialization exception.
`DriverStorage.saveDriver()` returns `DriverSaveResult.Error("Invalid JSON: ...")` for this
file. It can never be loaded by the app.

This file is a legacy artifact from before the WASM-only refactor. It contradicts the guide
(which correctly says only WASM is supported) and will mislead any developer who reads the
assets directory. It should be removed or replaced with a valid WASM example. `example_wasm
.json` (`polar_h10_wasm_v1`) is already a valid WASM example and can stand alone.

### 2. `BleCommand.kt` is dead code

`app/src/main/java/.../ble/BleCommand.kt` defines a sealed class `BleCommand` (Write,
EnableNotify, Delay) that is not imported or used anywhere in the codebase. The actual sync
command type used throughout the engine is `SyncCommand.kt`. `BleCommand` is a leftover from
a prior architecture and can be deleted.

### 3. `DeviceDriver` / `MetricProcessor` are effectively dead code

`WasmDriverManifest` does not implement `DeviceDriver`. The cast `(activeManifest as?
DeviceDriver)?.createProcessor()` in `BleEngine.connect()` always returns null. No other
class in the codebase implements `DeviceDriver`. `MetricProcessor` is never instantiated.
Both interfaces exist as scaffolding for a native Kotlin driver path that is not yet active.

### 4. Reprocess path does not route to dedicated typed tables (critical)

`DeviceSyncProcessor.processFromRaw()` re-parses all raw packets and then calls:
```kotlin
val rs = metricRepository.insertAllFromDevice(acceptedReadings)
```

`metricRepository` is `MetricReadingStagingRepository`. This means all accepted readings —
including HR, HRV, SPO2, RESPIRATION, SKIN_TEMP, STEPS, ACTIVE_CALORIES, TOTAL_CALORIES,
BLOOD_PRESSURE, and GLUCOSE — are inserted into `metric_readings_staging` during
reprocessing, not into their dedicated typed tables.

During a live sync, `BleEngine.routeReading()` sends these types directly to `hr_readings`,
`hrv_readings`, etc. The reprocess path does not replicate this logic. After reprocessing,
dedicated table rows will not be updated; the staging table will receive duplicates of
readings that should have gone elsewhere.

The guide's statement "Reprocess can fix wrong `value` fields" is inaccurate for the 10
dedicated-table metric types. Only metrics that normally route to staging (RHR, BODY_TEMP,
TEMP_DEVIATION, VO2_MAX, DISTANCE, ELEVATION_GAIN, ELEVATION_LOSS, CALORIES, BASAL_CALORIES)
will be correctly reprocessed. HR, HRV, SPO2, etc. will not have their dedicated-table rows
updated by Reprocess.

### 5. `BATTERY` leaks into staging via `routeReading()`

As noted in Missing section G, `BATTERY` has no dedicated case in `routeReading()`, falling
to `else -> stagingRepository.insert(reading)`. BATTERY rows accumulate in
`metric_readings_staging` over time despite the guide stating BATTERY is not stored there.

### 6. `SleepStagePromoter` metaJson contract is entirely implicit

For the `SLEEP_STAGE` MetricType path (emitted via `parseMetrics`), `SleepStagePromoter`
reads stage data using:
```kotlin
val json = JSONObject(row.metaJson ?: "{}")
SleepStage.valueOf(json.getString("stage"))
json.getLong("start_ms")
json.getLong("end_ms")
```

The key names (`stage`, `start_ms`, `end_ms`) and the `SleepStage` enum values
(`DEEP`, `LIGHT`, `REM`, `AWAKE`) are the complete contract for this path. They are defined
nowhere in the guide or in any documentation visible to a driver author.

---

## Recommendations — Priority order for updating the guide

Ordered by what a driver author encounters first and what causes silent failures vs. visible
errors.

### Priority 1 — Causes silent data loss immediately

**1. Rename `RESPIRATORY_RATE` to `RESPIRATION`** in the Supported Metric Types section and
anywhere else it appears in the guide. Any driver following the guide and emitting
`RESPIRATORY_RATE` will have every respiration reading silently rejected. This is the
highest-priority fix.

**2. Add `TOTAL_CALORIES`, `BLOOD_PRESSURE`, `GLUCOSE`** to the Supported Metric Types table
with correct descriptions. Add a dedicated note for `BLOOD_PRESSURE`: `value` must be
systolic (mmHg), and `metaJson` must contain `{"diastolic": <int>}` — if diastolic is absent
or unparseable, the reading silently goes to staging instead of `blood_pressure_readings`.

**3. Replace or remove `example_simple.json`** from `app/src/main/assets/drivers/`. The file
uses an unsupported `mode: "JSON"` and cannot be loaded. Replace it with a minimal valid WASM
example or delete it — `example_wasm.json` already serves as a WASM example.

### Priority 2 — Causes confusion when debugging or reading the code

**4. Update the Sync Processing section** to replace the reference to `metric_readings` with
the correct description. Add a routing table showing which MetricType goes to which table (or
to staging). Clarify that dedicated-table types are routed immediately by the BLE engine, not
through `DeviceSyncProcessor`.

**5. Document the Reprocess limitation** for dedicated-table types. The current guide implies
Reprocess fixes all wrong-value bugs. In practice, Reprocess only updates staging-routed
types. HR, HRV, SPO2, RESPIRATION, SKIN_TEMP, STEPS, ACTIVE_CALORIES, TOTAL_CALORIES,
BLOOD_PRESSURE, and GLUCOSE readings are not updated in their dedicated tables by Reprocess.

**6. Fix the `/drivers` link in `CONTRIBUTING.md`** to point to the actual directory where
driver files should be submitted. Either create a repo-root `drivers/` directory as the
canonical contribution location, or update the text to point to `Driver Builds/`.

### Priority 3 — Completeness for non-obvious behaviours

**7. Clarify BATTERY routing**: note that BATTERY lands in `metric_readings_staging` as a
side effect of the current `routeReading()` implementation, in addition to updating device
metadata. Driver authors do not need to do anything differently, but the current description
("BATTERY is not stored in metric_readings") does not fully describe runtime behaviour.

**8. Document the `SLEEP_STAGE` MetricType path** as an advanced alternative to `parseSleep`
for devices that stream sleep stages as individual BLE notifications. Specify the required
`metaJson` format: `{"stage": "DEEP|LIGHT|REM|AWAKE", "start_ms": <ms>, "end_ms": <ms>}`.
Note that `SleepStagePromoter` runs at quiescence to promote these rows into `sleep_stages`.

**9. Add a note about `computed_by_version`**: HRV and sleep stage rows carry a
`computedByVersion` integer (currently hardcoded to `1`) used to identify rows produced by a
given algorithm version. WASM drivers cannot control this value.

**10. Document or retire `DeviceDriver`/`MetricProcessor`**: if native Kotlin driver support
is intended as a public contribution path, document the interface contract. If it is an
internal-only extension point, add a note to the guide explicitly stating that the WASM
manifest path is the only supported external driver format.

---

*Audit performed 2026-06-16. No files were modified.*
