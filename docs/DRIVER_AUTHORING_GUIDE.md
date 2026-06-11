# BLE Device Driver Authoring Guide

> All drivers use WASM mode for parsing. A driver is a single `.json` file
> containing device configuration and an embedded WASM binary.

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

The app's `WasmDriverEngine` calls the exported WASM functions with the raw bytes.
The WASM module writes JSON output into shared linear memory. The app reads and
deserialises that JSON into the standard model objects.

Parsing errors never crash the app or abort a sync. A bad packet is logged and
skipped. The rest of the sync continues.

> **Important — WASM state after errors:** If a Chicory trap occurs during parsing,
> the WASM module is re-instantiated and all linear memory is reset. Any
> cross-packet accumulation state (e.g. a daily steps accumulator built across
> multiple packets) will be lost silently. Design your parser to be stateless where
> possible. If you must accumulate across packets, write state to WASM linear memory
> in a way that can be safely abandoned mid-sync — partial accumulations should
> produce no output rather than a corrupt partial output.

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

The app uses **insert-or-ignore** for point-in-time metrics and sleep sessions, and
**insert-or-replace with a value guard** for accumulator metrics (STEPS, CALORIES,
DISTANCE, and other daily totals). This distinction matters:

- Point-in-time: if a record with the same deduplication key already exists, the
  incoming record is silently skipped
- Accumulators: if a record with the same deduplication key already exists, it is
  replaced **only if the incoming value is greater than or equal to the stored value**
  — this allows a final day total to overwrite a partial, while protecting against a
  corrupt re-sync that produces a lower value (e.g. 0 steps) overwriting a correct
  record. A re-sync that produces a lower accumulator value than what is stored is
  treated as suspect and skipped, with a warning logged.

Drivers should return all available data on every sync without tracking what has
already been sent. Re-syncing the same data is safe and has no side effects.
Historical data and current data are treated identically — send everything, the
app handles deduplication.

## Deduplication

The app deduplicates incoming data using these keys:

| Data type       | Deduplication key                                   |
|-----------------|-----------------------------------------------------|
| Metric readings (point-in-time) | `driver_id` + `metric_type` + `recorded_at` (ms) |
| Metric readings (accumulator)   | `driver_id` + `metric_type` + `recorded_at` (ms) — uses insert-or-replace |
| Sleep sessions  | `driver_id` + `date` (ISO date of morning after)   |
| Activities      | `driver_id` + `start_time` (ms)                    |

Two records are considered duplicates if all key fields match. A duplicate
point-in-time record is silently skipped. A duplicate accumulator record replaces
the existing one.

The `driver_id` used for deduplication is the `id` field from the manifest. Data
from two drivers with different ids will never conflict even if their timestamps
overlap.

> **Multiple physical devices:** If the user owns or replaces a device, both units
> will share the same `driver_id`. This means readings from two physical units at
> the same millisecond will collide on the deduplication key. This is an expected
> limitation of the current system. If your device exposes a hardware serial number,
> include it in the `driver_id` field (e.g. `hume_band_v1_SN12345`) to make the key
> unique per unit.

### 6. Display

Parsed data appears on the Dashboard and History screens. The app never interprets
or scores data — it displays exactly what the driver produced.

---

## Driver Correctness

**Read this section in full before writing any parse logic.**

This section covers the decisions every driver author must get right before writing
parse logic. The structural contract — memory layout, JSON schemas, deduplication
keys — is covered elsewhere in this guide. This section covers the *reasoning*
behind correct timestamps, date attribution, and data classification. Getting these
wrong produces silent data corruption that is difficult to diagnose after the fact.

### The Single Rule: UTC Epoch Milliseconds, Always

Every timestamp emitted by a driver — `recordedAtMs`, `sleepStartMs`, `sleepEndMs`,
`startTimeMs`, `endTimeMs` — must be **UTC epoch milliseconds**.

The app never compensates for timezone offsets. It trusts what the driver gives it.
If your device sends timestamps in local time, your driver is responsible for
converting to UTC before writing to the output JSON. If your device sends relative
offsets, your driver is responsible for reconstructing absolute UTC. No timezone
handling happens anywhere else in the pipeline.

A UTC epoch millisecond value for a real-world timestamp is always a 13-digit
integer (e.g. `1749571200000`). If your value is 10 digits, you have epoch seconds
not milliseconds — multiply by 1000. If your value is negative or implausibly small,
your reconstruction logic has a bug.

> **DST warning:** Do not use today's UTC offset to reconstruct timestamps for
> historical records. If Daylight Saving Time changed between the recording date and
> the sync date, today's offset will be wrong for historical records. Use the UTC
> offset that was in effect at the time of recording, or — better — work only in UTC
> throughout and never apply local timezone offsets at all.

### Sourcing Timestamps: A Decision Tree

Devices vary in what they send. Work through this tree for every metric type your
driver handles.

**Does the device send absolute timestamps?**

- **Yes, and they are UTC** → use directly as `recordedAtMs`. This is the most
  reliable case. Document in your driver that timestamps are device-native UTC.

- **Yes, but they are local time** → convert to UTC before emitting. The UTC offset
  must come from a reliable source — either the device itself (if it exposes a
  timezone setting), or established from the device's region settings. Do not
  hardcode an offset. Do not use today's UTC offset for historical records (see DST
  warning above). Document the conversion in your driver.

- **Yes, but the epoch is non-standard** → some devices use a custom epoch (e.g.
  seconds since January 1 2000). Convert to Unix epoch before emitting. Document the
  epoch origin in your driver.

**Does the device send relative offsets?**

Relative offsets are values like "3600 seconds ago" or "4 hours into the day" that
must be resolved against a reference point to produce an absolute timestamp.

- **Relative to sync time** → reconstruct as `recordedAtMs = syncTimeMs - offsetMs`
  where `syncTimeMs` is `System.currentTimeMillis()` captured once at the start of
  the sync, before any packets are processed. Use this single captured value for the
  entire sync — do not call `currentTimeMillis()` per packet, as clock skew across a
  multi-minute sync will corrupt relative timestamps. Document in your driver that
  timestamps are reconstructed from sync time and are therefore approximate.
  Historical records reconstructed this way are less reliable than device-native
  timestamps.

- **Relative to a day boundary** → some devices send offsets within a named calendar
  day (e.g. "day index 3, offset 14400 seconds"). Reconstruct as `recordedAtMs =
  utcMidnightOfDayMs + offsetMs` where `utcMidnightOfDayMs` is midnight UTC of the
  named calendar day. If the device names days by index from a known epoch, compute
  the UTC midnight of that day exactly — do not approximate. Document the
  day-numbering scheme.

**Does the device send no timestamp at all?**

Use `System.currentTimeMillis()` as a last resort. This is only acceptable for
truly instantaneous readings where the timestamp is genuinely "now" (e.g. a live HR
reading triggered by a sync command). It is never acceptable for historical records —
a historical record with no timestamp cannot be reliably attributed to the correct
date and should be discarded rather than incorrectly dated. Document that the driver
uses sync time for this metric.

### Historical Data: Never Use Sync Time as recordedAtMs

When a device replays previous days during a sync, those historical records must
carry their original timestamps — not the current sync time.

This is the most common driver bug and produces the most visible corruption: all
historical data appears under today's date regardless of when it was actually
recorded.

The rule is simple: `created_at` is always the current time (when the row was
inserted). `recorded_at` is always when the sensor recorded the value. These are
two different fields for this reason. Never conflate them.

If a historical packet contains an original timestamp, use it for `recordedAtMs`
regardless of when you are processing it. The app's deduplication means re-sending
old records on every sync is safe — records that already exist are skipped or
replaced as appropriate.

### Device Clock Validation

Not all devices maintain a reliable clock. A device that has had its battery pulled,
or has never been time-synced, may send timestamps that are plausible (after
2020-01-01) but wrong by weeks or months.

The app's validator will reject timestamps before 2020-01-01 and more than 1 hour in
the future. But a device that thinks it's January 2020 (just had its RTC cleared)
will pass the floor check and insert readings on the wrong date.

If your device exposes a clock-sync command, issue it during `syncCommands` before
requesting data. If it does not, include the caveat in your driver's documentation:
readings may be misattributed if the device clock is wrong.

### Date Attribution Rules

Given a correct UTC `recordedAtMs`, the calendar date a reading belongs to is
determined as follows.

**Point-in-time metrics** (HR, HRV, RHR, SPO2, BATTERY, RESPIRATORY_RATE,
SKIN_TEMP, BODY_TEMP, TEMP_DEVIATION, VO2_MAX)

The calendar date is the UTC date of `recordedAtMs`. A reading at
`2026-06-09 23:45:00 UTC` belongs to June 9. A reading at `2026-06-10 00:15:00 UTC`
belongs to June 10. No special handling needed — emit the exact device timestamp and
let the app derive the date from it.

**Accumulator metrics** (STEPS, CALORIES, ACTIVE_CALORIES, BASAL_CALORIES,
DISTANCE, ELEVATION_GAIN, ELEVATION_LOSS)

Accumulator metrics represent totals that build up over a calendar day rather than
point-in-time samples. They require special timestamp handling.

Emit **one reading per calendar day**, not one reading per packet. If the device
sends hourly or interval buckets (e.g. steps per 15-minute window), your driver must
sum them into a single daily total before emitting. Do not emit each interval as a
separate MetricReading.

Set `recordedAtMs` to **UTC midnight of the accumulation day**. For example, a daily
step total for June 9 should have `recordedAtMs = 1749427200000`
(2026-06-09 00:00:00 UTC). This makes date attribution unambiguous and ensures
deduplication works correctly across syncs.

If the device sends a partial day total mid-sync (e.g. steps so far today), still
emit it with `recordedAtMs` set to UTC midnight of today. If the user syncs again
later that day, the updated total will replace the partial via insert-or-replace.

**Sleep sessions**

Sleep sessions span a date boundary — a person falls asleep on one calendar day and
wakes on the next.

`dateIso` must always be the **local calendar date the user woke up** — the morning
the sleep ended in the device's local time, not the night it began. A session where
the user wakes at 07:00 local time on June 10 must have `dateIso = "2026-06-10"`
regardless of what that maps to in UTC.

This is the morning-after convention in local time. It matches how the user
experiences their data — looking at June 10 should show the sleep that preceded that
day. Deriving `dateIso` from the UTC date of `sleepEndMs` produces the wrong date for
users in UTC+ timezones (where waking at 07:00 local may still be the previous UTC
day) and for users in UTC− timezones in the early morning hours.

There are two valid paths to a correct `dateIso`:

**Path 1 — Device reports local wake time directly (preferred).** Most wearables
report sleep wake time in device-local time. If the device gives you a local date or
local datetime for the wake event, use the date portion directly as `dateIso`. This
is the most reliable approach and requires no UTC offset calculation.

**Path 2 — Device reports wake time as UTC only.** If the device only gives you
`sleepEndMs` as UTC, you need the user's UTC offset to derive the correct local date.
Read `utcOffsetMinutes` from the metadata region at bytes 8–9 (see Memory Layout).
The app writes the device's current UTC offset there before every call. Convert:
`localSleepEndMs = sleepEndMs + (utcOffsetMinutes * 60_000)` then extract the date
from `localSleepEndMs` as the local calendar date.

> **DST caveat for Path 2:** `utcOffsetMinutes` in the metadata is the offset at sync
> time. For historical sleep sessions recorded when a different DST offset was in
> effect, this value may be off by one hour. This can push `dateIso` to the wrong day
> in edge cases (e.g. a historical sleep ending at 00:30 local during a DST
> transition). This is an inherent limitation when the device does not report local
> time directly. Document the limitation in your driver.

`sleepStartMs` and `sleepEndMs` themselves remain UTC epoch milliseconds — only
`dateIso` is derived in local time. Do not convert the timestamps.

`sleepStartMs` and `sleepEndMs` must cover the full session from first sleep onset
to final wake. Do not trim AWAKE periods from the ends — they are part of the
session span. `durationMinutes` is ignored by the engine and can be omitted or set
to 0. Duration is always recomputed from `(sleepEndMs − sleepStartMs) / 60000`.

**In-progress sleep sessions**

If the user syncs while asleep (uncommon but possible), the device may report an
active session with no valid end time — returning 0, a placeholder, or the current
time. Do not emit a sleep session if `sleepEndMs` is 0, equal to `sleepStartMs`, or
earlier than `sleepStartMs`. Signal no data by returning 0 from `parseSleep`. The
correct session will be emitted on the next morning sync, and the deduplication key
(`driverId + dateIso`) will ensure it inserts cleanly.

**Activities**

The date an activity belongs to is the UTC date of `startTimeMs`. Use the exact
device start timestamp. The app derives the date from this field — there is no
separate date field in the activity schema.

### Metric Classification: What to Emit and What to Discard

Only emit MetricReadings for types listed in your driver's `supportedMetrics` array,
and only for types that exist in the supported metric type list in this guide.

If a BLE packet contains fields your driver does not recognise, or fields that map
to metric types not in the supported list, **discard them silently**. Do not emit a
MetricReading with an unrecognised `metricType` string.

Common examples of fields to discard:

- Proprietary scores (readiness, recovery, body battery, strain, sleep score)
- Manufacturer-specific derived metrics with no standard definition
- Redundant fields that duplicate data already captured
- Intermediate accumulator values when you are emitting a daily total

If your device sends a field you believe should be added to the supported metric
type list, raise it as a proposal rather than emitting it as an unrecognised type.

### Steps and Activity Steps: Avoiding Double-Counting

Activities have a `steps` field for steps taken during that specific activity. The
STEPS metric covers total daily steps including all activity and non-activity
periods.

Emit both if your device provides both — they serve different purposes. Do not
subtract activity steps from the daily total before emitting. The app stores them
independently and does not sum them.

If your device only provides per-activity step counts and no daily total, sum all
activity step counts for the day and emit that as the STEPS metric. Note this in
your driver — it means rest-period steps are not captured.

### Output Region Size

The output region is capped at 61,440 bytes. Your WASM module must never write
beyond offset `0x1000 + 61440`. For most packets this is not a concern, but a large
historical sync response (many days of sleep stage data, or a full activity history)
could approach the limit if you emit everything in a single JSON array.

If you risk exceeding the limit, split your output across multiple parse calls by
emitting one record per call rather than batching. The app calls your parse function
once per BLE notification — emit only what that notification contains.

### Driver Versioning and Correcting Historical Records

The deduplication key for metric readings is `driver_id + metric_type + recorded_at`.
It does not include `driver_version`. This has an important consequence: **if you
release a new driver version that fixes a timestamp or parsing bug, re-syncing will
not overwrite the wrong records**. The corrected records share the same deduplication
key as the originals and are silently skipped.

The app provides a "Reprocess from raw data" action in the Devices screen that
re-runs all stored raw BLE payloads through the current driver using insert-or-replace.
This is the correct path for fixing historical records after a driver update. Raw data
is retained for 7 days — corrections beyond that window require the device to resync
naturally as new days accumulate.

**What Reprocess can and cannot fix:**

- **Can fix:** wrong `value` fields (e.g. unit conversion bug, calibration error).
  Reprocess re-runs the WASM and uses insert-or-replace, so the corrected value
  overwrites the wrong one.
- **Can fix:** accumulator totals (STEPS, CALORIES, DISTANCE). Insert-or-replace
  means the corrected daily total replaces the stored one.
- **Cannot fix:** wrong `recordedAtMs` in point-in-time metrics. If the timestamp
  was wrong, the corrected record has a different deduplication key — it inserts as a
  new record while the old wrong record remains in the database. The user ends up with
  both. The only resolution is a full database reset or a manual delete of the
  affected records. Document clearly in your driver changelog when a fix changes
  timestamps, and advise users to reset if the duplicate records are unacceptable.

Implications for driver authors:

- If you fix a bug that changes `value` fields but not timestamps (e.g. wrong unit
  conversion), Reprocess will correctly overwrite the old values.
- If you fix a bug that changes `recordedAtMs` values, Reprocess cannot clean up the
  old wrong records. Document this explicitly in your changelog.
- Never change the `id` field to force a re-import. Changing `id` creates a new
  deduplication namespace — old records remain in the database under the old id, and
  new records are inserted under the new id, producing duplicates visible in the UI.

**Value guard and downward corrections:**

The app's accumulator value guard (see Sync Processing) only replaces a stored
accumulator record when the incoming value is greater than or equal to the stored
value. This protects against corrupt re-syncs that emit 0. However, it also prevents
legitimate downward corrections — for example, a firmware update that recalculates
calorie totals with a corrected algorithm, or a distance reading reduced after GPS
noise removal. If your driver update produces lower accumulator values for historical
records, Reprocess is the only path to apply those corrections, since Reprocess uses
unconditional insert-or-replace without the value guard.

### Stateless Parsing is Strongly Preferred

The WASM engine re-instantiates the module after any Chicory trap, resetting all
linear memory. If your parser accumulates state across multiple packets (e.g. building
a daily steps total across many interval packets), that state is lost silently after
a trap and the sync continues with an empty accumulator.

Design your parser to be stateless where possible — each packet should produce a
complete, self-contained output or return 0. If you must accumulate across packets
(e.g. the device sends step intervals across many notifications), write your
accumulator into WASM linear memory in a region that produces no output when lost
mid-sync, rather than a partial corrupt output. A sync that produces fewer records
than expected is preferable to one that produces wrong records.

### Use i64 for All Timestamp Variables — Without Exception

Unix epoch milliseconds (~1.75 trillion for current dates) exceeds the maximum value
of a 32-bit signed integer (~2.1 billion). If you use `i32` for any timestamp
variable, the value overflows silently. The result is a timestamp that may still pass
the 2020-01-01 validation floor — because the wrapped value can fall anywhere — but
is completely wrong. The record is stored with an incorrect date and the corruption
is undetectable after the fact.

This is one of the most common WASM driver bugs. The default integer type in C,
AssemblyScript, and many other WASM-targeting languages is 32-bit. You must
explicitly declare timestamp variables as 64-bit:

- **C / Clang**: `int64_t` or `uint64_t` (from `<stdint.h>`). Never use `int`,
  `long`, or `unsigned long` for timestamps.
- **AssemblyScript**: `i64` or `u64`. Never use `i32` for timestamps.
- **Rust**: `i64` or `u64`. `i32` will not compile for epoch ms values, but check
  intermediate arithmetic carefully — a multiplication of two `i32` values before
  casting to `i64` still overflows.
- **WAT (raw WebAssembly text)**: use `i64.const`, `i64.mul`, `i64.add` throughout
  any timestamp computation.

Check every arithmetic operation involving timestamps: addition, subtraction,
multiplication, division, and bit shifts. A single i32 intermediate in a chain of
i64 operations is enough to corrupt the result.

### The Input Region Is Zeroed Beyond the Current Packet

The app writes the current packet starting at offset 16 and zeroes any bytes beyond
`16 + byteLength` that were written by the previous packet. Reads past `byteLength`
return `0x00` — not stale data from a previous packet.

Your parser should still treat the input as a slice of exactly `byteLength` bytes
starting at offset 16 (passed as param 1). Do not rely on zero-padding for packet
framing — use `byteLength` as the authoritative packet boundary.

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
STEPS            Step count (steps) — daily total only, one per day
BATTERY          Device battery level (%)
RESPIRATORY_RATE Breaths per minute
SKIN_TEMP        Skin temperature (°C)
BODY_TEMP        Body temperature (°C)
TEMP_DEVIATION   Temperature deviation from baseline (°C)
VO2_MAX          VO2 max estimate (ml/kg/min)
DISTANCE         Distance covered (m) — daily total only, one per day
ELEVATION_GAIN   Elevation gain (m) — daily total only, one per day
ELEVATION_LOSS   Elevation loss (m) — daily total only, one per day
CALORIES         Total calories (kcal) — daily total only, one per day
ACTIVE_CALORIES  Active calories (kcal) — daily total only, one per day
BASAL_CALORIES   Basal metabolic calories (kcal) — daily total only, one per day
```

> **Sleep capability is not declared via `supportedMetrics`.** Sleep stage data lives
> inside `SleepSession.stagesJson` — it is not emitted as a MetricReading and
> `SLEEP_STAGE` is not a valid value in `supportedMetrics`. Sleep support is declared
> by including `parseSleep` in `parsing.exports`. If your driver supports sleep, add
> `parseSleep` to exports and omit `SLEEP_STAGE` from `supportedMetrics` entirely.

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

> **Packet interleaving:** All sync commands fire before any data is processed.
> A device may begin responding to the first command before the last command has been
> sent. If your parser has any state that depends on receiving packets in a specific
> order, add `DELAY` commands between sync writes to give the device time to respond
> before the next command is issued. If packet ordering is critical, use longer
> delays and validate packet types explicitly in your parser rather than relying on
> arrival order.

---

### The `parsing` Block

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

You can write the module in any language that compiles to WASM: Rust, C,
AssemblyScript, Kotlin (via kotlin-wasm), or raw WAT. The compiled output must be a
valid `.wasm` binary encoded as Base64 for the manifest.

### Memory Layout

The app and your WASM module share a single linear memory region. Three fixed areas
are used:

```
Offset 0x0000 (    0) — METADATA REGION — 16 bytes
  The app writes sync context here before every parse call.
  Bytes 0–7:  syncStartMs — i64 little-endian — UTC epoch ms captured once
              at the start of the sync, before any packets are processed.
              Use this as the reference point for relative timestamp
              reconstruction. Do not call any system clock from WASM.
  Bytes 8–9:  utcOffsetMinutes — i16 little-endian — the device's current
              UTC offset in minutes (e.g. UTC+1 = 60, UTC-5 = -300, UTC = 0).
              Use this to derive local dateIso for sleep sessions when the
              device only provides UTC timestamps (Path 2 in Date Attribution).
              Note: this is the offset at sync time — see DST caveat in the
              sleep date attribution section.
  Bytes 10–15: reserved — will always be zero in spec v2. Do not read or
              write these bytes. Future spec versions may define values here;
              a non-zero value in bytes 10–15 does not indicate an error.

Offset 0x0010 (   16) — INPUT REGION  — max 4,080 bytes
  The app writes raw BLE characteristic bytes here before every call.
  The app zeroes any bytes beyond the current packet length, so reads
  past byteLength return 0x00, not stale data.

Offset 0x1000 (4,096) — OUTPUT REGION — max 61,440 bytes
  Your module writes UTF-8 JSON output here and returns the byte count.
```

These offsets are fixed. Do not change them. Do not write beyond
`0x1000 + 61440 = 0xF000`. Writing past this boundary will corrupt memory or trap.

To read from the metadata region (AssemblyScript example):
```typescript
const syncStartMs: i64 = load<i64>(0);       // offset 0, little-endian i64
const utcOffsetMinutes: i16 = load<i16>(8);   // offset 8, little-endian i16
```

### Function Signature

All three exported parse functions use the same signature:

```
(func (param i32 i32) (result i32))
  param 1 — memory offset of input bytes (always 16 / 0x0010)
  param 2 — length of input bytes
  result  — byte length of JSON written at offset 4096
```

Return `0` to signal "no data for this packet". The app will not read the output
region.

### Call Sequence (per notification)

1. App writes `syncStartMs` (i64, little-endian) to memory offset `0`
2. App writes raw BLE bytes to memory offset `16`
3. App zeroes bytes `16 + byteLength` through `16 + previousByteLength - 1`
   (clears stale bytes from the previous packet)
4. App calls your function with `(16, byteLength)`
5. Your function parses the bytes, writes JSON at offset `4096`, returns byte count
6. App reads `byteCount` bytes from offset `4096` and deserialises the JSON
7. If return value is `0`, app skips reading entirely

### JSON Output Schemas

Your functions must write valid UTF-8 JSON at offset 4096 conforming to these
schemas. Unknown keys are ignored by the app — you can include extra fields for
debugging.

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
| `recordedAtMs` | int64 | yes | UTC epoch milliseconds when the sensor recorded this value. |
| `confidence` | float | no | Signal quality 0.0–1.0 if available. Null otherwise. |
| `metaJson` | string | no | Any device-specific extra data as a JSON string. Null otherwise. |

> **Deduplication:** `recordedAtMs` is part of the deduplication key for
> point-in-time metrics. Use the exact timestamp from the device — do not round or
> normalise. For accumulator metrics, `recordedAtMs` must be UTC midnight of the
> accumulation day — this is what makes re-syncs idempotent.

An empty array `[]` is valid and equivalent to returning `0`.

**parseSleep** — writes a single JSON object or nothing:

```json
{
  "dateIso": "2024-01-15",
  "sleepStartMs": 1705276800000,
  "sleepEndMs": 1705305600000,
  "durationMinutes": 0,
  "stagesJson": "[{\"stage\":\"DEEP\",\"startMs\":1705276800000,\"endMs\":1705283000000}]"
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `dateIso` | string | yes | Local calendar date the user woke up, e.g. `"2024-01-15"`. Derived from the local time representation of `sleepEndMs` — not from the UTC date of `sleepEndMs`. |
| `sleepStartMs` | int64 | yes | UTC epoch ms when sleep began. Must be before `sleepEndMs`. |
| `sleepEndMs` | int64 | yes | UTC epoch ms when sleep ended. Must be greater than `sleepStartMs`. |
| `durationMinutes` | int | no | Ignored. Set to `0`. Duration is always derived from `(sleepEndMs − sleepStartMs) / 60000`. |
| `stagesJson` | string | no | JSON array of stage objects: `[{"stage":"DEEP","startMs":...,"endMs":...}]`. Valid stage values: `DEEP`, `LIGHT`, `REM`, `AWAKE`. Must include AWAKE stages — do not omit them. Null if device does not provide stage breakdown. |

> **In-progress sessions:** If `sleepEndMs` is 0, equal to `sleepStartMs`, or less
> than `sleepStartMs`, the session is in progress or invalid. Return `0` — do not
> emit it.

> **Multiple sessions per date:** A driver may emit more than one session for the
> same `dateIso`. The engine merges all sessions sharing the same `(driverId,
> dateIso)` into a single full-night record: the earliest `sleepStartMs`, the latest
> `sleepEndMs`, and the union of all stage objects sorted by `startMs`. Total
> duration is recomputed from the merged span.

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
| `startTimeMs` | int64 | yes | UTC epoch ms when the activity began. |
| `endTimeMs` | int64 | yes | UTC epoch ms when the activity ended. |
| `durationMinutes` | int | no | Ignored by the engine. Set to `0`. Duration is always derived from `(endTimeMs − startTimeMs) / 60000`. |
| `deviceName` | string | yes | Raw activity label from the device, e.g. `"Outdoor Run"`. The user classifies this after import. |
| `avgHrBpm` | float | no | Average heart rate in bpm. Null if not available. |
| `maxHrBpm` | float | no | Peak heart rate in bpm. Null if not available. |
| `minHrBpm` | float | no | Lowest heart rate in bpm. Null if not available. |
| `calories` | float | no | Total energy expenditure in kcal. Null if not available. |
| `activeCalories` | float | no | Active (non-resting) energy in kcal. Null if not available. |
| `distanceMeters` | float | no | Distance in metres. Null if not available. |
| `steps` | int | no | Steps during this activity only — not the daily total. Null if not available. |
| `hrZonesJson` | string | no | HR zone breakdown: `[{"zone":1,"seconds":120},...]`. Null if not available. |

> **Deduplication:** `startTimeMs` is part of the deduplication key. Use the exact
> start timestamp from the device.

Signal "no activity data" by returning `0` or writing `{}`.

---

## Complete Example

This example shows a device that supports metrics, sleep, and activities. Note
`specVersion: "2"` (required for the new memory layout), `parseSleep` in exports
(how sleep capability is declared — not via `supportedMetrics`), and
`durationMinutes: 0` in the activity object (ignored by the engine).

```json
{
  "id": "example_complex_v1",
  "displayName": "Example Complex Device",
  "version": "1.0.0",
  "specVersion": "2",
  "author": "example-author",
  "supportedMetrics": ["HR", "HRV", "SPO2", "STEPS", "BATTERY"],
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
| `parsing.mode` | Must be `"WASM"` |
| `parsing.wasmBase64` | Must decode to a valid WASM binary (magic header check: first 4 bytes must be `0x00 0x61 0x73 0x6D`) |
| `exports.parseMetrics` | Must not be blank |

---

## Checklist Before Submitting a Driver

**Structure**
- [ ] `id` is unique and will not change in future versions
- [ ] `version` follows semver (`X.Y.Z`)
- [ ] All UUIDs are full 128-bit format
- [ ] `matchByName` matches the exact advertised device name (check with a BLE scanner app)
- [ ] `matchConfidence` is `CERTAIN` only if name + UUID uniquely identify this device
- [ ] All `syncCommands` use role names that exist in `ble.characteristics`
- [ ] No proprietary score metrics included in `supportedMetrics`
- [ ] WASM binary starts with `0x00 0x61 0x73 0x6D` (valid WASM magic header)
- [ ] `parse_metrics` (or your chosen export name) is exported from the module
- [ ] Output JSON conforms to the schemas in this document

**Timestamps and date attribution**
- [ ] All emitted timestamps are UTC epoch milliseconds (13-digit integers)
- [ ] `recordedAtMs` is sourced from device data, not from sync time, for all historical records
- [ ] If device timestamps are in local time, conversion to UTC is documented and tested
- [ ] DST is not a problem — the driver does not use today's UTC offset for historical records
- [ ] Accumulator metrics (STEPS, CALORIES, DISTANCE, etc.) emit one reading per calendar day with `recordedAtMs` set to UTC midnight of that day
- [ ] Sleep `dateIso` is the **local calendar date** the user woke up — either taken directly from device-local wake time (Path 1), or derived using `utcOffsetMinutes` from the metadata region (Path 2)
- [ ] If using Path 2 (UTC + offset), DST caveat is documented in the driver
- [ ] Sleep `sleepStartMs` and `sleepEndMs` cover the full session including AWAKE periods at the boundaries
- [ ] In-progress sleep sessions (sleepEndMs = 0 or ≤ sleepStartMs) return 0, not a corrupt record
- [ ] `SLEEP_STAGE` is not in `supportedMetrics` — sleep capability is declared via `parseSleep` in exports
- [ ] If the driver uses `syncStartMs` for relative timestamp reconstruction, it reads it from offset 0 as i64
- [ ] If the driver uses `utcOffsetMinutes` for local dateIso derivation, it reads it from offset 8 as i16
- [ ] `specVersion: "2"` is present in the manifest (required for the new memory layout)

**Data integrity**
- [ ] No unrecognised or proprietary metric types are emitted
- [ ] STEPS and other accumulators are daily totals, not per-interval values
- [ ] Activity `steps` field contains activity-only steps, not the daily total
- [ ] WASM output never exceeds 61,440 bytes (offset 0xF000)
- [ ] Parser handles unknown packet types by returning 0, not crashing

**WASM correctness**
- [ ] All timestamp variables are declared as i64 (or equivalent 64-bit type) — no i32 for any timestamp
- [ ] All timestamp arithmetic uses 64-bit operations throughout — no i32 intermediates
- [ ] Parser uses `byteLength` (param 2) as the authoritative packet boundary — does not rely on zero-padding for framing
- [ ] Parser is stateless per-packet, or accumulator state loss mid-sync produces silence not corruption
- [ ] WASM output never exceeds 61,440 bytes (offset 0xF000)
- [ ] Parser returns 0 (not a partial result) for any packet it does not fully recognise

**End-to-end verification**
- [ ] Driver file loads without validation errors in the app
- [ ] At least one metric appears on the Dashboard after syncing
- [ ] Syncing twice in a row produces no duplicates
- [ ] A reading recorded yesterday appears under yesterday, not today
- [ ] Sleep duration on the Dashboard matches the band's own app
- [ ] Steps on the Dashboard match the band's own app
- [ ] Timestamp reconstruction method is documented if the device does not provide native UTC timestamps

**Correctness under failure conditions**
- [ ] Parser does not emit a sleep session when sleepEndMs is 0, missing, or ≤ sleepStartMs
- [ ] If the device sends complete stage arrays in multiple packets, stages are not doubled in the merge
- [ ] BATTERY readings are not emitted into metric_readings — battery is device metadata only
- [ ] Driver changelog documents any version that changes recordedAtMs values, so users know to use Reprocess

---

*Open Athlete Metrics — Driver Authoring Guide*