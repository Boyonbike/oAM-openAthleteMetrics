# Date & Time Architecture — openAthleteMetrics

This document is the authoritative reference for how date and time values are handled throughout
the oAM system. It surfaces the current implementation precisely (including known bugs), states
the correct decisions for each area, and acts as a specification for remedial changes.

Source-of-truth files examined:
- `data/db/Converters.kt` — TypeConverters
- `worker/DailySummaryWorker.kt` — aggregation time windows
- `ble/BleEngine.kt` — `affectedDates` tracking and sync command execution
- `ble/sync/SyncValidator.kt` — timestamp validation + sleep date correction
- `ble/sync/DeviceReprocessor.kt` — historical re-trigger
- `ble/wasm/WasmDriverEngine.kt` — WASM bridge, sync anchor, metadata block
- `ble/driver/SyncCommand.kt` — write command type definitions
- `ble/driver/ParsingConfig.kt` — `WasmExports` data class
- `seeder/SeederService.kt` — test data construction
- `data/db/DateUtils.kt` — `toUtcStartMs()` extension
- `ui/history/HistoryScreen.kt`, `ui/metric/MetricDetailViewModel.kt`, etc. — UI date queries
- `assets/drivers/example_wasm.json`, `Driver Builds/Hume Band 1/HumeBandDriver.json` — manifest examples

---

## 1. Storage Layer

### Timestamp storage format

All point-in-time timestamps are stored as **64-bit signed integers representing UTC epoch
milliseconds**. The `Converters` class maps `Instant ↔ Long` via `toEpochMilli()` /
`Instant.ofEpochMilli()`. This is consistent across every typed table.

Tables using `Instant` (via TypeConverter): `hr_readings`, `hrv_readings`, `spo2_readings`,
`respiration_readings`, `skin_temp_readings`, `steps_readings`, `active_calorie_readings`,
`total_calorie_readings`, `blood_pressure_readings`, `glucose_readings`,
`metric_readings_staging`, `activities`, `sleep_sessions`, `sync_sessions`.

Tables using raw `Long` (no TypeConverter, manual mapping in repository layer):
`devices` (`last_seen_ms`, `last_sync_ms`), `raw_device_data` (`received_at`),
`question_responses` (`recorded_at`).

The raw-Long tables are not a correctness bug — the values are still UTC epoch ms — but they
are an inconsistency in abstraction. The TypeConverter path is preferred for new columns.

### TypeConverter implementation — current state

```kotlin
// Instant ↔ Long  (epoch ms, UTC — no timezone information embedded)
fun fromInstant(instant: Instant): Long = instant.toEpochMilli()
fun toInstant(value: Long): Instant = Instant.ofEpochMilli(value)

// LocalDate ↔ String  (ISO-8601 "YYYY-MM-DD", no timezone embedded)
fun fromLocalDate(date: LocalDate): String = date.toString()
fun toLocalDate(value: String): LocalDate = LocalDate.parse(value)
```

The converters are correct. `Instant.ofEpochMilli` is always UTC. `LocalDate.parse` is
calendar-only — no timezone is stored in or inferred from the string. The critical question is
therefore not what the converter does, but **what timezone was used to derive the LocalDate
before it was written**, and whether that is consistent everywhere.

---

## 2. Date String Keys (YYYY-MM-DD)

### The canonical decision

The date string primary key on `daily_summary`, `daily_context`, `sleep_sessions`, and
`question_responses` **must represent the user's local calendar date** (device timezone at
the moment of writing). Rationale:

1. `DailySummaryWorker` uses `ZoneId.systemDefault()` for all time windows — this defines what
"day N" means for aggregation and must be canonical.
2. `LocalDate.now()` (device local) is used throughout the UI as "today".
3. Physiological windows (resting HR 00:00–06:00, morning HRV after 05:00) are meaningless in
UTC for users outside UTC.
4. A user perceives their June 19 data as the readings that happened while their clock showed
June 19. Storing it under UTC June 19 violates that expectation.

The consequence is: **every code path that produces a date key must use
`ZoneId.systemDefault()`, not `ZoneOffset.UTC`.**

### Concrete scenario: 23:45 local, 00:15 UTC next day

Using UTC-1 for clarity: 23:45 local = 00:45 UTC next calendar day.

- Driver outputs `recordedAtMs` = 00:45 UTC June 20.
- **Correct date key** = June 19 (it was 23:45 local on June 19 when the reading occurred).
- `ZoneId.systemDefault()` → `recordedAt.atZone(localZone).toLocalDate()` = "2024-06-19" ✅
- `ZoneOffset.UTC` → `recordedAt.atZone(ZoneOffset.UTC).toLocalDate()` = "2024-06-20" ❌

### Current bug: BleEngine uses UTC for date attribution

`BleEngine.kt:839`:
```kotlin
val date = reading.recordedAt.atZone(ZoneOffset.UTC).toLocalDate()
synchronized(affectedDates) { affectedDates.add(date) }
```

For the scenario above, this adds "2024-06-20" to `affectedDates` and the worker is triggered
for June 20. The worker's local-timezone window for June 20 starts at 01:00 UTC (June 20
midnight local), so the reading at 00:45 UTC is **before the window** and is not counted. The
reading also does not trigger a June 19 recompute, so June 19's summary is also incomplete.
The reading vanishes from both daily summaries.

`DeviceReprocessor.kt:151` and `:153` have the identical bug.

**Fix**: Replace `atZone(ZoneOffset.UTC).toLocalDate()` with
`atZone(ZoneId.systemDefault()).toLocalDate()` in `BleEngine.kt:839` and
`DeviceReprocessor.kt:151,153`.

---

## 3. Device Timestamp Communication

### Architecture overview

The system uses WASM-based drivers exclusively. JSON-only manifest drivers are not yet
implemented. All timestamp conversion logic lives inside the WASM module written by the driver
author.

**Parsing (`parseSession`) receives no time metadata from the host at all.** The app buffers
every BLE notification for the whole sync and calls the driver's `parseSession` export once (or
once per chunk, for a large session) with only the buffered frames as JSON — no metadata block is
written before this call. This is a change from the retired per-notification contract, which used
to write a 16-byte header (`syncStartMs`/`utcOffsetMinutes`) before every parse call; that header
no longer exists for `parseSession`. See `DRIVER_AUTHORING_GUIDE.md`'s
[Sourcing Timestamps](DRIVER_AUTHORING_GUIDE.md#sourcing-timestamps-a-decision-tree) section.

The contract (from `DRIVER_AUTHORING_GUIDE.md`): every timestamp emitted by a driver must be
UTC epoch milliseconds. The app trusts what the driver gives it. If the device sends timestamps
in local time, the driver is responsible for converting to UTC before writing to the output JSON.

If a driver genuinely needs a connection-time reference (for the relative-offset formats below),
the *only* host-provided time value is delivered separately, once, before `buildSyncCommands` —
not before `parseSession`. See [§3b](#3b-device-time-write-path) below. A driver that needs this
must capture it during `buildSyncCommands` and cache it in its own WASM state for `parseSession`
to read later in the same connection.

### Device clock formats and how to handle each inside `parseSession`

**Format 1 — Seconds since a device-specific epoch (e.g. Jan 1 2000)**
```
recordedAtMs = (deviceSeconds + EPOCH_OFFSET_S) * 1000
```
where `EPOCH_OFFSET_S` is the seconds from Unix epoch to the device epoch
(Jan 1 2000 UTC = 946 684 800 s).

**Format 2 — Seconds since Unix epoch**
```
recordedAtMs = deviceSeconds * 1000
```
This is UTC by definition — no offset needed.

**Format 3 — Packed BLE Date-Time characteristic (year/month/day/hour/min/sec fields)**
The device encodes local wall-clock time with no UTC offset. If the driver has cached a
connection-time `utcOffsetMinutes` (captured during `buildSyncCommands` — see above), it can apply it:
```
localEpochMs  = parseLocalFields(year, month, day, hour, min, sec) * 1000
recordedAtMs  = localEpochMs - (utcOffsetMinutes * 60_000)
```

**Format 4 — Relative offset from sync time**
Some devices report "N seconds before sync". This requires a cached connection-time reference,
since `parseSession` is not given one by the host (see Architecture overview above):
```
recordedAtMs = cachedEpochMs - (offsetSeconds * 1000)
```

### Device timezone assumption

Low-cost wearables encode local wall-clock time with no UTC offset. A driver that needs to
convert to UTC must have cached a `utcOffsetMinutes` value from `buildSyncCommands` — `parseSession`
does not receive one directly.

The value a driver would cache comes from `SyncContextFactory.build()` (only if the manifest
declares `syncRequirements.datetime: true`) or from the raw metadata header
`buildSyncCommands` always receives regardless of `syncRequirements`:
```kotlin
zone.rules.getOffset(instant).totalSeconds / 60   // SyncContextFactory
(TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000).toShort()   // buildSyncCommands metadata header
```
Both are the Android system timezone at the moment `buildSyncCommands` is called (connection
time) — the correct proxy for the user's local timezone at that moment. DST transitions are
handled correctly because both read the current DST-aware offset at call time, not a fixed value.

### Clock drift and desync

A wearable clock drifting 4 minutes behind means all `recordedAt` values are 4 minutes early.

- **Day bucketing**: readings within 4 minutes of local midnight may shift to the previous day's
window. For most metrics this is inconsequential.
- **Morning HRV** (first reading ≥ 05:00 local): the highest-impact case — a drifted clock at
04:56 device time = 05:00 true time may cause the first morning reading to be excluded.

If the device exposes its current time at connection, the WASM module should cache the
connection-time reference captured during `buildSyncCommands` (see [§3](#3-device-timestamp-communication)
above) and compute:
```
clockDriftMs = cachedEpochMs - deviceCurrentTimeAsUtcMs
recordedAtMs += clockDriftMs    // corrects all timestamps in the session
```
If the device does not expose current time, drift is undetectable. Document as a known
limitation per driver.

### Historical data sync

Many wearables batch-store days or weeks of data. `BleEngine` collects all affected dates
across the full sync into `affectedDates`, then at quiescence triggers one
`DailySummaryWorker` per unique date:

```kotlin
val datesToProcess = synchronized(affectedDates) {
affectedDates.toSet().also { affectedDates.clear() }
}
datesToProcess.forEach { enqueueSummaryWorker(it, workManager) }
```

This correctly handles bulk historical inserts — every affected date gets a worker. After
the fix in §2, the date attribution uses the correct local timezone.

### Clock not set / clearly invalid timestamps

`SyncValidator` rejects readings with `recordedAt < 2020-01-01T00:00:00Z`. This catches:
- Unix epoch 0 (device never synced) ✅
- Device-specific epoch as raw seconds without offset applied ✅
- Short relative offsets that have not been anchor-corrected ✅

The 1-hour future ceiling catches devices whose clocks are set significantly ahead. The
2020-01-01 floor should be reviewed periodically (consider making it "current year minus 10").

### Sync-time anchor

`WasmDriverEngine.startSync()` still captures a `syncStartMs`/`capturedUtcOffsetMinutes` pair,
but this is now dead state — it is only ever read by the retired per-packet `callParse()` path,
which nothing calls. It is **not** written into WASM memory before `parseSession`.

The live per-connection time anchor is the metadata header `buildSyncCommands` receives (see
[§3](#3-device-timestamp-communication) and [§3b](#3b-device-time-write-path)): `currentTimeMs`/
`utcOffsetMinutes`, captured fresh at the moment `buildSyncCommands` is called (once, at
connection, before any BLE data is exchanged) — correct because the timezone and wall-clock
reference should be stable across the entire sync session. A driver that needs this reference
during `parseSession` must read it during `buildSyncCommands` and cache it itself.

### JSON manifest drivers (future feature — not yet implemented)

When pure JSON manifest drivers are added, the manifest format should declare:

```json
"parsing": {
"mode": "JSON",
"timestampFormat": "DEVICE_EPOCH_SECONDS | UNIX_EPOCH_SECONDS | BLE_DATETIME | SYNC_RELATIVE_SECONDS",
"deviceEpochOffsetS": 946684800,
"timestampIsLocalTime": true
}
```

The host JSON interpreter would then:
1. Parse the raw timestamp bytes using the declared format.
2. If `timestampIsLocalTime = true`, subtract `utcOffsetMinutes * 60_000` (from sync anchor)
to produce UTC epoch ms.
3. Construct `MetricReading.recordedAt = Instant.ofEpochMilli(utcEpochMs)`.

---

## 3b. Device Time Write Path

### Current state: implemented via `buildSyncCommands`

This section previously described a design gap and proposed future options; that gap has since
been closed. `WasmExports` includes an optional `buildSyncCommands` export
(`ble/driver/ParsingConfig.kt`), and `BleEngine` calls it once at connection time, before any
static `syncCommands` would otherwise run (`BleEngine.kt` — gated solely on
`wasm.exports.buildSyncCommands != null`, independent of `manifest.specVersion`).

`SyncCommand.Write.bytes` is still a fixed hex string when it comes from the manifest's static
`syncCommands` array — there is no token-substitution mechanism there (Option A below was never
built). But when `buildSyncCommands` is exported, its return value **is** the complete command
sequence, generated by the WASM module itself at connection time with a fresh time reference — see
the next paragraph — so a driver can construct exactly the byte sequence its device's
time-synchronisation handshake requires. The static `syncCommands` list is not executed at all in
that case.

`buildSyncCommands` receives a 16-byte metadata header (`currentTimeMs` i64 LE at offset 0,
`utcOffsetMinutes` i16 LE at offset 8, both captured fresh — via `Instant.now()` /
`TimeZone.getDefault().getOffset(...)` — immediately before the call) followed by a `SyncContext`
JSON string (populated per the manifest's `syncRequirements`) at offset 16. Full details, including
the JSON schemas, live in `DRIVER_AUTHORING_GUIDE.md`'s
[Dynamic Sync Commands](DRIVER_AUTHORING_GUIDE.md#dynamic-sync-commands-buildSyncCommands)
section — this document covers only the timezone-correctness implications.

### Why this matters

Some BLE wearables use a time-synchronisation protocol as part of their data-download handshake:
the device exposes a Current Time characteristic (Bluetooth CTS, UUID 0x1805 / 0x2A2B) or a
vendor-specific equivalent, and refuses to release stored readings (or tags them with an
uncorrected internal clock) until the host writes the current time. `buildSyncCommands` is what
lets a driver author express "write the current time to this characteristic" — the driver encodes
the metadata header's `currentTimeMs`/`utcOffsetMinutes` into whatever byte format the device
expects and includes that as a `Write` command in its returned array.

### Canonical rule for time writes

- **Device expects UTC epoch seconds**: `currentTimeMs / 1000` from the metadata header, encoded
as i32 or i64 little-endian.
- **Device expects local wall-clock (BLE DateTime or vendor-packed bytes)**: derive local time
from `currentTimeMs + utcOffsetMinutes * 60_000`, both from the same metadata header.
- **Never substitute a value cached earlier in the connection** (e.g. the retired
`syncStartMs`/`capturedUtcOffsetMinutes` `WasmDriverEngine` fields, which are dead — see
[§3](#3-device-timestamp-communication)) — always use the metadata header `buildSyncCommands`
was just given; it is captured fresh, synchronously, immediately before each call.
- **Do not use `ZoneOffset.UTC` for local-wall-clock devices under any circumstances.** The
failure mode is that the device clock is set to a time off by the full UTC offset (e.g.
5 hours 30 minutes in India, 8 hours in Beijing). Every reading the device takes after this
write will carry a permanently wrong timestamp with no recovery path.

### DST handling

`TimeZone.getDefault().getOffset(currentTimeMillis)` is DST-aware: it returns the current total
UTC offset including the DST component. For a device in UTC+1 (winter) / UTC+2 (summer), it
returns 60 minutes or 120 minutes respectively — correct in both cases.

Because `buildSyncCommands` runs once, synchronously, and the metadata header is captured
immediately before that single call, there is no window during command *generation* for a DST
transition to make the value stale — unlike the old per-command execution-time model this section
used to describe. The residual risk is only across separate connection attempts (handled by the
next subsection) or if a driver mistakenly caches and reuses an older value instead of what it was
just given.

### Device re-sync after timezone change between sessions

If the user changes timezone between two separate sync sessions, the next connection's
`buildSyncCommands` call will capture the new offset fresh (`TimeZone.getDefault()` /
`ZoneId.systemDefault()` at that moment). Any write commands in the new session will use the
updated offset. No special handling is required.

### Failure modes summary

| Wrong implementation | Effect |
|---|---|
| Send UTC epoch ms where device expects epoch seconds | Device clock set to ~51,000 years in the future |
| Send local wall-clock time to a UTC-expecting device | Device clock off by UTC offset permanently |
| Send `ZoneOffset.UTC` to a local-wall-clock-expecting device | Device clock off by UTC offset permanently; all future readings unrecoverable |
| Cache and reuse an offset from earlier in the connection instead of the metadata header's fresh value | Device clock off by 60 minutes if a DST boundary was crossed since the cached value was captured |
| Use raw integer offset instead of `ZoneId.systemDefault()` | Correct most of the year; wrong by 60 minutes for the hours immediately after a DST transition |

The most dangerous failure is writing UTC to a local-wall-clock device. The errors it produces
(all subsequent readings carry a UTC-offset timestamp error) are undetectable without an
independent reference and cannot be corrected in the app layer after the fact.

---

## 4. Sleep Sessions

### Column semantics

- `sleep_start_ms` / `sleep_end_ms`: UTC epoch ms — absolute points in time. ✅
- `date`: LocalDate stored as ISO-8601 text — the "morning date", the calendar day the user
woke up.

### Current bug: SyncValidator uses UTC for sleep date

`SyncValidator.kt:76`:
```kotlin
val expectedDate = session.sleepEndMs.atZone(ZoneOffset.UTC).toLocalDate()
```

For a user in UTC+8 who wakes at 07:00 local (23:00 UTC previous day):
- UTC date of `sleepEndMs` = yesterday.
- Local date of `sleepEndMs` = today (the actual wake-up day).
- The session is filed under **yesterday** — today shows no sleep data.

**Fix**: `SyncValidator.kt:76` — replace `atZone(ZoneOffset.UTC)` with
`atZone(ZoneId.systemDefault())`.

The future-date validation check (`session.date.isAfter(LocalDate.now())`) is correct once
the date is local-time-based — both sides use the system default timezone.

### Device-sourced sleep timestamps

The WASM driver converts device local-time sleep start/end to UTC epoch ms using
`utcOffsetMinutes` from the sync anchor. The `SleepWasmDto.dateIso` field from the driver is
ignored by the host; the host always recomputes the date from `sleepEndMs` using the local
timezone (post-fix).

### Timezone change mid-sleep (travel scenario)

If the user falls asleep in UTC+1 and wakes in UTC+8 (overnight flight):
- `sleep_start_ms` and `sleep_end_ms` are UTC — unaffected by timezone change. ✅
- `sleepEndMs.atZone(ZoneId.systemDefault())` uses UTC+8 at wakeup — the correct timezone. ✅
- The `date` column reflects the morning the user physically woke up. ✅

---

## 5. HRV Morning Reading

### Definition

First HRV reading at or after 05:00 **local time** for the date being summarised.

### Current implementation

`DailySummaryWorker.kt:69`:
```kotlin
val morningStartMs = date.atTime(5, 0).atZone(zone).toInstant().toEpochMilli()
```
where `zone = ZoneId.systemDefault()`. **Correct.** ✅

### UTC+8 example

`morningStartMs` for "2024-06-19" in UTC+8 = 2024-06-18 21:00 UTC. The query
`recordedAt >= morningStartMs` correctly captures readings from 05:00 local onward. ✅

### Historical sync timing caveat

If a reading was taken at 06:00 in UTC+1 while travelling, but synced 3 days later after
returning to UTC+0, the driver uses UTC+0 at sync time — the UTC timestamp will be off by
1 hour. This is a device-layer limitation (device stores local time without timezone metadata),
not fixable in the app. Document per driver.

---

## 6. Resting HR Window

### Definition

Minimum 5-minute-bucket average HR between local midnight and 06:00 local.

### Current implementation

`DailySummaryWorker.kt:65–90`:
```kotlin
val zone       = ZoneId.systemDefault()
val dayStartMs = date.atStartOfDay(zone).toInstant().toEpochMilli()        // local midnight
val nightEndMs = date.atTime(6, 0).atZone(zone).toInstant().toEpochMilli() // local 06:00

val restingHrBpm = hrReadings
.filter { it.recordedAt.toEpochMilli() in dayStartMs until nightEndMs }
.groupBy { it.recordedAt.toEpochMilli() / 300_000L }
.values
.map { bucket -> bucket.map { it.bpm }.average() }
.minOrNull()
```

Both boundaries use `ZoneId.systemDefault()`. **Correct.** ✅

---

## 7. Day Scroller and "Today"

### Current implementation

`HistoryScreen.kt:121`, `HistoryViewModel.kt:114`, `DashboardScreen.kt:100` all use
`LocalDate.now()` (device local time). **Correct for date navigation.** ✅

After the §2 fix, date keys also use system default timezone, so "today" in the UI and the
date under which today's readings land will agree.

### Bug: UI raw-reading range queries use UTC boundaries

`MetricDetailViewModel.kt:80–82`, `HistoryScreen.kt:615`, `DashboardScreen.kt:229`,
`QuestionsScreen.kt:486`:
```kotlin
date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
```

For a user in UTC+8, the time-series chart for "June 19" queries
[June 19 00:00 UTC, June 20 00:00 UTC) = [June 19 08:00 local, June 20 08:00 local). The
chart shows an 8am-to-8am slice rather than midnight-to-midnight. Data appears shifted by the
UTC offset.

Corresponding `atZone(ZoneOffset.UTC).toLocalDate()` calls used to convert epoch ms back to a
display date (e.g. `HistoryScreen.kt:619,629`, `DashboardScreen.kt:233,244`) have the same
issue in reverse.

**Fix**: Replace `atStartOfDay(ZoneOffset.UTC)` with `atStartOfDay(ZoneId.systemDefault())`,
and `atZone(ZoneOffset.UTC).toLocalDate()` with `atZone(ZoneId.systemDefault()).toLocalDate()`
in all UI-layer date-to-epoch and epoch-to-date conversions.

Affected locations: `MetricDetailViewModel.kt:80,82,96,123,171,172`, `HistoryScreen.kt:615,619,629`,
`DashboardScreen.kt:229,233,244`, `QuestionsScreen.kt:486,490,500`.

---

## 8. Seeder Timestamps

### Current implementation

`SeederService.kt` uses `date.toUtcStartMs()` (= `date.atStartOfDay(ZoneOffset.UTC)`) as the
base for all reading timestamps, and `toInstant(ZoneOffset.UTC)` for specific time-of-day
readings.

### The problem

Seeder timestamps treat "midnight" as UTC midnight, but `DailySummaryWorker` treats "midnight"
as local midnight. On a UTC+8 device, seeder "SpO2 readings from 22:00" are generated at
22:00 UTC = 06:00 local the next morning — outside the intended overnight window and possibly
landing in the wrong day's summary.

**Fix**: Change `SeederService.kt` to use `ZoneId.systemDefault()` as the timezone reference:
```kotlin
// Replace:
val dayStartMs = date.toUtcStartMs()
// With:
val dayStartMs = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
```
Replace all `toInstant(ZoneOffset.UTC)` time-of-day calls with `atZone(ZoneId.systemDefault())`.
This ensures seeder data lands in the same local-time slots as device-sourced data and behaves
correctly when tests run on non-UTC machines.

---

## 9. Timezone Change Handling

### What happens when the user travels from UTC+1 to UTC+8

`ZoneId.systemDefault()` returns UTC+8 immediately after the device timezone changes.

**Pre-computed `daily_summary` rows**: These are stale for the hour-bounded aggregates (resting
HR, morning HRV), which were computed with UTC+1 windows. The date string keys themselves are
still correct — they represent the date the user experienced locally at the time of recording.

**Historical device data synced after timezone change**: The driver uses `utcOffsetMinutes`
captured at sync time (now UTC+8). For readings recorded in UTC+1 territory, UTC timestamps
will be off by 7 hours. This is a device-layer limitation — not fixable in the app without
per-reading timezone metadata from the device.

**Day scroller / date navigation**: `LocalDate.now()` gives the new local date in UTC+8. Date
keys from before the travel are based on UTC+1 local dates. Both correctly reflect what the
user experienced as "June 19" on those respective days.

### Detection and re-computation mechanism

Store the last-known `ZoneId.systemDefault().id` in `SharedPreferences`. On app startup:
1. Compare to current `ZoneId.systemDefault().id`.
2. On mismatch, enqueue `DailySummaryWorker` for the most recent N days (e.g. 7).
3. Update the stored timezone ID.

No schema change required. This handles the majority of travel scenarios without manual
intervention.

---

## 10. Decision Register

| Section | Verdict | Summary |
|---------|---------|---------|
| §1 Storage Layer | ✅ Correct | TypeConverters are correct; raw-Long tables are an abstraction inconsistency but not a correctness bug. |
| §2 Date String Keys | ❌ Bug risk | `BleEngine.affectedDates` and `DeviceReprocessor` use UTC for date attribution but the rest of the system expects local-timezone date keys. Readings near midnight in non-UTC timezones vanish from both daily summaries. |
| §3 Device Timestamp Communication | ✅ Correct (revised) | `parseSession` receives no time metadata from the host at all — the old per-notification metadata header is dead. A driver needing a connection-time reference must capture and cache it itself during `buildSyncCommands`. Floor/ceiling checks are sound. JSON manifest drivers need a specified timestamp format when implemented. Clock drift is undetectable without device cooperation. |
| §3b Write Path — timezone handling | ✅ Implemented | `buildSyncCommands` is implemented and is the mechanism this section used to describe as a future gap. Its metadata header is captured fresh, synchronously, immediately before each call, so a driver can construct a correct time write for CTS-style handshakes. |
| §3b Write Path — half-hour UTC offsets | ✅ Correct | `utcOffsetMinutes` is stored as `i16` (range ±32767 min), which correctly represents UTC+5:30 (330 min), UTC+5:45 (345 min), and UTC+9:30 (570 min). `getOffset(ms) / 60_000` is lossless for all real-world timezone offsets — all are exact multiples of whole minutes. |
| §3b Write Path — device re-sync after timezone change | ✅ Correct | `utcOffsetMinutes` is re-captured from `TimeZone.getDefault()`/`ZoneId.systemDefault()` fresh on every `buildSyncCommands` call, i.e. at the start of each new connection. Stale offsets from a prior session cannot carry forward. No mechanism needed. |
| §4 Sleep Sessions | ❌ Bug risk | `SyncValidator` assigns sleep date using UTC `atZone(ZoneOffset.UTC)`. Users in positive UTC offsets (UTC+1 through UTC+14) get sleep sessions filed under the wrong (previous) date. |
| §5 HRV Morning Reading | ✅ Correct | Uses `ZoneId.systemDefault()` for the 05:00 boundary. Historical timezone drift is a device-layer limitation. |
| §6 Resting HR Window | ✅ Correct | Uses `ZoneId.systemDefault()` for both 00:00 and 06:00 boundaries. |
| §7 Day Scroller | ✅ Correct (navigation) / ❌ Bug risk (charts) | Date navigation uses local time correctly. UI raw-reading range queries use `ZoneOffset.UTC`, so charts show an offset slice rather than midnight-to-midnight local data. |
| §8 Seeder Timestamps | ❌ Bug risk | Seeder uses UTC midnight as reference for "time of day", so seeder data is misaligned on non-UTC devices. Time-of-day windows (overnight SpO2, steps at 23:59) land in the wrong local slot. |
| §9 Timezone Change | ⚠️ Ambiguous | No detection or re-computation mechanism exists. Hour-window aggregates in `daily_summary` become stale after a timezone change. A startup-check + re-enqueue approach is sufficient; implement after correctness bugs. |

### Required changes — priority order

| Priority | File | Change |
|----------|------|--------|
| P0 | `BleEngine.kt:839` | `ZoneOffset.UTC` → `ZoneId.systemDefault()` |
| P0 | `DeviceReprocessor.kt:151,153` | `ZoneOffset.UTC` → `ZoneId.systemDefault()` |
| P0 | `SyncValidator.kt:76` | `ZoneOffset.UTC` → `ZoneId.systemDefault()` |
| P1 | `MetricDetailViewModel.kt:80,82,96,123,171,172` | `ZoneOffset.UTC` → `ZoneId.systemDefault()` |
| P1 | `HistoryScreen.kt:615,619,629` | `ZoneOffset.UTC` → `ZoneId.systemDefault()` |
| P1 | `DashboardScreen.kt:229,233,244` | `ZoneOffset.UTC` → `ZoneId.systemDefault()` |
| P1 | `QuestionsScreen.kt:486,490,500` | `ZoneOffset.UTC` → `ZoneId.systemDefault()` |
| P2 | `SeederService.kt` (all `toUtcStartMs()` + `toInstant(ZoneOffset.UTC)`) | Use `ZoneId.systemDefault()` |
| P3 | App startup | Detect timezone change, re-enqueue summary workers for recent N days |
| P3 | `WasmDriverEngine.kt:287` | Replace `/ 60_000` with `/ 1000 / 60` to make integer-then-integer division explicit and immune to any future sub-millisecond offset values |
| ~~P3 (future)~~ Done | `ParsingConfig.kt`, `WasmDriverEngine.kt`, `BleEngine.kt` | WASM `buildSyncCommands` export is implemented — see [§3b](#3b-device-time-write-path) |

### Verification checklist

1. Set emulator to **UTC-5**. Seed data and simulate a device sync with a reading at 23:30
local (= 04:30 UTC next day). Confirm the reading appears in **today's** summary, not
tomorrow's.
2. Set emulator to **UTC+8**. Confirm a sleep session ending at 07:00 local (= 23:00 UTC
previous day) appears under **today's** date, not yesterday's.
3. Open a time-series chart for any date in **UTC+8**. Confirm the x-axis spans local
midnight-to-midnight, not UTC midnight-to-midnight.
4. Run the seeder on a **UTC+8** device and confirm SpO2 "overnight" readings (generated from
22:00) appear at 22:00 local, not 22:00 UTC (= 06:00 local next morning).
5. Set emulator to **UTC+5:30** (India). Connect a simulated device and verify via logcat that
`utcOffsetMinutes` = 330 and that the WASM memory at byte offset 8 contains `0x4A 0x01`
(330 as little-endian i16). Confirm that half-hour offset readings land in the correct local
date bucket.
6. Inspect all static `SyncCommand.Write` byte sequences in the Hume Band driver manifest.
Confirm that none of the hex byte sequences contain time-dependent values (e.g. no current
epoch, no date fields). This establishes that device clocks cannot be corrupted by a
wrong-timezone write under the current driver set.
7. Simulate a timezone change mid-session (change emulator timezone while a sync is in
progress). Confirm that the in-progress sync continues to use the anchor captured at
`startSync()` (stable) and that the next sync captures the new timezone correctly.
