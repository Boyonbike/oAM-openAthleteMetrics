# BLE Device Driver Authoring Guide

This document contains everything needed to write a device driver for the app —
either as a human developer or as an AI given a device protocol document alongside
this guide.

A driver is a single `.json` file that tells the app how to identify, connect to,
sync from, and parse data from a BLE wearable device. The app loads driver files
at runtime from device storage — no code changes or app updates are required to
add support for a new device.

---

## How the App Uses a Driver

Understanding the full pipeline helps you write a correct driver.

### 1. Loading

The user selects a `.json` file (or folder of files) via the Devices screen. The app:

- Parses the file as a `WasmDriverManifest`
- Runs the manifest validator (checks required fields, semver, WASM magic header)
- Copies the file to internal storage so it survives if the user deletes the original
- Registers the manifest in `DriverRegistry`

A driver that fails validation is rejected with a list of error messages shown to
the user. It is never partially loaded.

### 2. Device Matching (Scanning)

When the user taps **Add Device**, the app starts a BLE scan. For each device found,
it calls `DriverRegistry.resolve(deviceName, serviceUuids)` which checks every loaded
driver's `ble.matchByName` and `ble.matchByServiceUuid` fields against the scan result.

The best match wins — `CERTAIN` before `PROBABLE`. If no driver matches, scanning
continues until the 30-second timeout.

### 3. Connection and Sync Setup

After matching, the app:

1. Connects to the device via GATT
2. Requests MTU 512
3. Discovers services
4. Enables BLE notifications on every characteristic listed in `ble.characteristics`
5. Executes `syncCommands` in order — writes, notification enables, and delays

After the sync command sequence completes, the device is in **Connected** state.
Data begins flowing via BLE notifications. The app accumulates all incoming packets
until the user taps **Sync**.

### 4. Parsing

Every incoming BLE notification is routed through the driver's parsing logic.

- **JSON mode** — the app's `JsonDriverEngine` walks the `parsing.rules` array,
  finds rules matching the characteristic and packet identifier byte, and extracts
  field values using byte offsets and scaling.

- **WASM mode** — the app's `WasmDriverEngine` calls the exported WASM functions
  with the raw bytes. The WASM module writes JSON output into shared linear memory.
  The app reads and deserialises that JSON into the standard model objects.

Parsing errors never crash the app or abort a sync. A bad packet is logged and
skipped. The rest of the sync continues.

### 5. Sync Processing

When the user taps **Sync**, the app passes all accumulated data to
`DeviceSyncProcessor.process()`, which:

1. Records a `SyncSession` row in the database
2. Validates all readings, sleep sessions, and activities
3. Inserts valid data into `metric_readings`, `sleep_sessions`, and `activities`
4. Stores raw BLE payloads in `raw_device_data` for future reprocessing
5. Re-runs `DailySummaryWorker` to update the Dashboard
6. Updates the device's `last_sync_ms` timestamp

Validation rules applied to every metric reading:
- Value must not be NaN or Infinite
- Timestamp must be after 2020-01-01 and not more than 1 hour in the future
- Unit must not be blank
- Metric type must be a known value

### 6. Display

Parsed data appears on the Dashboard and History screens. The app never interprets
or scores data — it displays exactly what the driver produced.

---

## The Driver File Format

A driver file is a UTF-8 encoded `.json` file. Every field is described below.

### Top-Level Fields

```json
{
  "id": "example_device_v1",
  "displayName": "Example Device",
  "version": "1.0.0",
  "author": "your-name",
  "supportedMetrics": ["HR", "HRV", "SPO2", "STEPS", "BATTERY"],
  "ble": { ... },
  "syncCommands": [ ... ],
  "parsing": { ... }
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `id` | string | yes | Stable unique identifier. Stored in the database — never change it after release. Use lowercase and underscores. |
| `displayName` | string | yes | Human-readable name shown in the Devices screen. |
| `version` | string | yes | Semver string, e.g. `"1.0.0"`. |
| `author` | string | yes | Your name or handle. |
| `supportedMetrics` | string[] | yes | Array of metric type names this driver can produce. See supported values below. |
| `ble` | object | yes | BLE discovery and characteristic configuration. |
| `syncCommands` | array | no | Ordered list of commands to execute after connecting. Can be empty if data flows automatically. |
| `parsing` | object | yes | Parsing mode and rules. |

### Supported Metric Types

```
HR               Heart rate (bpm)
HRV              Heart rate variability (ms)
RHR              Resting heart rate (bpm)
SPO2             Blood oxygen saturation (%)
STEPS            Step count (steps)
SLEEP_STAGE      Sleep stage data
BATTERY          Device battery level (%)
RESPIRATORY_RATE Breaths per minute
SKIN_TEMP        Skin temperature (°C)
BODY_TEMP        Body temperature (°C)
TEMP_DEVIATION   Temperature deviation from baseline (°C)
VO2_MAX          VO2 max estimate (ml/kg/min)
DISTANCE         Distance covered (m)
ELEVATION_GAIN   Elevation gain (m)
ELEVATION_LOSS   Elevation loss (m)
CALORIES         Total calories (kcal)
ACTIVE_CALORIES  Active calories (kcal)
BASAL_CALORIES   Basal metabolic calories (kcal)
```

Do not include any proprietary manufacturer metrics (readiness scores, recovery
scores, sleep scores, body battery, strain scores, or similar). The app stores
raw sensor measurements only.

---

### The `ble` Block

```json
"ble": {
  "matchByName": "ExampleDevice",
  "matchByServiceUuid": "0000fee0-0000-1000-8000-00805f9b34fb",
  "matchConfidence": "CERTAIN",
  "services": [
    "0000fee0-0000-1000-8000-00805f9b34fb"
  ],
  "characteristics": {
    "notify": "0000fee1-0000-1000-8000-00805f9b34fb",
    "write":  "0000fee2-0000-1000-8000-00805f9b34fb"
  }
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `matchByName` | string | one of name/uuid required | Exact BLE advertised name or name prefix. |
| `matchByServiceUuid` | string | one of name/uuid required | Full 128-bit UUID string advertised during scan. |
| `matchConfidence` | string | yes | `"CERTAIN"` or `"PROBABLE"`. Use `CERTAIN` only when name + UUID together uniquely identify this device. Use `PROBABLE` if the name is a prefix or the UUID is shared with other devices from the same manufacturer. |
| `services` | string[] | yes | Service UUIDs to discover after connecting. Must have at least one. |
| `characteristics` | object | yes | Map of role names to characteristic UUIDs. Role names are driver-defined (e.g. `"notify"`, `"write"`). The app enables notifications on all characteristics listed here. |

UUIDs must be full 128-bit format: `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`.

---

### The `syncCommands` Array

The ordered sequence of BLE operations to execute after connecting and enabling
notifications. Three command types are available:

**WRITE** — write bytes to a characteristic:
```json
{
  "type": "WRITE",
  "characteristic": "write",
  "bytes": "0x01 0xAB 0x00"
}
```
`characteristic` is a role name from `ble.characteristics`.
`bytes` is a space-separated hex string.

**ENABLE_NOTIFY** — enable notifications on a characteristic:
```json
{
  "type": "ENABLE_NOTIFY",
  "characteristic": "notify"
}
```

**DELAY** — wait before the next command:
```json
{
  "type": "DELAY",
  "millis": 200
}
```

The app executes these in order. After the last command completes, the device
enters Connected state and the app begins accumulating notification data.

---

### The `parsing` Block — JSON Mode

Use JSON mode when **all** of the following are true:

- Every metric fits in a single BLE packet
- No encryption or obfuscation
- No stateful parsing (no rolling counters, delta encoding, or session tokens)
- Each field is at a fixed byte offset with simple optional scaling

```json
"parsing": {
  "mode": "JSON",
  "rules": [
    {
      "characteristic": "notify",
      "match": {
        "byte": 0,
        "value": "0x04"
      },
      "fields": [
        {
          "metric": "HR",
          "byteOffset": 1,
          "byteLength": 1,
          "signed": false,
          "unit": "bpm"
        },
        {
          "metric": "BATTERY",
          "byteOffset": 2,
          "byteLength": 1,
          "signed": false,
          "scale": 1.0,
          "unit": "%"
        }
      ]
    }
  ]
}
```

**Rule fields:**

| Field | Type | Required | Description |
|---|---|---|---|
| `characteristic` | string | yes | Role name from `ble.characteristics` that carries this packet. |
| `match.byte` | int | yes | Index into the payload to check for packet identification. |
| `match.value` | string | yes | Expected hex value at that byte index, e.g. `"0x04"`. |
| `fields` | array | yes | List of values to extract from a matching packet. |

**Field extraction fields:**

| Field | Type | Required | Description |
|---|---|---|---|
| `metric` | string | yes | MetricType name (see supported values above). |
| `byteOffset` | int | yes | Byte index of the value in the payload (0-based). |
| `byteLength` | int | yes | Number of bytes to read (1, 2, or 4). |
| `signed` | bool | yes | Whether to interpret the bytes as a signed integer. |
| `scale` | float | no | Multiply the raw integer by this value. Omit if no scaling needed. |
| `unit` | string | yes | Output unit string, e.g. `"bpm"`, `"ms"`, `"%"`. |

Multi-byte values are read little-endian.

---

### The `parsing` Block — WASM Mode

Use WASM mode when **any** of the following are true:

- Multi-packet assembly is required for any metric
- Encryption or obfuscation is present
- Stateful parsing is required (counters, deltas, session tokens)
- Logic more complex than a single byte match is needed

```json
"parsing": {
  "mode": "WASM",
  "wasmBase64": "AGFzbQEAAAA...",
  "exports": {
    "parseMetrics": "parse_metrics",
    "parseSleep": "parse_sleep",
    "parseActivity": "parse_activity"
  }
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `wasmBase64` | string | yes | The compiled `.wasm` binary encoded as Base64. |
| `exports.parseMetrics` | string | yes | Name of the exported WASM function that parses metric readings. |
| `exports.parseSleep` | string | no | Name of the exported WASM function that parses sleep sessions. Omit if device has no sleep data. |
| `exports.parseActivity` | string | no | Name of the exported WASM function that parses activities. Omit if device has no activity data. |

---

## Writing a WASM Driver Module

### Requirements

Your WASM module must:

- Export a `memory` with at least 1 page (65,536 bytes)
- Export the functions listed in `parsing.exports`
- Use the memory layout and JSON output schemas described below

You can write the module in any language that compiles to WASM: Rust, C, AssemblyScript,
Kotlin (via kotlin-wasm), or raw WAT. The compiled output must be a valid `.wasm` binary
encoded as Base64 for the manifest.

### Memory Layout

The app and your WASM module share a single linear memory region. Two fixed areas are used:

```
Offset 0x0000 (    0) — INPUT REGION  — max 4,096 bytes
  The app writes raw BLE characteristic bytes here before every call.

Offset 0x1000 (4,096) — OUTPUT REGION — max 61,440 bytes
  Your module writes UTF-8 JSON output here and returns the byte count.
```

These offsets are fixed. Do not change them.

### Function Signature

All three exported parse functions use the same signature:

```
(func (param i32 i32) (result i32))
  param 1 — memory offset of input bytes (always 0)
  param 2 — length of input bytes
  result  — byte length of JSON written at offset 4096
```

Return `0` to signal "no data for this packet". The app will not read the output region.

### Call Sequence (per notification)

1. App writes raw BLE bytes to memory offset `0`
2. App calls your function with `(0, byteLength)`
3. Your function parses the bytes, writes JSON at offset `4096`, returns byte count
4. App reads `byteCount` bytes from offset `4096` and deserialises the JSON
5. If return value is `0`, app skips reading entirely

### JSON Output Schemas

Your functions must write valid UTF-8 JSON at offset 4096 conforming to these schemas.
Unknown keys are ignored by the app — you can include extra fields for debugging.

**parseMetrics** — writes a JSON array:

```json
[
  {
    "metricType": "HR",
    "value": 72.0,
    "unit": "bpm",
    "recordedAtMs": 1234567890000,
    "confidence": null,
    "metaJson": null
  }
]
```

| Field | Type | Required | Description |
|---|---|---|---|
| `metricType` | string | yes | MetricType name. Must be a value from the supported list. |
| `value` | float | yes | The parsed sensor value. |
| `unit` | string | yes | Unit string matching the metric type. |
| `recordedAtMs` | int64 | yes | Unix epoch milliseconds when the sensor recorded this value. |
| `confidence` | float | no | Signal quality 0.0–1.0 if available. Null otherwise. |
| `metaJson` | string | no | Any device-specific extra data as a JSON string. Null otherwise. |

An empty array `[]` is valid and equivalent to returning `0`.

**parseSleep** — writes a single JSON object or nothing:

```json
{
  "dateIso": "2024-01-15",
  "sleepStartMs": 1705276800000,
  "sleepEndMs": 1705305600000,
  "durationMinutes": 480,
  "stagesJson": "[{\"stage\":\"DEEP\",\"startMs\":1705276800000,\"endMs\":1705283000000}]"
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `dateIso` | string | yes | ISO date of the morning the sleep ended, e.g. `"2024-01-15"`. |
| `sleepStartMs` | int64 | yes | Unix epoch ms when sleep began. |
| `sleepEndMs` | int64 | yes | Unix epoch ms when sleep ended. |
| `durationMinutes` | int | yes | Total sleep duration in minutes. |
| `stagesJson` | string | no | JSON array of stage objects: `[{"stage":"DEEP","startMs":...,"endMs":...}]`. Valid stage values: `DEEP`, `LIGHT`, `REM`, `AWAKE`. Null if device does not provide stage breakdown. |

Signal "no sleep data" by returning `0` or writing `{}`.

**parseActivity** — writes a single JSON object or nothing:

```json
{
  "startTimeMs": 1705276800000,
  "endTimeMs": 1705280400000,
  "durationMinutes": 60,
  "deviceName": "Outdoor Run",
  "avgHrBpm": 145.0,
  "maxHrBpm": 178.0,
  "minHrBpm": 120.0,
  "calories": 650.0,
  "activeCalories": 600.0,
  "distanceMeters": 10000.0,
  "steps": 8500,
  "hrZonesJson": null
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `startTimeMs` | int64 | yes | Unix epoch ms when the activity began. |
| `endTimeMs` | int64 | yes | Unix epoch ms when the activity ended. |
| `durationMinutes` | int | yes | Duration in whole minutes. |
| `deviceName` | string | yes | Raw activity label from the device, e.g. `"Outdoor Run"`. The user classifies this after import. |
| `avgHrBpm` | float | no | Average heart rate in bpm. Null if not available. |
| `maxHrBpm` | float | no | Peak heart rate in bpm. Null if not available. |
| `minHrBpm` | float | no | Lowest heart rate in bpm. Null if not available. |
| `calories` | float | no | Total energy expenditure in kcal. Null if not available. |
| `activeCalories` | float | no | Active (non-resting) energy in kcal. Null if not available. |
| `distanceMeters` | float | no | Distance in metres. Null if not available. |
| `steps` | int | no | Steps during this activity. Null if not available. |
| `hrZonesJson` | string | no | HR zone breakdown: `[{"zone":1,"seconds":120},...]`. Null if not available. |

Signal "no activity data" by returning `0` or writing `{}`.

---

## Complete Examples

### JSON Mode Driver (simple heart rate band)

```json
{
  "id": "example_hr_band_v1",
  "displayName": "Example HR Band",
  "version": "1.0.0",
  "author": "example-author",
  "supportedMetrics": ["HR", "BATTERY"],
  "ble": {
    "matchByName": "ExampleHR",
    "matchByServiceUuid": "0000fee0-0000-1000-8000-00805f9b34fb",
    "matchConfidence": "CERTAIN",
    "services": ["0000fee0-0000-1000-8000-00805f9b34fb"],
    "characteristics": {
      "notify": "0000fee1-0000-1000-8000-00805f9b34fb",
      "write":  "0000fee2-0000-1000-8000-00805f9b34fb"
    }
  },
  "syncCommands": [
    { "type": "WRITE", "characteristic": "write", "bytes": "0x01 0xAB 0x00" },
    { "type": "DELAY", "millis": 200 }
  ],
  "parsing": {
    "mode": "JSON",
    "rules": [
      {
        "characteristic": "notify",
        "match": { "byte": 0, "value": "0x04" },
        "fields": [
          { "metric": "HR",      "byteOffset": 1, "byteLength": 1, "signed": false, "unit": "bpm" },
          { "metric": "BATTERY", "byteOffset": 2, "byteLength": 1, "signed": false, "unit": "%" }
        ]
      }
    ]
  }
}
```

### WASM Mode Driver (complex device with multi-packet parsing)

```json
{
  "id": "example_complex_v1",
  "displayName": "Example Complex Device",
  "version": "1.0.0",
  "author": "example-author",
  "supportedMetrics": ["HR", "HRV", "SPO2", "STEPS", "SLEEP_STAGE", "BATTERY"],
  "ble": {
    "matchByName": "ComplexDevice",
    "matchByServiceUuid": "0000abcd-0000-1000-8000-00805f9b34fb",
    "matchConfidence": "CERTAIN",
    "services": ["0000abcd-0000-1000-8000-00805f9b34fb"],
    "characteristics": {
      "notify": "0000abce-0000-1000-8000-00805f9b34fb",
      "write":  "0000abcf-0000-1000-8000-00805f9b34fb"
    }
  },
  "syncCommands": [
    { "type": "WRITE", "characteristic": "write", "bytes": "0xAA 0x01" },
    { "type": "DELAY", "millis": 500 },
    { "type": "WRITE", "characteristic": "write", "bytes": "0xAA 0x02" }
  ],
  "parsing": {
    "mode": "WASM",
    "wasmBase64": "AGFzbQEAAAA...",
    "exports": {
      "parseMetrics":  "parse_metrics",
      "parseSleep":    "parse_sleep",
      "parseActivity": "parse_activity"
    }
  }
}
```

---

## Validation Rules

The app validates every manifest before loading it. A driver that fails any check
is rejected entirely with an error message shown to the user.

| Rule | Detail |
|---|---|
| `id` not blank | Must be a non-empty string |
| `version` is semver | Must match `X.Y.Z` where X, Y, Z are integers |
| `supportedMetrics` not empty | Must contain at least one valid MetricType value |
| `ble.services` not empty | Must list at least one service UUID |
| At least one match field | `matchByName` or `matchByServiceUuid` must be present |
| JSON mode: `rules` not empty | Must have at least one parse rule |
| WASM mode: valid magic header | First 4 bytes of the decoded WASM binary must be `0x00 0x61 0x73 0x6D` |
| WASM mode: `exports.parseMetrics` not blank | The metrics export name must be specified |

---

## Checklist Before Submitting a Driver

- [ ] `id` is unique and will not change in future versions
- [ ] `version` follows semver (`X.Y.Z`)
- [ ] All UUIDs are full 128-bit format
- [ ] `matchByName` matches the exact advertised device name (check with a BLE scanner app)
- [ ] `matchConfidence` is `CERTAIN` only if name + UUID uniquely identify this device
- [ ] All `syncCommands` use role names that exist in `ble.characteristics`
- [ ] No proprietary score metrics included in `supportedMetrics`
- [ ] JSON mode: every rule has a `match` condition that uniquely identifies the packet type
- [ ] WASM mode: binary starts with `0x00 0x61 0x73 0x6D` (valid WASM magic header)
- [ ] WASM mode: `parse_metrics` (or your chosen export name) is exported from the module
- [ ] WASM mode: output JSON conforms to the schemas in this document
- [ ] Driver file loads without validation errors in the app
- [ ] At least one metric appears on the Dashboard after syncing

---

*Open Athlete Metrics — Driver Authoring Guide*
