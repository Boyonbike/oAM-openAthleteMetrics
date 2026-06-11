# App Fix Prompts

Fixes for systemic issues found during driver and sync pipeline audit.
Work through these in order — each is a self-contained Claude Code session.
Paste both spec documents (Master Plan + MVP Build Spec) at the start of each
session before pasting the prompt.

---

## FIX 1 — In-Progress Sleep Session Permanently Blocks Correct Record

**Priority: CRITICAL** | **Mode: Normal**
Well-scoped fix across two known files (DeviceSyncProcessor and SleepRepository). Steps are clear and sequential with no diagnosis needed.
A sleep session synced mid-sleep (sleepEndMs = 0 or invalid) writes a corrupt
record that shares the same deduplication key as the real session. The real
session can never be inserted. The user's sleep for that night shows as zero
or missing permanently.

---

```
# FIX 1 — In-progress sleep session deduplication block

## Problem
If the user syncs while asleep, the device returns an active sleep session
with an invalid end time (0, equal to start, or a placeholder). The sync
processor currently writes this corrupt session to sleep_sessions. Its
deduplication key (driver_id + dateIso) matches the real session that will
arrive on the next morning sync. The real session is silently skipped as a
duplicate. The user's sleep for that night is permanently missing or
shows zero duration.

## Fix required

### 1. DeviceSyncProcessor — reject in-progress sessions before insert

Before inserting any SleepSession, validate:
- sleepEndMs must be greater than sleepStartMs
- sleepEndMs must not be 0
- (sleepEndMs - sleepStartMs) must be at least 60,000 ms (1 minute)

If any check fails, discard the session and log a warning:
"Discarding in-progress or invalid sleep session for [dateIso]: 
 startMs=[sleepStartMs] endMs=[sleepEndMs]"

Do not insert it. Do not count it in the sync summary. This is not an error
— it is an expected condition when syncing while asleep.

### 2. SleepRepository — add a replace method for corrected sessions

The current insert-or-ignore behaviour means a valid morning sync can never
overwrite a previously inserted corrupt session if one slipped through.

Add a method:
  suspend fun insertOrReplace(session: SleepSession)

This uses OnConflict.REPLACE so a correct full-night session always wins
over a previously stored partial. Use this method in DeviceSyncProcessor
for all sleep session inserts (not insert-or-ignore).

The deduplication key for sleep is driver_id + dateIso. A correct morning
session will always have a later sleepEndMs than any corrupt mid-sleep
session — replacing is always the right behaviour for sleep.

### 3. Add a one-time cleanup

Add a migration or startup task that deletes any existing sleep_sessions
rows where:
  (sleep_end_ms - sleep_start_ms) < 60000
  OR sleep_end_ms = 0
  OR sleep_end_ms <= sleep_start_ms

These are corrupt records from previous syncs. Removing them unblocks the
correct sessions on the next sync.

Show all changed files in full.
```

---

## FIX 2 — STEPS Partial Day Total Frozen Forever

**Priority: CRITICAL** | **Mode: Normal**
Targeted change: define accumulator set, add DAO upsert method, update SyncProcessor routing. No architectural changes.
A mid-day sync writes a partial step count (e.g. 4,200 steps at 14:00).
Because the timestamp is anchored to UTC midnight, the deduplication key
matches the final total synced later that day. The final total is silently
skipped. The step count for that day is frozen at the partial value forever.

---

```
# FIX 2 — Accumulator metrics use insert-or-replace

## Problem
Accumulator metrics (STEPS, CALORIES, DISTANCE, ELEVATION_GAIN,
ELEVATION_LOSS, ACTIVE_CALORIES, BASAL_CALORIES) are anchored to UTC
midnight of their accumulation day. This makes their deduplication key
stable across syncs — which is correct for idempotency. But the current
insert-or-ignore behaviour means the first value written wins. If the user
syncs at 14:00 (partial total) and again at 22:00 (final total), the 22:00
sync is silently skipped. The partial count is frozen forever.

## Fix required

### 1. Define accumulator metric types

Create a set or enum extension that identifies which MetricTypes are
accumulators:

  val ACCUMULATOR_METRICS = setOf(
    MetricType.STEPS,
    MetricType.CALORIES,
    MetricType.ACTIVE_CALORIES,
    MetricType.BASAL_CALORIES,
    MetricType.DISTANCE,
    MetricType.ELEVATION_GAIN,
    MetricType.ELEVATION_LOSS
  )

### 2. MetricReadingDao — add an upsert method

Add a DAO method using OnConflict.REPLACE:
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(reading: MetricReadingEntity)

Keep the existing insert-or-ignore method for point-in-time metrics.

### 3. DeviceSyncProcessor — route by metric type

When inserting MetricReadings in DeviceSyncProcessor, check the metric type:
- If metricType is in ACCUMULATOR_METRICS → use upsert (insert-or-replace)
- Otherwise → use insert-or-ignore

This means the latest synced daily total always wins for accumulators,
while point-in-time readings (HR, HRV, SPO2 etc.) remain immutable once
inserted.

### 4. Clean up existing partial records

There is no reliable way to identify which existing STEPS/CALORIES/DISTANCE
records are partial vs final. Document in the code comment that the fix
is forward-looking — existing partial records remain but will be replaced
on the user's next sync.

Show all changed files in full.
```

---

## FIX 3 — MTU Fragmentation Causes Silent Data Loss

**Priority: CRITICAL** | **Mode: Plan**
The reassembly strategy depends on reading the BLE engine and the Hume Band protocol to determine how packet boundaries are framed. Claude Code needs to read both before deciding the implementation approach.
The app requests MTU 512 but the device negotiates the actual MTU. If the
device grants a small MTU (e.g. 20 bytes — the BLE default), a logical data
record larger than the MTU arrives as multiple notification packets. There is
no reassembly layer. Each fragment goes straight to the WASM parser as a
truncated packet, which either returns garbage or silently returns 0. Records
are lost with no indication.

---

```
# FIX 3 — BLE packet reassembly layer

## Problem
The app requests MTU 512 after connecting. The device negotiates the actual
MTU — which may be as low as 20 bytes. A logical BLE notification larger than
the negotiated MTU is fragmented: it arrives as multiple smaller notification
callbacks on the same characteristic. The current engine passes each raw
notification directly to the WASM parser. A fragment produces either garbled
output or a silent 0 return. Records are lost with no error shown.

## Fix required

### 1. Track the negotiated MTU

In the BLE engine, capture the negotiated MTU in the
onMtuChanged(gatt, mtu, status) callback. Store it as a property on the
active connection. Log the negotiated value at INFO level on every connection:
"MTU negotiated: [mtu] bytes"

### 2. Add a packet reassembly buffer per characteristic

In the BLE engine, maintain a Map<UUID, ByteArray> reassembly buffer —
one accumulation buffer per characteristic UUID.

On every onCharacteristicChanged callback:

  a. Append the incoming bytes to that characteristic's buffer.

  b. Check if the buffer contains a complete packet. What constitutes
     "complete" is device-specific — look at how the driver's sync commands
     and the Hume Band protocol define packet boundaries. Common strategies:
       - Fixed known packet length: complete when buffer.size >= expectedSize
       - Length prefix: first byte(s) encode total packet length
       - Delimiter byte: specific terminal byte signals end of packet
       - MTU-sized fragment with continuation flag in a header byte

     The correct strategy must be determined from the device protocol. Add a
     comment in the engine noting which strategy is used for the current
     driver and why.

  c. When a complete packet is assembled:
     - Pass the full assembled buffer to the WASM parser
     - Clear the buffer for that characteristic

  d. If bytes arrive for a characteristic that already has a partial buffer,
     append — do not replace. This handles multi-fragment records.

  e. On sync completion (user taps Sync), discard all partial buffers and
     log a warning for any characteristic that had a non-empty buffer:
     "Discarding incomplete packet on [characteristicUuid]: [byteCount] bytes"
     This makes truncated records visible in Logcat.

### 3. No change to WASM parsing

The WASM parse functions receive the fully assembled packet as before. This
fix is entirely in the BLE engine layer — the driver contract does not change.

### 4. Document the strategy

Add a comment block at the top of the BLE engine's notification handler
explaining the reassembly strategy and where to change it when adding support
for a new device with different packet framing.

Show all changed files in full.
```

---

## FIX 4 — Sync Summary Always Reports Zero Skipped Records

**Priority: MEDIUM** | **Mode: Normal**
Targeted: add count query to DAO, update SyncSummary fields, update UI string. All files known in advance.
`DeviceSyncProcessor` hardcodes `readingsSkipped = 0` in the sync summary.
The user cannot distinguish a sync that inserted 400 new records from one that
found 400 already-present records. Particularly confusing after re-pairing a
device or debugging data issues.

---

```
# FIX 4 — Accurate sync summary counts

## Problem
SyncSummary.readingsSkipped is hardcoded to 0 in DeviceSyncProcessor.
The actual count of records skipped by insert-or-ignore deduplication is
never computed or surfaced. The user has no way to tell if a sync actually
wrote new data.

## Fix required

### 1. Count skipped records during insert

For point-in-time metric inserts, compare the row count before and after
each insertAll call to determine how many rows were actually inserted vs
skipped:

  val countBefore = metricReadingDao.countForDriverAndTypes(driverId, types)
  metricReadingDao.insertAll(readings)  // insert-or-ignore
  val countAfter = metricReadingDao.countForDriverAndTypes(driverId, types)
  val inserted = countAfter - countBefore
  val skipped = readings.size - inserted

Alternatively, use SQLite's changes() function via a RawQuery after the
insert to get the actual insert count without the two-query approach.

### 2. Update SyncSummary

Replace the hardcoded readingsSkipped = 0 with the computed value.
Also add:
  - readingsInserted: Int  (new records written)
  - sessionsInserted: Int  (new sleep sessions written)
  - activitiesInserted: Int (new activities written)
  - activitiesSkipped: Int

### 3. Surface in the UI

On the Devices screen, after a sync completes show a brief summary:
  "Sync complete — 47 new records, 312 already up to date"

If everything was already present (0 new records), show:
  "Already up to date"

If the device has never synced before (all records new), show:
  "[n] records imported"

Keep the message non-technical — no mention of rows, deduplication, or
database terms.

Show all changed files in full.
```

---

## FIX 5 — Unbounded Growth of raw_device_data and SyncSession Tables

**Priority: LOW** | **Mode: Normal**
Additive only: two prune queries and two post-sync calls. No existing logic changes.
Every sync appends rows to raw_device_data and sync_sessions indefinitely.
On a device that sends 100+ notifications per sync, raw_device_data grows by
hundreds of rows per sync. Over months of daily use this becomes a meaningful
storage burden with no user benefit.

---

```
# FIX 5 — Pruning for raw_device_data and sync_sessions

## Problem
raw_device_data stores all raw BLE payloads with no expiry. sync_sessions
writes a new row on every sync including syncs with 0 new records. Both
tables grow indefinitely with no cleanup.

## Fix required

### 1. raw_device_data — retain last 7 days only

Add a pruning query to RawDeviceDataDao:
  @Query("DELETE FROM raw_device_data WHERE created_at < :cutoffMs")
  suspend fun deleteOlderThan(cutoffMs: Long)

Call this in DeviceSyncProcessor after a successful sync:
  val cutoff = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
  rawDeviceDataDao.deleteOlderThan(cutoff)

7 days is sufficient for reprocessing purposes. Raw payloads older than 7
days have already been processed and the parsed data is in the main tables.

### 2. sync_sessions — retain last 90 days only

Add a pruning query to SyncSessionDao:
  @Query("DELETE FROM sync_sessions WHERE started_at < :cutoffMs")
  suspend fun deleteOlderThan(cutoffMs: Long)

Call this in DeviceSyncProcessor after a successful sync:
  val cutoff = System.currentTimeMillis() - (90 * 24 * 60 * 60 * 1000L)
  syncSessionDao.deleteOlderThan(cutoff)

90 days of sync history is sufficient for any diagnostic purpose.

### 3. Do not prune on failed syncs

Only run pruning after a sync that completes without error. If the sync
throws or is cancelled, skip pruning — the existing data may still be
needed for diagnosis.

### 4. Run pruning in the background

Both pruning calls should run on Dispatchers.IO and not be awaited by the
UI. Fire-and-forget is acceptable — pruning failure is not a sync failure.
Log a warning if pruning throws.

Show all changed files in full.
```

---

## FIX 6 — Sync Gap: Device Overwrites History Without Warning

**Priority: LOW** | **Mode: Normal**
Additive only: last_sync_ms check before sync, warning dialog, post-sync gap log query. No existing logic changes.
If the user hasn't synced in longer than the device's history buffer (typically
7 days for most bands), the oldest data is silently overwritten by the device
firmware. The app has no way to recover lost data, but it can detect the gap
and warn the user.

---

```
# FIX 6 — Sync gap detection and warning

## Problem
Wearable devices store a limited history (typically 7 days). If the user
hasn't synced within that window, the oldest records are silently overwritten
by the device firmware. The app currently has no way to detect this or warn
the user. Data is permanently lost with no indication.

## Fix required

### 1. Store last_sync_ms per device (already exists — verify it is updated)

Confirm that DeviceSyncProcessor updates the device row's last_sync_ms on
every successful sync. If not, add it.

### 2. Calculate expected coverage gap before each sync

When the user initiates a sync, before processing begins:
  val daysSinceLastSync = (System.currentTimeMillis() - device.lastSyncMs)
                          / (24 * 60 * 60 * 1000L)

### 3. Warn if gap exceeds 6 days

If daysSinceLastSync > 6, show a warning dialog before the sync proceeds:

  Title: "Some data may be missing"
  Body: "Your last sync was [N] days ago. This device stores up to 7 days
         of history. Data from before [date] may no longer be on the device."
  Actions: [ "Sync anyway" ]  [ "Cancel" ]

Use 6 days as the threshold (not 7) to give one day of buffer for devices
with slightly shorter retention windows.

### 4. After sync, check for date gaps in inserted data

After a successful sync, query metric_readings for the current device and
check if there are any calendar days in the last 30 days with no readings
at all. If a gap of more than 1 day exists (allowing for rest days with no
HR data), log it at WARN level:
  "Data gap detected for device [driverId]: no readings between [date1]
   and [date2]"

Do not surface this as a UI warning — it may be expected (no sync, or a
rest day). The Logcat entry is sufficient for debugging.

### 5. No data recovery

Do not attempt to reconstruct or estimate missing data. Log and warn only.
The app's principle is to display exactly what the device recorded.

Show all changed files in full.
```

---

## FIX 7 — WasmDriverEngine Not Thread-Safe

**Priority: HIGH** | **Mode: Plan**
Architectural: Mutex must be added to the engine, all three parse paths, the load() function, the BLE callback, and the accumulator type. Claude Code should read all affected files and plan the change sequence before touching anything — a partial fix here is worse than none.
`WasmDriverEngine` is a `@Singleton` with no synchronisation. BLE notification
callbacks can trigger concurrent calls to `parseMetrics` and `parseSleep` on
the same engine. Both calls share the same linear memory region. One call
writes input bytes at offset 0 while the other is mid-parse. Output gets
interleaved writes. Additionally, `load()` can replace the WASM instance
while a parse is in flight, causing parsed records from the wrong driver to
enter the sync accumulator silently.

---

```
# FIX 7 — WasmDriverEngine thread safety

## Problem
WasmDriverEngine is a @Singleton. Its instance field is a plain var with no
synchronisation. Two concurrent BLE notification callbacks (common during
a fast-streaming sync) can call parseMetrics and parseSleep simultaneously.
Both calls share the same linear memory at offset 0 (input) and 0x1000
(output). The results are interleaved or corrupt, with no error thrown.

Additionally, load() sets instance = null then instance = newModule with no
lock. An in-flight parse holds a local reference to the old instance so it
won't crash, but its output is emitted into the accumulator for the new
driver's sync — wrong driver's records mixed in silently.

## Fix required

### 1. Serialise all parse calls with a Mutex

Add a kotlinx.coroutines.sync.Mutex to WasmDriverEngine:
  private val parseMutex = Mutex()

Wrap every call to the WASM instance in parseMutex.withLock { ... }:
  suspend fun parseMetrics(bytes: ByteArray): List<MetricReading> =
    parseMutex.withLock {
      // existing parse logic
    }

  suspend fun parseSleep(bytes: ByteArray): SleepSession? =
    parseMutex.withLock {
      // existing parse logic
    }

  suspend fun parseActivity(bytes: ByteArray): Activity? =
    parseMutex.withLock {
      // existing parse logic
    }

This ensures only one parse call accesses WASM memory at a time. BLE
notification callbacks that arrive during a parse will suspend and queue,
not race.

### 2. Serialise load() with the same Mutex

Wrap load() in the same mutex so it cannot execute while a parse is in
flight, and a parse cannot start while a load is in progress:
  suspend fun load(manifest: WasmDriverManifest) =
    parseMutex.withLock {
      // existing load logic
    }

### 3. Make the BLE notification handler a coroutine

BLE notification callbacks (onCharacteristicChanged) arrive on the Android
BLE thread. If parseMetrics is not already called from a coroutine, wrap the
call in a CoroutineScope launched on Dispatchers.IO so it can suspend on
the mutex without blocking the BLE thread:
  bleScope.launch(Dispatchers.IO) {
    val readings = wasmEngine.parseMetrics(bytes)
    accumulator.add(readings)
  }

Do not block the BLE callback thread — suspending is fine, blocking is not.

### 4. Verify the accumulator is also thread-safe

The sync accumulator (the collection that gathers parsed records until the
user taps Sync) must be a thread-safe collection. Replace any plain
mutableListOf() with Collections.synchronizedList() or a
Channel/ConcurrentLinkedQueue appropriate for the coroutine context. Show
what the current accumulator type is and confirm or fix it.

Show all changed files in full.
```

---

## FIX 8 — Sleep Validation and Stage Integrity

**Priority: MEDIUM** | **Mode: Normal**
Three related validation fixes all in the same code path (DeviceSyncProcessor + sleep merge logic). Well-scoped with no architectural dependencies.
Three separate sleep data integrity issues found in the sync processor and
validation layer. Grouping them into one session as they all touch the same
code path.

---

```
# FIX 8 — Sleep session validation and stage integrity

## Three problems, one session

### Problem A — Negative or zero sleep duration not rejected
If the device sends a malformed sleep record where sleepStartMs >= sleepEndMs
(garbled packet, midnight-boundary bug, RTC reset), the validator does not
explicitly check the relationship between the two timestamps.
ChronoUnit.MINUTES.between(start, end) returns zero or negative. The session
enters the database with zero or negative duration. The UI shows a corrupt
sleep card with no recovery path.

### Problem B — stagesJson stored without JSON validation
The merge code wraps stage parsing in runCatching so malformed JSON doesn't
crash the merge, but the original malformed string is still passed through
and stored in sleep_sessions.stagesJson. When the UI later parses this
string it gets a JSONException with no recovery path.

### Problem C — sleepStartMs = 0 may pass the timestamp floor check
The timestamp floor (after 2020-01-01) is documented for metric readings.
It is not confirmed to apply to sleep session timestamps. A device with an
unset RTC could produce sleepStartMs = 0 (epoch 1970-01-01), inserting a
sleep session anchored to 1970.

## Fix required

### 1. Add explicit sleep session validation in DeviceSyncProcessor

Before inserting any SleepSession, validate all of the following. If any
check fails, discard the session and log the specific reason:

  - sleepStartMs > 1577836800000  (after 2020-01-01 00:00:00 UTC)
  - sleepEndMs > 1577836800000    (after 2020-01-01 00:00:00 UTC)
  - sleepEndMs > sleepStartMs
  - (sleepEndMs - sleepStartMs) >= 60_000  (at least 1 minute)
  - (sleepEndMs - sleepStartMs) <= 86_400_000  (at most 24 hours)
  - sleepEndMs <= System.currentTimeMillis() + 3_600_000  (not more than
    1 hour in the future)

Log a descriptive warning for each rejected session:
  "Rejecting invalid sleep session for [dateIso]: [specific reason]"

### 2. Validate stagesJson before storage

Before inserting a SleepSession that has a non-null stagesJson, attempt to
parse it as a JSON array:
  try {
    JSONArray(session.stagesJson)
  } catch (e: JSONException) {
    // store null instead of the malformed string
    session = session.copy(stagesJson = null)
    Log.w(TAG, "Discarding malformed stagesJson for [dateIso]: ${e.message}")
  }

Also validate each stage object within the array:
  - "stage" field must be one of: DEEP, LIGHT, REM, AWAKE
  - "startMs" and "endMs" must both be present and be Long values
  - startMs < endMs for each stage
  - stage times must fall within sleepStartMs..sleepEndMs

Remove any stage objects that fail these checks. If no valid stages remain,
store null rather than an empty array.

### 3. Fix sleep stage merge to deduplicate before union

The current merge takes the union of all stage arrays from packets sharing
the same (driverId, dateIso). If two packets both contain the complete stage
array (not additive partial segments), the merge doubles every stage.

Fix the merge to deduplicate stages before storing:
  - After taking the union of all stage objects, group by (stage, startMs)
  - Keep only one entry per group (the one with the larger endMs if they
    differ)
  - Sort the deduplicated stages by startMs

This makes the merge correct whether packets send partial or complete stage
arrays.

Show all changed files in full.
```

---

## FIX 9 — No Transaction Across Metric, Sleep, and Activity Inserts

**Priority: MEDIUM** | **Mode: Plan**
Transaction wrapping requires reading the repository layer, the database module, and the SyncSession state machine to understand what currently runs inside and outside transactions, and whether any existing retry or partial-completion logic conflicts with the new transaction boundary.
Metrics, sleep sessions, and activities are inserted in three separate
repository calls in `DeviceSyncProcessor`. A crash or exception between the
first and second insert leaves metrics persisted but sleep absent, with the
`SyncSession` stuck at `PARTIAL`. On the next sync, metrics deduplicate
safely but the `PARTIAL` status is never resolved — there is no retry
mechanism and the user sees a permanently incomplete sync in their history.

---

```
# FIX 9 — Wrap sync inserts in a single database transaction

## Problem
DeviceSyncProcessor inserts metrics, sleep sessions, and activities in
three separate repository calls with no wrapping transaction. A crash or
cancellation between any two calls leaves the database in a partially
written state. The SyncSession row is marked PARTIAL and never updated —
there is no mechanism to resolve it on retry because the next sync
deduplicates safely but does not revisit the SyncSession status.

## Fix required

### 1. Wrap all three inserts in a single Room transaction

In DeviceSyncProcessor, combine the three insert calls inside a single
withTransaction block:

  appDatabase.withTransaction {
    metricRepository.insertAll(readings)
    sleepRepository.insertOrReplace(session)
    activityRepository.insertAll(activities)
  }

If any insert throws, the transaction rolls back entirely. The SyncSession
is then marked FAILED, not PARTIAL. On the next sync, all three inserts
retry cleanly with full deduplication.

### 2. Remove the PARTIAL SyncSession status if it no longer serves a purpose

If the only path to PARTIAL was a mid-sync crash between separate inserts,
and that path is now closed by the transaction, the PARTIAL status can be
removed. A sync is either COMPLETED or FAILED — there is no meaningful
in-between state that the user or the system can act on.

If PARTIAL is still needed for other cases (e.g. truncated BLE stream),
keep the status but document exactly which conditions produce it.

### 3. Handle FAILED SyncSession on next sync

When a sync begins, check if the most recent SyncSession for this device
has status FAILED. If so, log it at INFO level:
  "Retrying after failed sync at [timestamp]"

Do not show this to the user — it is expected behaviour after a crash. The
retry will insert everything the failed sync missed.

### 4. Clean up orphaned PARTIAL sessions

Add a startup task that marks any SyncSession rows older than 1 hour with
status PARTIAL as FAILED. A sync that has been PARTIAL for more than an
hour was interrupted and will never self-resolve.

Show all changed files in full.
```

---

## FIX 10 — Battery Level Uses recordedAt Instead of Created Wall Clock

**Priority: LOW** | **Mode: Normal**
Single field selection change in DeviceSyncProcessor. Confirm accumulator order and update one line.
`DeviceSyncProcessor` selects the "most recent" battery reading using
`maxByOrNull { it.recordedAt }`. If the device has a wrong-future timestamp
on any historical record (RTC drift), that record becomes the "latest" and
the UI shows a stale or incorrect battery percentage. Battery level should
always reflect the most recently *received* reading, not the reading with
the highest device timestamp.

---

```
# FIX 10 — Battery reading selection

## Problem
DeviceSyncProcessor picks the battery reading to store using:
  maxByOrNull { it.recordedAt }

recordedAt is the device timestamp. If any historical record has a
future timestamp (RTC drift, clock reset), it becomes the "latest"
and the device shows a stale battery percentage. Battery level is a
present-state value — it should always reflect the reading that arrived
most recently during the current sync, not the one with the highest
device-reported timestamp.

## Fix required

### 1. Select battery by insertion order, not recordedAt

Battery readings arrive during the sync in BLE notification order. The
last battery reading received in the sync is the most current.

Change the selection in DeviceSyncProcessor to use the last item in the
received battery readings list rather than the one with the highest
recordedAt:
  val latestBattery = batteryReadings.lastOrNull()

If the accumulator preserves insertion order (a List), lastOrNull() gives
the most recently received reading. Confirm the accumulator preserves order
and document this assumption.

### 2. For the battery column in the devices table

When storing battery level to devices.last_battery_pct, use the value from
latestBattery as above. Do not write battery readings to metric_readings —
battery is device metadata, not a user health metric.

Confirm that battery readings are currently excluded from metric_readings
inserts. If not, add the exclusion: filter out MetricType.BATTERY before
the insertAll call for metric readings.

Show all changed files in full.
```

---

## FIX 11 — Driver Version Bump Cannot Correct Historical Records

**Priority: MEDIUM** | **Mode: Plan**
New feature (Reprocess) spans multiple new components: a new action in the Devices screen, a new processing path in DeviceSyncProcessor, a new DAO query, and progress UI. Needs a plan before any code is written to avoid building the UI before the data path exists.
The deduplication key uses `driver_id` but not `driver_version`. If a driver
update fixes a timestamp bug, re-syncing writes corrected records that share
the same key as the old wrong records. The corrected records are silently
dropped. There is no way to force a re-import of corrected data short of
wiping the database.

---

```
# FIX 11 — Driver reprocess command for corrected historical data

## Problem
The deduplication key for metric readings is driver_id + metric_type +
recorded_at. It does not include driver_version. If a driver update fixes
a timestamp or parsing bug, the corrected records from a re-sync share the
same key as the original wrong records and are silently skipped. The user's
historical data remains wrong with no fix available short of a full database
reset.

## Fix required

### 1. Add a "Reprocess from raw data" action to the Devices screen

The raw_device_data table stores all BLE payloads from every past sync.
Add an action in the Devices screen (long press on device, or a menu item):
  "Reprocess all synced data"

This action:
  a. Loads all raw_device_data rows for this device, ordered by created_at
  b. Re-runs them through the current loaded driver's WASM parser
  c. Uses insert-or-replace (not insert-or-ignore) for ALL metric types
     during reprocessing — this allows corrected records to overwrite wrong
     ones
  d. Re-runs DailySummaryWorker for all affected dates after reprocessing
  e. Shows a progress indicator and a completion summary

### 2. Scope reprocessing to a date range (optional but recommended)

Allow the user to limit reprocessing to the last N days (default: 30) to
avoid reprocessing months of data unnecessarily. A driver fix typically
only affects records from a specific period.

### 3. Confirm before running

Show a confirmation dialog:
  "This will reprocess all raw data from [device] using the current driver.
   Corrected values will replace existing records. This may take a minute."
  Actions: [ "Reprocess" ] [ "Cancel" ]

### 4. Document the raw data retention dependency

Reprocessing only works while raw data exists. raw_device_data is pruned to
7 days (FIX 5). Document in the Devices screen that reprocessing covers
only the last 7 days of synced data. For older corrections, the user must
wait for the device to resync naturally as new days are added.

Show all changed files in full.
```

---

## FIX 12 — Sleep Merge Ignores Existing Database Record

**Priority: HIGH** | **Mode: Normal**
Targeted: add one DAO query, pass existing record into the merge function, confirm insert-or-replace is in place from FIX 1. All files known. Run FIX 1 before this.

This fix covers two distinct failure scenarios that share the same root cause:

1. **Within a single sync** — the device sends two packets for the same night in one sync (e.g. one per stage block). The in-memory merge handles this correctly already.
2. **Across syncs** — sync 1 stores a partial session (incomplete stages, truncated end time). Sync 2 delivers the full session. The in-memory merge produces a correct merged result but `insert-or-ignore` silently drops it because the `(driverId, dateIso)` key already exists. The partial record from sync 1 persists permanently. This is the case that is currently broken and requires reading the existing DB record before merging.

---

```
# FIX 12 — Sleep merge must include the existing database record

## Problem
mergeSleepSessions merges only sessions from the current sync payload. It
does not fetch the existing record for (driverId, dateIso) from the database
before merging. The result:

  Sync 1 delivers a partial session → stored in DB (start + LIGHT stage only)
  Sync 2 delivers the full session → mergeSleepSessions produces a good
    merged record, but insert-or-ignore sees the key already exists and
    drops it silently. The partial record from sync 1 stays forever.

## Fix required

### 1. Fetch existing DB record before merge

In DeviceSyncProcessor, before calling mergeSleepSessions for a given
(driverId, dateIso) group, fetch the existing record from the database:

  val existing = sleepSessionDao.getByDriverAndDate(driverId, dateIso)

If a record exists, include it as the first input to the merge — before the
incoming sessions from this sync. The merge then produces a result that is
a superset of both what was stored and what arrived:

  val merged = mergeSleepSessions(
    listOfNotNull(existing?.toDomain()) + incomingSessions
  )

### 2. Always use insert-or-replace for sleep after merge

The merged result should always replace whatever is in the database, because
it is always at least as complete as the existing record. Change all sleep
session inserts to use OnConflict.REPLACE (this was introduced in FIX 1 —
confirm it is in place).

### 3. Add SleepSessionDao.getByDriverAndDate

If not already present, add a DAO query:
  @Query("""
    SELECT * FROM sleep_sessions
    WHERE driver_id = :driverId AND date = :dateIso
    LIMIT 1
  """)
  suspend fun getByDriverAndDate(driverId: String, dateIso: String): SleepSessionEntity?

### 4. Verify the merge with a specific test scenario

After implementing, verify the following scenario manually using the debug
seeder or by inserting test rows directly into the database:

  1. Insert a sleep session for "2026-06-09" with only LIGHT stages and an
     early sleepEndMs
  2. Trigger a sync that delivers the same night with full stages and the
     correct sleepEndMs
  3. Confirm the resulting DB record has the correct sleepEndMs and the
     full stage set — not the partial record from step 1

Show all changed files in full.
```

---

## FIX 13 — Android 7-Notification Queue Cap Causes Silent Packet Loss

**Priority: HIGH** | **Mode: Plan**
Architectural change: replaces the current BLE notification handling with a Channel-based consumer. Needs to read the BLE engine in full before deciding where the Channel sits relative to the reassembly buffer (FIX 3) and the WASM mutex (FIX 7). Run FIX 3 and FIX 7 before this.
Android's BLE stack silently drops incoming notifications if the app has not
processed the previous 7. If the device bursts data faster than the coroutine
consuming the queue, packets are lost at the OS layer — no error, no log, no
retry. High-throughput syncs (long history, dense HR samples) are most at risk.

---

```
# FIX 13 — BLE notification back-pressure and queue management

## Problem
Android's BLE stack maintains an internal notification queue of 7 packets
per characteristic. If the app does not consume notifications fast enough
(e.g. because WASM parsing is slow, or the coroutine dispatcher is saturated),
the OS silently drops incoming packets. There is no error, no log entry, and
no way for the app to detect the loss after the fact.

This is an Android platform constraint. The fix is to consume notifications
as fast as possible and never block the BLE callback thread.

## Fix required

### 1. Confirm the BLE callback never blocks

The onCharacteristicChanged callback runs on Android's BLE thread. Any work
done synchronously in this callback delays the next notification callback.
Review the callback and ensure it does nothing except:
  a. Append the raw bytes to the reassembly buffer (FIX 3)
  b. Post a message to a Channel or launch a coroutine for async processing

No WASM parsing, no JSON deserialisation, no database access should happen
on the BLE callback thread directly.

### 2. Use a Channel with a large buffer for the notification queue

Replace any direct coroutine launch with a Channel-based consumer:

  private val notificationChannel = Channel<Pair<UUID, ByteArray>>(
    capacity = 512,
    onBufferOverflow = BufferOverflow.DROP_OLDEST
  )

In onCharacteristicChanged:
  notificationChannel.trySend(Pair(characteristicUuid, bytes))

Launch a single consumer coroutine on Dispatchers.IO that reads from the
channel and processes notifications sequentially:
  bleScope.launch(Dispatchers.IO) {
    for ((uuid, bytes) in notificationChannel) {
      processNotification(uuid, bytes)  // reassembly + WASM parse
    }
  }

BufferOverflow.DROP_OLDEST means: if the channel is full (512 items queued),
the oldest unprocessed notification is dropped rather than blocking the BLE
thread. Log a warning when this occurs:
  Log.w(TAG, "Notification channel full — dropping oldest packet")

512 is a generous buffer that covers most sync scenarios. Adjust based on
observed peak notification rates.

### 3. Log channel depth periodically during sync

Every 50 notifications processed, log the current channel size:
  if (processedCount % 50 == 0) {
    Log.d(TAG, "Notification channel depth: ${notificationChannel.size}")
  }

This makes it visible in Logcat when the consumer is falling behind, without
adding overhead to every packet.

### 4. No fix for OS-level drops

Packets dropped by the Android OS before reaching onCharacteristicChanged
are unrecoverable. The Channel fix prevents drops caused by slow processing,
but cannot recover drops caused by the OS queue being full before the
callback fires. Document this limitation in the BLE engine source.

Show all changed files in full.
```

---

## FIX 14 — Reconnect After Drop Re-runs Sync Commands, Doubles Accumulator

**Priority: MEDIUM** | **Mode: Normal**
Targeted: swap three list accumulators for deduplicating maps. Self-contained with no pipeline dependencies beyond FIX 13 being in place first.
If the BLE connection drops mid-sync and the app reconnects, the 7 sync
commands fire again. The device streams all history from the beginning. The
accumulator receives duplicate packets. Deduplication at the DB layer is safe,
but the accumulator holds every record twice in RAM — doubling memory usage and
processing time for the remainder of that sync.

---

```
# FIX 14 — Deduplicate the in-memory accumulator on reconnect

## Problem
On BLE reconnect mid-sync, the device restarts its data stream from the
beginning. The in-memory accumulator receives all previously accumulated
packets a second time. DB-level deduplication handles this at insert time,
but RAM usage and WASM processing time double for that sync with no benefit.

## Fix required

### 1. Deduplicate the metric readings accumulator in memory

The accumulator for metric readings should use a deduplication key that
matches the DB key: (metricType, recordedAtMs).

Replace the plain list accumulator with a LinkedHashMap keyed by this pair:
  private val metricsAccumulator =
    LinkedHashMap<Pair<MetricType, Long>, MetricReading>()

On each incoming reading:
  metricsAccumulator[Pair(reading.metricType, reading.recordedAtMs)] = reading

This replaces any previously accumulated reading with the same key, keeping
only the latest received copy. Final insert uses metricsAccumulator.values.

### 2. Deduplicate the sleep sessions accumulator in memory

Sleep sessions accumulate by (driverId, dateIso). The merge step already
handles combining sessions for the same night — the in-memory dedup here
just prevents the same packet from being fed into the merge twice.

Track received session packet hashes (or sleepStartMs values) in a Set and
skip packets already seen:
  private val seenSleepStartMs = HashSet<Long>()

  if (seenSleepStartMs.add(session.sleepStartMs)) {
    sleepAccumulator.add(session)
  }

### 3. Deduplicate the activities accumulator in memory

Activities deduplicate by startTimeMs. Use a LinkedHashMap:
  private val activitiesAccumulator = LinkedHashMap<Long, Activity>()
  activitiesAccumulator[activity.startTimeMs] = activity

### 4. Reset accumulators on sync start, not on reconnect

Do not clear accumulators on reconnect — the data already accumulated is
valid and should be kept. Only clear accumulators when a new sync session
begins (user taps Sync and it completes, or user cancels and starts fresh).

### 5. Log deduplicated count after reconnect

After a reconnect, log how many packets were already present in the
accumulator before the re-stream began:
  Log.i(TAG, "Reconnect: accumulator has [n] existing readings — 
    duplicates from re-stream will be discarded in memory")

Show all changed files in full.
```

---

## FIX 15 — User Can Tap Sync Before Device Finishes Streaming

**Priority: MEDIUM** | **Mode: Normal**
Additive UI change: quiescence timer, Sync button state, live packet counter, early-sync warning dialog. No changes to the data pipeline.
There is no end-of-stream signal from the device. The user can tap Sync at
any point after connecting. `DeviceSyncProcessor.process()` runs on whatever
arrived so far. Remaining in-flight notifications are discarded or go into an
orphaned accumulator. The sync reports SUCCESS on incomplete data with no
warning.

---

```
# FIX 15 — Sync readiness indication and stream completion heuristic

## Problem
The device has no explicit "done streaming" signal. The user can tap Sync
immediately after connecting, receiving only a fraction of the available
data. The sync reports SUCCESS with no indication that data may be missing.

A perfect fix would require a device-level end-of-transmission marker, which
cannot be added to all devices. The fix here is a best-effort heuristic plus
a UI affordance that discourages premature Sync taps.

## Fix required

### 1. Show live packet count during sync streaming

While BLE notifications are arriving, display a live counter on the Devices
screen below the device status:
  "Receiving data... 47 packets"

Update this count every time a notification is processed. This gives the
user a visible signal that data is still flowing and discourages tapping
Sync prematurely.

### 2. Add a minimum quiescence period before Sync is considered ready

Track the timestamp of the most recently received notification. The Sync
button becomes fully active (or changes from a muted to a bold state) only
when no notification has arrived for a configurable quiescence period:
  const val STREAM_QUIESCENCE_MS = 3_000L  // 3 seconds

If no packet has arrived in the last 3 seconds, the stream is likely
complete. The Sync button becomes fully active and the counter changes to:
  "Ready to sync — 47 packets received"

If the user taps Sync before the quiescence period, still allow it — do not
block — but show a brief warning:
  "Sync may be incomplete — device may still be sending data."
  Actions: [ "Sync anyway" ] [ "Wait" ]

### 3. Log the packet count and quiescence state in the sync summary

Add to SyncSession:
  - packetsReceived: Int
  - syncedBeforeQuiescence: Boolean  (true if user tapped early)

Log both fields. Surface syncedBeforeQuiescence in the sync history entry
as a subtle indicator: a small warning icon or "(early sync)" label.

### 4. Do not change the SUCCESS/FAILED status semantics

A sync that processes valid data is still SUCCESS even if the stream was
cut short. The warning is informational only — it does not change the sync
outcome. The user can always sync again to pick up remaining data.

Show all changed files in full.
```

---

## FIX 16 — Activity Deduplication Broken by 1-Second RTC Drift

**Priority: MEDIUM** | **Mode: Normal**
Targeted: drift-tolerant window query in ActivityDao, routing logic in DeviceSyncProcessor, duration validation. All files known in advance.
Activities deduplicate by `driver_id + startTimeMs`. If the device's RTC drifts
by even 1 second between syncs, the same activity's `startTimeMs` shifts by
1000ms, producing a different deduplication key. The second sync inserts a
duplicate. The user sees the same run listed twice.

---

```
# FIX 16 — Activity deduplication with RTC drift tolerance

## Problem
Activity deduplication uses driver_id + startTimeMs as the key. A 1-second
RTC drift between syncs shifts startTimeMs by 1000ms, producing a different
key. The existing record is not matched and the duplicate is inserted. The
user sees the same run twice.

## Fix required

### 1. Add a drift-tolerant uniqueness check before insert

Before inserting an activity, query for any existing activity from the same
driver within a ±30 second window of the incoming startTimeMs:

  @Query("""
    SELECT * FROM activities
    WHERE driver_id = :driverId
    AND start_time_ms BETWEEN :windowStart AND :windowEnd
    LIMIT 1
  """)
  suspend fun findNear(
    driverId: String,
    windowStart: Long,
    windowEnd: Long
  ): ActivityEntity?

In DeviceSyncProcessor, for each incoming activity:
  val window = 30_000L  // 30 seconds
  val existing = activityDao.findNear(
    driverId = activity.driverId,
    windowStart = activity.startTimeMs - window,
    windowEnd = activity.startTimeMs + window
  )

  if (existing != null) {
    // Same activity, different timestamp due to RTC drift
    // Keep whichever has the longer duration as the canonical record
    if (activity.durationMinutes > existing.durationMinutes) {
      activityDao.replace(activity)
    }
    // else: skip the incoming record, existing is fine
  } else {
    activityDao.insert(activity)  // insert-or-ignore for exact key match
  }

### 2. Log when drift-match deduplication fires

  Log.d(TAG, "Activity drift-match: incoming startMs=${activity.startTimeMs}
    matched existing startMs=${existing.startTimeMs}
    delta=${activity.startTimeMs - existing.startTimeMs}ms")

This makes RTC drift visible in Logcat without surfacing it to the user.

### 3. Validate activity duration against start/end times

Before inserting any activity, check that durationMinutes is consistent with
the startTimeMs and endTimeMs:
  val derivedMinutes = ((activity.endTimeMs - activity.startTimeMs) /
                        60_000L).toInt()
  val tolerance = 2  // minutes

  if (abs(derivedMinutes - activity.durationMinutes) > tolerance) {
    Log.w(TAG, "Activity duration mismatch: stored=${activity.durationMinutes}
      derived=$derivedMinutes — using derived value")
    activity = activity.copy(durationMinutes = derivedMinutes)
  }

Always store the derived value when it disagrees with the reported value.
The start/end timestamps are the ground truth; durationMinutes is derived.

Show all changed files in full.
```

---

## FIX 17 — SyncValidator Does Not Apply Timestamp Checks to Sleep and Activities

**Priority: MEDIUM** | **Mode: Plan**
Audit-first: the scope of what SyncValidator currently checks for sleep and activities is unknown. Claude Code needs to read the validator source and report gaps before any fixes are written. The fix scope depends entirely on the audit findings.
The validation rules in the authoring guide explicitly list timestamp floor
and ceiling checks for metric readings. Whether `SyncValidator.validateSessions()`
and `validateActivities()` apply the same checks is unconfirmed. A sleep session
with `sleepStartMs = 0` (unset RTC) or an activity with `startTimeMs` in 2019
may pass validation entirely and enter the database.

---

```
# FIX 17 — Extend SyncValidator to cover sleep and activity timestamps

## Problem
SyncValidator applies timestamp floor (after 2020-01-01) and ceiling (not
more than 1 hour in the future) checks to metric readings. It is not
confirmed that validateSessions() and validateActivities() apply equivalent
checks. Records with epoch-zero timestamps or future timestamps can enter
the database silently.

## Fix required

### 1. Audit SyncValidator — report what each validate method currently checks

Read SyncValidator in full. For each of the three validate methods
(validateReadings, validateSessions, validateActivities), list exactly
which fields are checked and which are not. Report this before making
any changes.

### 2. Apply consistent validation rules to all three data types

After the audit, ensure all three methods enforce:

  Metric readings (existing — confirm):
  - value not NaN or Infinite
  - recordedAtMs > 1577836800000  (2020-01-01 UTC)
  - recordedAtMs < System.currentTimeMillis() + 3_600_000  (1 hour future)
  - unit not blank
  - metricType is a known value

  Sleep sessions (add if missing):
  - sleepStartMs > 1577836800000
  - sleepEndMs > 1577836800000
  - sleepEndMs > sleepStartMs
  - (sleepEndMs - sleepStartMs) >= 60_000  (at least 1 minute)
  - (sleepEndMs - sleepStartMs) <= 86_400_000  (at most 24 hours)
  - sleepEndMs < System.currentTimeMillis() + 3_600_000
  - dateIso matches the UTC date of sleepEndMs — if they disagree,
    correct dateIso rather than rejecting (log a warning)

  Activities (add if missing):
  - startTimeMs > 1577836800000
  - endTimeMs > startTimeMs
  - durationMinutes > 0
  - startTimeMs < System.currentTimeMillis() + 3_600_000
  - deviceName not blank

### 3. Log a descriptive warning for every rejected record

For each rejected record, log the field that failed and the offending value:
  "Rejecting sleep session [dateIso]: sleepStartMs=0 fails floor check"
  "Rejecting activity [deviceName]: startTimeMs=1234567 fails floor check"

Do not throw — validation failures are skipped records, not sync failures.

Show all changed files in full.
```

---

## FIX 18 — Process Death Loses Accumulated Packets

**Priority: MEDIUM** | **Mode: Plan**
Architectural: moves raw_device_data writes from sync completion to packet arrival, adds a new recovery flow for PARTIAL sessions on app restart, and introduces a new SyncSession lifecycle path. Multiple components interact — needs a plan to sequence correctly and avoid conflicts with FIX 5 (pruning) and FIX 9 (transactions).
Android can kill the app process during BLE notification accumulation. The
`SyncSession` row exists with status `PARTIAL` but all accumulated packets are
in heap memory and are lost. On restart the accumulator is empty. The user must
reconnect and re-sync from scratch. The `PARTIAL` session record is never
resolved, accumulating as a permanent artefact in sync history.

---

```
# FIX 18 — Persist accumulator to survive process death

## Problem
All BLE notifications are held in a heap accumulator until the user taps
Sync. Android can kill the app process at any time during accumulation (low
memory, user force-stop, background limits). The SyncSession row is marked
PARTIAL but the accumulated data is gone. The user must reconnect and
re-sync. The PARTIAL session is never resolved.

A full persistent accumulator (writing every raw packet to the database as
it arrives) is the complete fix but has storage overhead. This prompt
implements a practical middle ground.

## Fix required

### 1. Write raw packets to raw_device_data immediately on arrival

raw_device_data already stores BLE payloads for reprocessing purposes.
Currently it is written during sync completion. Change it to write on
arrival — as each notification is processed, persist the raw bytes:

  rawDeviceDataDao.insert(
    RawDeviceDataEntity(
      driverId = activeDriver.id,
      characteristicUuid = uuid.toString(),
      payload = bytes,
      createdAt = System.currentTimeMillis(),
      syncSessionId = currentSyncSessionId
    )
  )

This is the only change needed for persistence. The data is now on disk
as it arrives.

### 2. On restart after PARTIAL SyncSession, offer to complete from raw data

On app start, check for any SyncSession rows with status PARTIAL that are
less than 24 hours old:

  val partialSessions = syncSessionDao.getRecentPartial(cutoffMs)

If any are found, show a notification or banner on the Devices screen:
  "A previous sync was interrupted. Tap to complete it from saved data."

Tapping this runs DeviceSyncProcessor.processFromRaw(sessionId), which:
  a. Loads all raw_device_data rows for that syncSessionId
  b. Re-runs them through the current WASM parser
  c. Inserts results using the normal deduplication logic
  d. Marks the SyncSession as COMPLETED

### 3. Clean up PARTIAL sessions older than 24 hours

PARTIAL sessions older than 24 hours cannot be usefully recovered (the
device has moved on, the raw data may be pruned, the user should just
re-sync). Mark them FAILED and log:
  "Expiring stale PARTIAL SyncSession [id] from [timestamp]"

Run this check at app start before surfacing the recovery offer.

### 4. Do not write raw_device_data on the BLE callback thread

The raw_device_data insert must happen on Dispatchers.IO. The BLE callback
posts to the notification Channel (FIX 13); the Channel consumer coroutine
runs on Dispatchers.IO and performs the insert there. No database access
on the BLE thread.

Show all changed files in full.
```

---

## FIX 19 — GATT Service Cache Stale After Firmware Update

**Priority: LOW-MEDIUM** | **Mode: Normal**
Additive: quiescence detection timeout, reflection call attempt, user-facing message. No changes to the existing connection or sync logic.
Android caches GATT service and characteristic handles per device address.
If the device firmware updates and changes characteristic UUIDs or handle
assignments, Android serves the stale cached layout. Writes go to the wrong
handle silently — no error, data does not flow. Clearing the cache requires
either a hidden Android API or a full Bluetooth adapter reset. The driver
has no mechanism to detect or trigger a refresh.

---

```
# FIX 19 — GATT cache refresh on connection failure

## Problem
Android caches GATT service/characteristic handles per device address. A
firmware update that changes handles or UUIDs causes writes to go to the
wrong handle silently. The app gets no error — sync commands fire, no data
arrives. The only fixes are: clear the GATT cache via a hidden API, or
reset the Bluetooth adapter.

## Fix required

### 1. Detect a "silent failure" sync

After the sync command sequence completes and a configurable timeout passes
(e.g. 15 seconds) with no notifications received, flag the sync as
potentially stale-cache affected:
  Log.w(TAG, "No notifications received after [timeout]ms — 
    possible GATT cache issue")

### 2. Attempt GATT cache refresh via reflection

Android exposes BluetoothGatt.refresh() as a hidden API. Attempt to call it
via reflection when a silent failure is detected:

  fun BluetoothGatt.refreshCache(): Boolean {
    return try {
      val refresh = this.javaClass.getMethod("refresh")
      refresh.invoke(this) as Boolean
    } catch (e: Exception) {
      Log.w(TAG, "GATT refresh not available: ${e.message}")
      false
    }
  }

If refresh() succeeds, disconnect and reconnect. The reconnect will
re-discover services with a fresh cache. Log:
  "GATT cache refreshed — reconnecting"

If refresh() fails (API not available on this Android version), fall
through to step 3.

### 3. Surface a manual fix to the user if refresh fails

If no notifications arrive after reconnect, show a message on the Devices
screen:
  "Having trouble connecting to [device]. Try turning Bluetooth off and
   on, then reconnect."

This is the user-facing equivalent of an adapter reset and resolves stale
cache on all Android versions without requiring hidden APIs.

### 4. Document the limitation

Add a comment in the BLE engine explaining the GATT cache problem, the
reflection approach, and its limitations. This is a known Android BLE
issue that affects all apps — not a bug specific to this implementation.

Show all changed files in full.
```

---

## FIX 20 — Memory Layout Spec Change: Add Metadata Header Region

**Priority: HIGH** | **Mode: Plan**
Breaking spec change touching the memory layout contract, all WASM call sites, and specVersion routing logic. Also fixes an active code/spec mismatch: `WasmDriverEngine` currently has `IN_OFFSET = 0` but the spec now says 16. Any partial implementation mixing v1 and v2 call conventions silently corrupts parse results. Read `WasmDriverEngine` in full before touching anything.

---

```
# FIX 20 — WASM metadata header: syncStartMs, utcOffsetMinutes, and IN_OFFSET fix

## Active bug
WasmDriverEngine has private const val IN_OFFSET = 0 and calls parse
functions with (IN_OFFSET.toLong(), byteLength). The spec now defines
input at offset 16 (0x0010). The engine and spec currently disagree.
Every WASM parse call is passing the wrong offset — drivers reading from
param 1 are reading the metadata region, not BLE data. This must be fixed
as part of this prompt along with the specVersion routing.

## Full fix required

### 1. Update WasmDriverEngine — write full metadata before every call

Capture syncStartMs once when sync begins (when the first sync command
fires, before any data arrives):
  val syncStartMs = System.currentTimeMillis()

Compute utcOffsetMinutes from the device's timezone:
  val utcOffsetMinutes = TimeZone.getDefault()
    .getOffset(System.currentTimeMillis()) / 60_000

Before every call to parseMetrics, parseSleep, or parseActivity:
  // Write syncStartMs as i64 little-endian at offset 0
  wasmMemory.writeLong(offset = 0, value = syncStartMs)
  // Write utcOffsetMinutes as i16 little-endian at offset 8
  wasmMemory.writeShort(offset = 8, value = utcOffsetMinutes.toShort())
  // Zero bytes 10–15 (reserved)
  wasmMemory.zeroRange(from = 10, to = 16)
  // Write packet bytes at offset 16
  wasmMemory.writeBytes(offset = 16, bytes = packetBytes)
  // Zero bytes 16+byteLength through 16+previousByteLength-1
  wasmMemory.zeroRange(
    from = 16 + packetBytes.size,
    to = 16 + previousPacketSize
  )

Track previousPacketSize across calls to correctly zero stale input bytes.

### 2. Update IN_OFFSET constant and all call sites

Change:
  private const val IN_OFFSET = 0
To:
  private const val IN_OFFSET_V2 = 16
  private const val IN_OFFSET_V1 = 0  // legacy, for specVersion = "1" drivers

All calls use IN_OFFSET_V2 for spec v2 drivers, IN_OFFSET_V1 for spec v1
drivers (see step 3).

### 3. Add specVersion routing

Add a field to WasmDriverManifest:
  val specVersion: String = "1"  // default for backwards compatibility

In WasmDriverEngine, select the input offset based on specVersion:
  val inputOffset = when (manifest.specVersion) {
    "2"  -> IN_OFFSET_V2
    else -> IN_OFFSET_V1
  }

For specVersion = "1" drivers, skip writing metadata (do not write
syncStartMs or utcOffsetMinutes at offset 0 — the driver doesn't expect
them and reads from offset 0 directly for BLE data).

Log a warning for specVersion = "1" drivers:
  "Driver [id] uses spec v1 layout — metadata not available.
   Update to specVersion 2 for syncStartMs and utcOffsetMinutes support."

### 4. No guide changes needed

The guide already specifies the correct layout including utcOffsetMinutes
at bytes 8–9 and the AssemblyScript read examples. Confirm the
implementation matches the guide exactly.

Show all changed files in full.
```

---

## FIX 21 — Accumulator Replace Needs Value Guard

**Priority: HIGH** | **Mode: Normal**
Targeted: add a guarded upsert method to MetricReadingDao, update SyncProcessor routing. Well-scoped with no architectural dependencies. Run FIX 2 before this — it establishes the ACCUMULATOR_METRICS set this fix builds on.
`insert-or-replace` for accumulator metrics (STEPS, CALORIES, DISTANCE) allows a
corrupt re-sync that produces a lower value (e.g. 0 steps from a partial packet) to
silently overwrite a correct stored value. There is no recovery path. The fix is a
value guard: only replace when the incoming value is greater than or equal to the
stored value.

---

```
# FIX 21 — Value guard for accumulator metric insert-or-replace

## Problem
insert-or-replace for accumulators allows a correct stored value to be
overwritten by a corrupt or partial re-sync value. A re-sync that produces
0 steps for a completed day silently replaces the correct 8,500. There is
no recovery path short of a database wipe.

## Fix required

### 1. Add a guarded upsert method to MetricReadingDao

Replace the plain upsert with a conditional update:

  @Transaction
  suspend fun upsertAccumulator(reading: MetricReadingEntity) {
    val existing = getByDriverTypeAndDate(
      reading.driverId,
      reading.metricType,
      reading.recordedAt  // UTC midnight — same key
    )
    if (existing == null) {
      insert(reading)  // insert-or-ignore for first write
    } else if (reading.value >= existing.value) {
      replace(reading)  // only replace if new value is >= stored
    } else {
      Log.w(TAG, "Accumulator value guard: skipping ${reading.metricType} " +
        "incoming=${reading.value} stored=${existing.value} — " +
        "incoming value is lower, keeping stored record")
    }
  }

### 2. Apply the guard only to accumulator metrics

Use this guarded upsert only for metrics in ACCUMULATOR_METRICS (STEPS,
CALORIES, ACTIVE_CALORIES, BASAL_CALORIES, DISTANCE, ELEVATION_GAIN,
ELEVATION_LOSS). Point-in-time metrics continue to use insert-or-ignore.

### 3. Update SyncSummary to reflect guarded skips

Add a field:
  accumulatorValuesGuarded: Int

Increment it each time the value guard fires (incoming < stored). Surface
this in Logcat as part of the sync summary. Do not show it in the UI —
it is a diagnostic signal, not a user-facing event.

Show all changed files in full.
```

---

## FIX 22 — recordsImported Overcounts on Accumulator Re-syncs

**Priority: LOW** | **Mode: Normal**
Targeted: expand SyncSummary fields, update the guarded upsert to track the three outcome types, update the UI summary string. Run FIX 21 before this — it introduces the guarded upsert this fix instruments.
With insert-or-replace, every accumulator re-sync counts as a write at the DB
level even when no genuinely new data arrived. `recordsImported` reports > 0 on
the second sync of the same completed day, misleading the user into thinking new
data was received.

---

```
# FIX 22 — Accurate recordsImported for accumulator metrics

## Problem
With insert-or-replace, every accumulator update counts as a DB write.
recordsImported counts rows written, so a second sync of the same
completed day reports recordsImported > 0 despite no new data arriving.
The checklist item "syncing twice produces no records imported" is violated.

## Fix required

### 1. Distinguish genuinely new records from updates

Track separately:
  - newRecordsInserted: rows inserted for the first time (no prior record
    with that dedup key)
  - accumulatorUpdates: rows replaced where the incoming value was higher
    than the stored value (a real update — the total increased)
  - accumulatorNoChange: rows where incoming value equalled stored value
    (same data re-synced — not new, not an update)

### 2. Update SyncSummary fields

  data class SyncSummary(
    val newRecordsInserted: Int,    // first-time inserts
    val accumulatorUpdates: Int,    // accumulator increased
    val accumulatorNoChange: Int,   // re-sync of unchanged data
    val accumulatorGuarded: Int,    // incoming lower than stored, skipped
    val readingsSkipped: Int,       // point-in-time already present
    val sessionsInserted: Int,
    val activitiesInserted: Int,
    val activitiesSkipped: Int
  )

### 3. Surface correctly in the UI

The Devices screen sync summary should report:
  - If newRecordsInserted > 0 or accumulatorUpdates > 0:
    "Sync complete — [n] new records"
  - If all zeros:
    "Already up to date"

accumulatorNoChange and readingsSkipped are internal — do not show them.
accumulatorGuarded should appear in Logcat only.

Show all changed files in full.
```

---

## FIX 23 — Activity durationMinutes Not Recomputed in Engine + DTO Default Missing

**Priority: HIGH** | **Mode: Normal**
Two active code/spec mismatches in the same function. The guide says `durationMinutes`
is ignored and always derived from `(endTimeMs − startTimeMs) / 60000` — matching
how sleep works. The engine still stores `dto.durationMinutes` verbatim. A driver
following the new spec that sets `durationMinutes = 0` will have activities stored
with zero duration. Additionally, `ActivityWasmDto.durationMinutes` has no default
value — a WASM author who omits the field entirely gets a `SerializationException`
and a silent activity parse failure despite the guide saying the field is optional.

---

```
# FIX 23 — Activity duration derivation and DTO default

## Two active bugs

### Bug A — Engine stores durationMinutes as-reported, not derived
WasmDriverEngine.parseActivity() constructs Activity with:
  durationMinutes = dto.durationMinutes
The guide now says durationMinutes is ignored and duration is always
derived from (endTimeMs − startTimeMs) / 60000. A driver following the
spec that sets durationMinutes = 0 produces activities with zero duration.

### Bug B — ActivityWasmDto.durationMinutes has no default value
The field is declared as val durationMinutes: Int with no default. The
guide says "Ignored. Set to 0." which implies the field is optional. A
WASM author who reads "ignored" and omits the field gets:
  SerializationException: Field 'durationMinutes' is required
The activity parse fails silently — no output, no user-visible error.

## Fix required

### 1. Derive durationMinutes in the engine

In WasmDriverEngine.parseActivity(), replace:
  durationMinutes = dto.durationMinutes
With:
  durationMinutes = ((dto.endTimeMs - dto.startTimeMs) / 60_000L).toInt()

Add a sanity check: if derived duration is ≤ 0, discard the activity and
log a warning:
  "Discarding activity [deviceName]: derived duration ≤ 0 
   startMs=${dto.startTimeMs} endMs=${dto.endTimeMs}"

This matches how sleep already handles duration.

### 2. Add default value to ActivityWasmDto

Change:
  val durationMinutes: Int
To:
  val durationMinutes: Int = 0

This means WASM authors can omit the field entirely (as the guide implies
they should) without causing a serialisation exception. The value is
ignored by the engine anyway — the default just prevents parse failures.

### 3. Verify sleep does the same

Confirm that SleepSession construction in WasmDriverEngine already derives
durationMinutes from (sleepEndMs - sleepStartMs) / 60000 rather than
storing dto.durationMinutes. If it does not, apply the same fix.

Show all changed files in full.
```