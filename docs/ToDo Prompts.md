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
Done
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
Done
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
Done
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
Done
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
Done
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

Not wanted
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
Done
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
Done
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
Done
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
Done
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
Done
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
Done
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
Done
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
Done
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
Done
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
Done
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
Done
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
Done
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
Done
```

---

## FIX 20 — Memory Layout Spec Change: Add Metadata Header Region

**Priority: HIGH** | **Mode: Plan**
Breaking spec change touching the memory layout contract, all WASM call sites, and specVersion routing logic. Also fixes an active code/spec mismatch: `WasmDriverEngine` currently has `IN_OFFSET = 0` but the spec now says 16. Any partial implementation mixing v1 and v2 call conventions silently corrupts parse results. Read `WasmDriverEngine` in full before touching anything.

---

```
Done
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
Done
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
Done
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
Done
```