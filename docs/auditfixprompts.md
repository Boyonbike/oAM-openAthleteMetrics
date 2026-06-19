# Athlete Data App — Leanness Audit Fix Prompt Sequence

Generated from three **leanness audits** (bloat, duplication, dead code, premature scaffolding, oversized functions, inconsistent patterns, defensive-code-with-no-trigger, and near-duplicate functions) covering: **Driver System**, **Database System**, and **BLE System**.

Each prompt below is self-contained — it restates the relevant file, the problem, and the required fix, so it can be pasted directly into a Claude Code session without needing the original audit reports attached.

**How to use this document**

- These are simplification/cleanup changes, not bug fixes — the underlying behavior should not change for any of these (a few are explicitly flagged where behavior *might* need to change, and those are called out clearly).
- Work through prompts in order: High → Medium → Low → Needs Human Judgment. The order here is about leverage (line-count/maintenance-burden reduction vs. risk), not urgency — none of these are bugs, so there's no time pressure the way there was with the correctness audits.
- One prompt = one Claude Code session. After each one: rebuild, run the existing test suite (or the closest manual equivalent — seeding, a sync cycle, a driver load) to confirm behavior is unchanged, then move on.
- A handful of these prompts touch the same file or the same underlying duplicated logic from a different angle — these are cross-referenced inline so you don't accidentally fix the same thing twice with two different approaches.
- Three items are flagged **NEEDS HUMAN JUDGMENT** in the original reports because the auditor couldn't tell from the code alone whether the "bloat" was actually load-bearing intent. Those are written as investigate-then-decide prompts, not blind fixes — see that section at the end.
- **Plan Mode vs. Normal Mode** tags follow the same convention as the correctness-fix document: Plan Mode for changes spanning many files, changing a shared abstraction, or requiring a real design decision; Normal Mode for narrow, single-location, low-ambiguity changes.

---

## HIGH

### H-1 — Unify `MetricRouter.route()` and `MetricRouter.routeAll()` into shared entity-mapping logic

**Source:** Driver Audit F-1 (Category 8) and DB Audit Finding 8-A — both reports independently flagged the same underlying duplication from different angles; this prompt covers both.

**Mode:** Plan Mode — touches the core routing function for all 10 metric types and changes the shape of two public functions; plan the extraction before editing either function.

**Problem:** `MetricRouter.route()` (lines 50–172) and `MetricRouter.routeAll()` (lines 183–338) independently implement the same metric-type-to-repository dispatch for all 10 routed types (HR, HRV, SPO2, RESPIRATION, SKIN_TEMP, STEPS, ACTIVE_CALORIES, TOTAL_CALORIES, BLOOD_PRESSURE, GLUCOSE). Entity construction (e.g. `HrReadingEntity(recordedAt = ..., createdAt = ..., source = ..., driverId = ..., ...)`) is duplicated verbatim 10 times across both functions. The BLOOD_PRESSURE diastolic-extraction-from-`metaJson` logic and its `Timber.w` fallback message are duplicated verbatim. The SLEEP_STAGE `pending_sleep_stage` flag injection is duplicated verbatim. The `computedByVersion = 1` hardcode for HRV appears in both places (lines 66, 204). The two functions differ in exactly two ways: `routeAll()` groups by type and calls `insertAllOrIgnore()` with conflict counting, while `route()` processes one reading at a time and calls `insert()`. `routeAllForceReplace()` (line 342) already delegates to `route()` in a loop, proving `route()`'s single-reading logic already supports force-replace semantics. The consequence: any field added to an entity, or any bug fixed in the BLOOD_PRESSURE/SLEEP_STAGE special-casing, must be manually kept in sync across two locations with no compiler enforcement.

**Prompt:**

```
Done
```

**After:** Rebuild, then run a live BLE sync (or simulate one) and a raw-replay/reprocess cycle, and confirm identical data lands in identical tables as before the refactor — this should be a pure refactor with zero behavior change.

---

### H-2 — Centralize `DEDICATED_METRIC_TYPES` into a single source of truth

**Source:** Driver Audit F-2 (Category 1) and BLE Audit F-3 — both reports independently flagged the exact same duplication; this is one fix, not two.

**Mode:** Plan Mode — a shared constant touching three files (the two existing copies plus its new home), and the choice of where it lives affects which module depends on which.

**Problem:** The identical eleven-element set `{HR, HRV, SPO2, RESPIRATION, SKIN_TEMP, STEPS, ACTIVE_CALORIES, TOTAL_CALORIES, BLOOD_PRESSURE, GLUCOSE, SLEEP_STAGE}` is defined independently in `BleEngine.kt` (lines 84–91, as a companion constant `DEDICATED_METRIC_TYPES`) and `DeviceSyncProcessor.kt` (lines 131–136, as a local `val dedicatedTypes`). `MetricRouter`'s own `when` block (the dispatch logic fixed in H-1) constitutes an implicit third copy of the same underlying truth — every type it handles explicitly *is* this set, just expressed as code branches instead of a collection. If a new `MetricType` gets a dedicated table in the future, all three places need updating, and nothing enforces that they stay in sync. The BLE audit additionally notes that `DeviceSyncProcessor`'s copy is used only for an invariant-violation check, and already shows signs of drift risk: it includes SLEEP_STAGE in a check whose comment is about misfiled "dedicated" types being sent to staging, even though SLEEP_STAGE is intentionally staged by design — a sign this list is already being reasoned about inconsistently across its two copies.

**Prompt:**

```
Done
```

**After:** Rebuild, then confirm the invariant-violation check in `DeviceSyncProcessor` still fires under the same conditions it did before (you can verify this by reading its logic against the canonical set rather than needing to reproduce a violation).

---

### H-3 — Collapse the 10 near-identical typed reading DAO interfaces into a shared base

**Source:** DB Audit Finding 1-A (Category 1) and Finding 8-B (Category 8) — the same underlying duplication, flagged once as plain duplication and once as the "near-duplicate functions that should be one" framing. Treat as a single fix.

**Mode:** Plan Mode — this is the highest line-count-reduction change in the whole leanness review (~350 lines per the DB audit's own summary), spans 10 files, and requires confirming Room's annotation-processor compatibility with the chosen abstraction approach before committing to it.

**Problem:** `HrReadingDao`, `HrvReadingDao`, `SpO2ReadingDao`, `RespirationReadingDao`, `SkinTempReadingDao`, `StepsReadingDao`, `ActiveCalorieReadingDao`, `TotalCalorieReadingDao`, `BloodPressureReadingDao`, `GlucoseReadingDao` are each 37 lines, and 9 of the 10 are character-for-character identical except for the entity class name and table name string. The method set (`insert`, `insertAll`, `insertAllOrIgnore`, `deleteBySource`, `deleteAll`, `getReadingsInRange`, `getReadingsInRangeOnce`, `getLatestReading`) appears in the same order with the same semantics in all 10 files. Only `HrReadingDao` differs, adding one extra method (`countSourceDataInRange`, used for the seeder banner). Adding a new query today means editing 10 files; removing a method means auditing 10 files for safety.

**Prompt:**

```
Done 
```

**After:** This is a large mechanical refactor — rebuild after, then run the full seeder cycle (seed 30 days, verify all reading types still populate correctly) and a BLE sync simulation to confirm no reading type silently broke during the consolidation.

---

### H-4 — Remove `getLatestReading()` and the `Flow` variant of `getReadingsInRange()` from all 10 typed reading DAOs/repositories (confirmed dead)

**Source:** DB Audit Finding 3-A and Finding 3-B (Category 3)

**Mode:** Normal Mode — mechanical removal across 10 files with zero behavior change, already confirmed dead by exhaustive grep in the audit; no design decision required.

**Problem:** `getLatestReading()` is declared in all 10 DAO interfaces, all 10 repository interfaces, and implemented in all 10 Room repositories — but confirmed by exhaustive search to have zero callers anywhere outside the data layer itself (no ViewModel, worker, service, or UI file calls it on any typed reading type). Separately, the `Flow<List<T>>`-returning variant of `getReadingsInRange()` (distinct from the suspend one-shot `getReadingsInRangeOnce()`, which IS used everywhere) has zero real callers — the only references are two `TODO` comments in `DailyDetailScreen.kt:178,182` describing a planned future graph read, not an actual call. Together this is roughly 60 method declarations and 60 compiled Room queries with no current purpose.

**Prompt:**

```
Done
```

**After:** Rebuild and run existing tests/screens that touch any of the 10 reading types to confirm nothing was actually relying on either removed method.

---

## MEDIUM

### M-1 — Decide the fate of `DeviceDriver`/`MetricProcessor` scaffolding (currently dead on every BLE packet)

**Source:** Driver Audit F-3 (Category 2)

**Mode:** Plan Mode — requires a real product decision (is a native driver path actually planned?) before any code is touched; do not let Claude Code delete this unilaterally without your input on the underlying question.

**Problem:** `DeviceDriver.kt` and `MetricProcessor.kt` are small, well-documented interfaces whose own KDoc honestly states no class implements them. In `BleEngine.kt`, this scaffolding is not just unused — it executes on every live BLE packet as a guaranteed no-op: `currentProcessor: MetricProcessor?` (line 112) is always null; `(activeManifest as? DeviceDriver)?.createProcessor()` (line 526) always evaluates to null since `WasmDriverManifest` is a data class that doesn't implement `DeviceDriver`; `currentProcessor?.onReading(reading)` (line 360) runs as a null-safe no-op on every incoming packet; `currentProcessor?.onSyncComplete()` (line 411) always returns `emptyList()`. The audit explicitly frames this as a judgment call, not a clear-cut bug: the interfaces are small and well-documented, but the dead call sites live inside the hot notification path, adding `currentProcessor` to the reader's mental model of the sync path for no current benefit.

**Prompt:**

```
Done
```

---

### M-2 — Extract shared boilerplate from `parseMetrics`/`parseSleep`/`parseActivity` into one helper

**Source:** Driver Audit F-4 (Category 8)

**Mode:** Plan Mode — touches three public functions' internals and changes their shared skeleton; plan the helper's signature before implementing.

**Problem:** `WasmDriverEngine.kt`'s `parseMetrics` (lines 94–120), `parseSleep` (127–155), and `parseActivity` (161–201) each independently: acquire `parseMutex.withLock`; cast `loadedManifest?.parsing as? ParsingConfig.WasmParsing ?: return@withLock <default>`; resolve the export name (with a null-guard for the optional exports in `parseSleep`/`parseActivity`); call `callParse(exportName, data)`; deserialize the result into a DTO; and catch any exception, returning the safe default. Steps 1, 2, and the catch-all are structurally identical across all three. The current shape has already caused a real, separately-flagged inconsistency (see M-3 below): the empty-payload guard exists in two of the three functions and is missing from the third, specifically *because* there's no shared helper enforcing consistency.

**Prompt:**

```
In WasmDriverEngine.kt, parseMetrics (lines 94-120), parseSleep (lines
127-155), and parseActivity (lines 161-201) each independently:
1. Acquire parseMutex.withLock
2. Cast loadedManifest?.parsing as? ParsingConfig.WasmParsing, returning
   a safe default if the cast fails
3. Resolve the relevant export function name from WasmExports (with a
   null-guard for parseSleep/parseActivity, since those exports are
   optional; parseMetrics's export is required)
4. Call callParse(exportName, data)
5. Deserialize the resulting JSON string into the relevant DTO type
6. Catch any exception and return the safe default, logging via Timber.w

Steps 1, 2, and 6 are structurally identical across all three functions.
Steps 3-5 differ in: which export name to resolve, whether that export is
optional, the DTO type to deserialize into, and any post-processing
specific to that data type.

Fix: extract a private suspend fun <T> callAndDecode(
    getExportName: (WasmExports) -> String?,
    decode: (String) -> T
): T? (or similar signature — adjust as needed once you see the exact
shapes) that handles the mutex acquisition, the WasmParsing cast/guard,
the callParse invocation, and the exception catch with safe-default
return. The three public functions become thin callers that supply their
export-name accessor and DTO decode/mapping logic, then apply any
type-specific post-processing on the result.

While doing this extraction, also resolve the empty-payload handling
difference between the three functions (parseSleep and parseActivity
currently guard against an empty "{}" result before decoding;
parseMetrics does not) — make all three handle this the same way inside
the new shared helper, so the inconsistency can't reappear. Decide
explicitly whether the guard belongs inside callAndDecode (applied to all
three uniformly) or stays a per-caller concern, and explain your choice.

Show me the extracted helper and all three simplified public functions.
Confirm WASM execution behavior is otherwise unchanged for all three data
types — same export names called, same DTOs produced, same error
handling outcome.
```

**Note:** This prompt resolves M-3 (the empty-payload inconsistency) as a side effect — do M-2 first; if you do M-3 separately afterward, it should find nothing left to fix.

---

### M-3 — Make `parseMetrics` handle empty WASM payloads the same way as `parseSleep`/`parseActivity`

**Source:** Driver Audit F-5 (Category 6)

**Mode:** Normal Mode — a single guard clause addition with the exact fix specified, **only needed if M-2 was not done first** (M-2's extraction resolves this as a side effect).

**Problem:** `parseSleep` and `parseActivity` apply `.takeIf { it.isNotBlank() && it != "{}" }` after `callParse`, producing a clean `null` early-return if the WASM module writes an empty object. `parseMetrics` does not have this guard. If a WASM module writes `"{}"` for a metrics call (intending to signal "no data"), `json.decodeFromString<List<MetricWasmDto>>("{}")` throws a `SerializationException` (since `{}` isn't a valid JSON array), which the outer `catch (e: Exception)` swallows, returning `emptyList()` with a misleading `Timber.w` warning that looks like a real error to anyone debugging — even though the end result (empty list, no crash) is correct.

**Prompt:**

```
Not needed
```

---

### M-4 — Remove the unused `characteristicUuid` parameter from six WASM parse function signatures

**Source:** Driver Audit F-6 (Category 3)

**Mode:** Plan Mode — touches six function signatures across three files and their call sites; plan to confirm no caller actually needs the value before removing it everywhere.

**Problem:** `callParse`/`parseMetrics`/`parseSleep` (and their `DriverRegistry` forwarding equivalents) in `WasmDriverEngine.kt` (lines 95, 128, 162) and `DriverRegistry.kt` (lines 77, 85, 94) all accept a `characteristicUuid` parameter that is forwarded straight through but never actually inspected inside `WasmDriverEngine` — `callParse` only uses the export function name and raw bytes; the WASM memory layout spec gives the module a byte offset and length, with no channel for the UUID at all. Three call sites (`BleEngine.kt:351–353`, `DeviceSyncProcessor.kt:301–304`, `DeviceReprocessor.kt:85–87`) all pass a real UUID value into a parameter that is silently dropped. The audit notes the UUID *could* matter for a future engine that dispatches to different export functions based on which characteristic sent the data — but today, it's dead weight on every signature.

**Prompt:**

```
characteristicUuid is a parameter threaded through six function
signatures but never actually used inside any of them:

- WasmDriverEngine.kt: parseMetrics, parseSleep, parseActivity (lines ~95,
  ~128, ~162) — the parameter is accepted but callParse() never inspects
  it; only the export function name and raw bytes are used.
- DriverRegistry.kt: the corresponding forwarding functions (lines ~77,
  ~85, ~94) — pass characteristicUuid straight through without using it
  either.

Three call sites pass a real UUID value into this dead parameter:
BleEngine.kt (~lines 351-353), DeviceSyncProcessor.kt (~lines 301-304),
DeviceReprocessor.kt (~lines 85-87).

First, check: is there any concrete near-term plan for
characteristic-based dispatch (different export functions called
depending on which BLE characteristic sent the data)? Search for any
related TODOs, comments, or partial implementation signaling this is
planned. If you find no such signal, ask me to confirm before proceeding
— this is a similar judgment call to other scaffolding-removal decisions
in this audit.

If there's no concrete plan: remove characteristicUuid from all six
function signatures (the three in WasmDriverEngine.kt and the three
forwarding ones in DriverRegistry.kt), and update all three call sites
(BleEngine.kt, DeviceSyncProcessor.kt, DeviceReprocessor.kt) to stop
passing it.

If there IS a concrete plan: leave the parameter in place, but add a KDoc
comment on each of the three WasmDriverEngine functions explaining that
the parameter is currently unused but reserved for planned
characteristic-based dispatch, with a brief note on the plan.

Show me your finding on whether a plan exists, and the resulting six
function signatures plus three call site updates (or the KDoc additions,
if you're keeping the parameter).
```

---

### M-5 — Document or formally mark the 9 staging-only `MetricType` values

**Source:** DB Audit Finding 4-A (Category 4)

**Mode:** Normal Mode — a documentation/annotation addition with no behavior change, well-specified.

**Problem:** `RHR`, `BODY_TEMP`, `TEMP_DEVIATION`, `VO2_MAX`, `DISTANCE`, `ELEVATION_GAIN`, `ELEVATION_LOSS`, `CALORIES`, `BASAL_CALORIES` (`MetricType.kt:18–27`) have no dedicated table, no seeder data, and no UI consumer — they fall through to `stagingRepository.insert(reading)` in `MetricRouter` with a comment referencing a prior audit finding that deferred a schema decision. These 9 enum entries add cognitive load every time someone reads `MetricType`, and the distinction between `CALORIES`, `ACTIVE_CALORIES`, and `TOTAL_CALORIES` (the latter two *do* have dedicated tables) is undocumented, making the enum confusing on its own terms even before considering the staging-fallback question.

**Prompt:**

```
MetricType.kt (lines 18-27) defines 9 values with no dedicated table, no
seeder data generation, and no UI consumer: RHR, BODY_TEMP,
TEMP_DEVIATION, VO2_MAX, DISTANCE, ELEVATION_GAIN, ELEVATION_LOSS,
CALORIES, BASAL_CALORIES. All 9 fall through to the staging table
(metric_readings_staging) via MetricRouter's fallback branch, with a
comment there noting this was already reviewed once and deferred. These
rows accumulate in staging indefinitely with nothing reading them back
out.

Separately, the relationship between CALORIES, ACTIVE_CALORIES, and
TOTAL_CALORIES is not documented anywhere in MetricType — the latter two
have dedicated tables, CALORIES does not, and a reader has no way to know
why or what CALORIES is for if ACTIVE_CALORIES and TOTAL_CALORIES already
exist.

This is not a candidate for silent deletion (these readings may
genuinely be planned for future dedicated tables), so the fix here is
clarity, not removal:

1. Add a KDoc comment to each of the 9 staging-only MetricType entries
   (or a single comment above the group, whichever reads better) stating
   they are intentionally staging-only pending a future dedicated-table
   decision, distinct from a documented timeline if one exists (check
   with me if you're unsure whether there's a real near-term plan for
   any of them, rather than assuming there isn't).
2. Add a comment clarifying the distinction between CALORIES,
   ACTIVE_CALORIES, and TOTAL_CALORIES specifically — what each
   represents and why CALORIES alone lacks a dedicated table.
3. Consider (and tell me your recommendation, but don't implement without
   confirming): would an explicit boolean or enum property on each
   MetricType entry (e.g. hasDedicatedTable: Boolean, or a
   StorageDestination enum) make the staging-fallback behavior explicit
   in the type itself, rather than implicit in MetricRouter's when-block
   structure? This would mean MetricRouter's fallback branch could assert
   "this type is staging-only by design" instead of just being "every
   type not explicitly listed."

Show me the updated MetricType.kt with the new documentation, and your
recommendation on point 3 before implementing it.
```

---

### M-6 — Split `SeederService.seedDays()` into a per-day function

**Source:** DB Audit Finding 5-A (Category 5)

**Mode:** Plan Mode — restructures a central seeding function; plan the extraction boundary so the per-day function's signature and the outer loop's responsibilities are clearly divided before writing code.

**Problem:** `SeederService.seedDays()` (lines 111–192, 81 lines) handles six distinct concerns in one function: pre-computing workout/illness/dip/sleep/weight schedules for all days; querying existing data for the "already seeded" guard; orchestrating insertion across 10 metric types via 11 repository calls; generating and inserting sleep sessions (including a non-obvious insert-then-query-back-the-generated-ID pattern to attach sleep stages); enqueueing `DailySummaryWorker` per day; and collecting failed dates while reporting progress. The sleep-session handling in particular is flagged as "non-obvious" — a bug in the sleep-stage foreign key relationship would be invisible without reading through the entire orchestration function to find it.

**Prompt:**

```
SeederService.seedDays() (lines 111-192, 81 lines) currently handles six
distinct concerns in a single function:
1. Pre-computing workout/illness/dip/sleep/weight schedules for all days
   in the range (lines ~116-120)
2. Querying existing data to check the "already seeded" guard per day
   (lines ~127-131)
3. Orchestrating insertion of all 10 metric types via repository calls
   for a given day (lines ~139-149, ~165-169)
4. Generating a sleep session, inserting it, then querying back the
   database-generated ID in order to attach sleep stages to it (lines
   ~154-169) — this insert-then-requery pattern is non-obvious and easy
   to miss when scanning the function
5. Enqueueing DailySummaryWorker for the day (line ~182)
6. Collecting failed dates and reporting progress (lines ~122-124,
   ~183-188)

Fix: extract a private suspend fun seedDay(date: LocalDate, <whatever
schedule/context parameters it needs from the precomputed schedules>)
that covers concerns 2 through 5 for a single date — i.e. everything from
"check if this day is already seeded" through "enqueue the summary
worker for this day," including the sleep-session insert-then-requery
logic. The outer seedDays() should then only:
- Precompute the schedules for the full date range (concern 1)
- Loop over dates, calling seedDay() for each
- Collect failures and report aggregate progress (concern 6)

This should not change any seeded data, timing, or behavior — it is a
pure structural extraction so the per-day logic (especially the
sleep-session ID requery, which is the most bug-prone part) is readable
and independently reasoned about, separate from the outer
orchestration/scheduling loop.

Show me the extracted seedDay() function and the simplified seedDays().
Confirm a full 30-day seed produces identical output before and after
this refactor (same metric counts, same sleep sessions, same daily
context rows).
```

**After:** Run a full seed cycle before and after, and diff the resulting row counts per table to confirm the extraction didn't change behavior.

---

### M-7 — Route `SettingsViewModel`'s bulk delete through the repository layer instead of direct DAO access

**Source:** DB Audit Finding 5-B (Category 5)

**Mode:** Plan Mode — adds a new method to every repository (or a new centralizing service) and changes how one ViewModel is wired; plan the chosen shape before touching 13+ files.

**Problem:** `SettingsViewModel.kt` (lines 143–162) injects `AppDatabase` directly and calls `deleteAll()` on 13 DAOs, bypassing the repository layer entirely — every other ViewModel in the codebase injects repository interfaces and never touches DAOs directly. This is a real maintenance risk: if any repository's `deleteAll()` ever grows side effects (enqueuing a cleanup worker, cascading to a related table not covered by a foreign key constraint), `SettingsViewModel`'s direct DAO call would silently skip those side effects since it never goes through the repository at all.

**Prompt:**

```
SettingsViewModel.kt (lines 143-162) injects AppDatabase directly and
calls deleteAll() on 13 DAOs for its "reset all data" feature. Every
other ViewModel in this codebase injects repository interfaces and never
touches DAOs or AppDatabase directly — this is the one exception.

The risk: if any repository's delete/cleanup logic ever grows side
effects beyond a plain DAO delete (e.g. enqueueing a WorkManager job,
cascading a delete to a table not covered by a DB-level foreign key),
SettingsViewModel's direct-DAO approach would silently skip those side
effects, since it bypasses the repository layer where such logic would
live.

Fix: add a deleteAll() (or clearAllData()) method to each of the 13
relevant repository interfaces (and their Room implementations), each
delegating to its own DAO's existing deleteAll(). Then update
SettingsViewModel to inject those 13 repository interfaces instead of
AppDatabase, calling each repository's new method instead of going
through the DAO directly.

If 13 individual repository injections feels excessive for one
ViewModel, consider (and tell me your recommendation before implementing)
introducing a single DatabaseCleaner or DataResetService that itself
injects all 13 repositories and exposes one clearAllData() method —
SettingsViewModel would then inject just that one service. Pick whichever
approach better matches how this codebase already handles
multi-repository orchestration elsewhere (check if a similar pattern
already exists, e.g. in the seeder).

Show me the updated repository interfaces/implementations and the
updated SettingsViewModel. Confirm the "reset all data" feature in
Settings still deletes everything it did before.
```

---

### M-8 — Resolve or document the `REPLACE` vs `IGNORE` conflict-strategy inconsistency between `route()` and `routeAll()`

**Source:** DB Audit Finding 6-A (Category 6) and the related BLE Audit NHJ note about the same divergence

**Mode:** Plan Mode — this is a behavioral question (is the divergence intentional?) before it's a code change; plan/investigate first, since changing conflict strategy changes what happens to real duplicate data.

**Problem:** `MetricRouter.route()` calls `insert()` (declared `OnConflictStrategy.REPLACE` on all typed reading DAOs), so a duplicate HR reading arriving via live BLE sync silently *replaces* the stored row. `MetricRouter.routeAll()` calls `insertAllOrIgnore()` (`OnConflictStrategy.IGNORE`), so the same duplicate arriving via raw-data reprocess is silently *dropped* instead. `routeAllForceReplace()` (line 342) calls `route()` per reading (REPLACE) in a loop. There is no comment anywhere explaining whether this three-way difference in conflict handling is deliberate (e.g. "live sync should win, replay should never overwrite fresher data") or accidental drift. A reader cannot currently tell which it is.

**Prompt:**

```
MetricRouter has three different conflict-resolution behaviors for what
is logically the same operation (writing a metric reading that might
already exist):

1. route() -> calls insert() -> OnConflictStrategy.REPLACE -> a duplicate
   reading silently overwrites the existing row.
2. routeAll() -> calls insertAllOrIgnore() -> OnConflictStrategy.IGNORE
   -> a duplicate reading is silently dropped, existing row untouched.
3. routeAllForceReplace() -> calls route() per reading in a loop ->
   REPLACE (same as #1).

So: live BLE sync (route()) and forced reprocess (routeAllForceReplace())
both overwrite on conflict; raw-data replay (routeAll()) does not. There
is no comment anywhere in MetricRouter explaining whether this is
deliberate design (e.g. "live sync data should always win over older
buffered data; passive replay should never clobber something newer") or
unintentional drift between the three call paths.

Investigate first: look at how routeAll() (the raw-replay path) is
actually invoked in DeviceSyncProcessor.processFromRaw() — is there a
reason replay data should be considered lower-priority than live data in
this specific architecture (e.g. replay runs against potentially stale
buffered data that arrived after live data already established the
canonical row)? Form a view on whether the difference is sensible given
how each path is actually used, then do ONE of the following:

(a) If the divergence is sensible and intentional: add a clear comment
    to both route() and routeAll() (and routeAllForceReplace()) stating
    the conflict-strategy rationale for each, so a future reader doesn't
    have to re-derive it. No behavior change.

(b) If you conclude (or I confirm, if you're unsure) the divergence is
    unintentional and the three paths should behave consistently: tell me
    which conflict strategy should be canonical before changing anything
    — this changes real data-handling behavior, not just code structure,
    so confirm with me before implementing either direction.

Show me your investigation findings and recommendation before making any
code change.
```

---

### M-9 — Resolve the local-timezone vs UTC day-boundary mismatch in `DailySummaryWorker`

**Source:** DB Audit Finding 6-B (Category 6), closely related to DB Audit NHJ-1 (see the Needs Human Judgment section — this is the same underlying fact, here framed as a hard inconsistency rather than an open question)

**Mode:** Plan Mode — this is a real behavioral question about correctness, not just style; investigate the actual intent (what does the UI display?) before changing any boundary calculation.

**Problem:** `DailySummaryWorker.kt:65–68` computes its day-boundary window using `ZoneId.systemDefault()` (the device's local timezone). Every other component that writes time-bounded data — `SeederService`, `RoomActivityRepository`, `RoomMetricReadingStagingRepository`, and all 10 typed reading repositories — uses `toUtcStartMs()`, which resolves to `ZoneOffset.UTC`. For a user in UTC+5, local midnight falls 5 hours after UTC midnight, so the worker's query window for "today" does not align with the UTC-based window every writer used when storing the data. This is the same underlying timezone fact flagged separately as NHJ-1 (below) — that entry frames it as an open question ("is this intentional?"); this entry states it as a hard inconsistency. Resolve them together: the investigation in NHJ-1 determines what M-9 should actually do.

**Prompt:**

```
DailySummaryWorker.kt (lines 65-68) computes its day-boundary window using
the device's local timezone:

val zone       = ZoneId.systemDefault()
val dayStartMs = date.atStartOfDay(zone).toInstant().toEpochMilli()
val dayEndMs   = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

Every other writer of time-bounded data in this codebase — SeederService,
RoomActivityRepository, RoomMetricReadingStagingRepository, and all 10
typed reading repositories — uses a UTC-based helper (toUtcStartMs(),
resolving to ZoneOffset.UTC) for equivalent calculations. For a user in
a non-UTC timezone, the worker's "today" window (local-time-based) does
not line up with the UTC-based window every writer used when storing
readings — e.g. for a UTC+5 user, readings written between 00:00 and
05:00 UTC would be associated with the wrong calendar day relative to
what the worker queries as "today."

Before changing anything, determine the actual intent: does the app's UI
display DailySummary.date (and related day-based groupings) as the
user's local calendar day, or as a UTC calendar day? Check how dates are
formatted and displayed wherever DailySummary is shown (e.g. the
dashboard, daily detail screen).

- If the UI shows the user's LOCAL calendar day: the worker's
  local-timezone window is actually the CORRECT design intent — but
  every writer using UTC-based day-boundaries elsewhere creates a real
  mismatch bug (data written near UTC midnight could land in the wrong
  summary day for non-UTC users). In this case, the fix is NOT to change
  DailySummaryWorker — it's to either (a) align every writer to also use
  local-timezone day boundaries when deciding which "day" a reading
  belongs to for aggregation purposes, or (b) keep raw storage in UTC
  (which is reasonable) but ensure DailySummaryWorker's query window
  conversion correctly maps local calendar days to the corresponding UTC
  range for querying — re-examine whether it already does this correctly
  or has an actual bug.

- If the UI shows (or is expected to show) a UTC calendar day: change
  DailySummaryWorker to use the same toUtcStartMs()-based UTC boundary
  approach as every other writer, for consistency.

Add a clear comment to DailySummaryWorker's boundary calculation either
way, stating which calendar-day convention the app uses and why, so this
doesn't need to be re-investigated again later.

Show me your finding on what the UI actually displays, your conclusion on
which side is correct, and the resulting code change (which may be "no
change to DailySummaryWorker, but a fix elsewhere" depending on what you
find).
```

**Note:** Treat this together with NHJ-1 below — they are the same underlying question from two audit framings. Resolve once, not twice.

---

### M-10 — Apply consistent try/catch error handling across all repositories (or remove it everywhere it adds no value)

**Source:** DB Audit Finding 6-C (Category 6) and Finding 7-A (Category 7) — these two findings are in tension with each other (one says "apply consistently," the other says "the pattern itself adds no value") and need to be resolved together as a single decision, not two separate fixes.

**Mode:** Plan Mode — this requires picking one of two opposite directions (standardize on try/catch everywhere, or remove it everywhere) and applying that decision consistently; the choice itself needs to be made deliberately, not mechanically.

**Problem:** `RoomHrReadingRepository.kt:19–26` (and 8 other repositories) wrap every DAO operation in `try { dao.insert(entity) } catch (e: Exception) { Timber.e(...); throw e }`. `RoomActivityRepository.kt` (lines 26–29, 78, 31–37, 39–69) has no try/catch at all on equivalent operations. There is no documented reason for the difference (Finding 6-C). Separately, the audit also questions whether the try/catch pattern itself is worth keeping anywhere: Room `@Insert` operations can legitimately throw (`SQLiteConstraintException`, `SQLiteFullException`), so the catch block is technically reachable — but since the exception is immediately re-thrown after logging, the caller receives the identical exception whether the try/catch exists or not. The only actual effect is the `Timber.e` log line, which the audit notes could equally be provided by a single global `Timber.Tree` intercepting coroutine exceptions, or a database error callback on `Room.databaseBuilder`, instead of ~200 lines of repeated boilerplate across repositories (Finding 7-A).

**Prompt:**

```
There are two related findings about error handling in the repository
layer that need to be resolved together as one decision:

1. INCONSISTENCY: RoomHrReadingRepository.kt (lines 19-26) and 8 other
   repositories wrap every DAO call in try { ... } catch (e: Exception) {
   Timber.e(...); throw e }. RoomActivityRepository.kt (lines ~26-29, ~78,
   ~31-37, ~39-69) has no try/catch at all on equivalent operations,
   with no documented reason for the difference.

2. QUESTIONABLE VALUE: where the try/catch pattern DOES exist, it
   immediately re-throws after logging — so the caller receives the
   identical exception either way. The only actual effect is the
   Timber.e log line. This adds roughly 200 lines of repeated boilerplate
   across repositories for what is purely a logging concern.

These two findings point in different directions (one says "make it
consistent everywhere," the other says "the pattern adds no value
anywhere") — resolve them as a single decision, not two patches:

Pick ONE of these two directions and apply it everywhere:

(a) REMOVE the try/catch/log/rethrow pattern everywhere it currently
    exists (since it adds no behavioral value over not catching at all),
    and instead centralize logging via ONE of: a global Timber.Tree that
    intercepts uncaught coroutine exceptions, or a database error
    callback registered once on Room.databaseBuilder. This removes ~200
    lines total and makes ALL repositories consistent (none have local
    try/catch).

(b) APPLY the try/catch/log/rethrow pattern consistently to every
    repository that doesn't have it yet (starting with
    RoomActivityRepository), so all repositories behave identically. This
    keeps per-call-site logging context (which DAO call failed, with
    what entity) that a global handler might lose, at the cost of keeping
    (and extending) the boilerplate.

Tell me which direction you'd recommend and why (consider: does the
per-call-site Timber message currently carry information a global
handler couldn't reconstruct from the stack trace alone? e.g. "Failed to
insert HR reading" vs a global handler that would just see "exception in
coroutine X" without knowing it was specifically an HR insert) — then
implement whichever direction I confirm.

Show me the full set of changes once we've agreed on a direction.
```

---

### M-11 — Extract shared scoring logic duplicated between `SeederService.generateDailyContext()` and `generateQuestionResponses()`

**Source:** DB Audit Finding 8-C (Category 8)

**Mode:** Normal Mode — a single well-specified extraction with the exact formula and target shape already given.

**Problem:** `SeederService.kt:585–599` and `SeederService.kt:634–648` both independently compute the same HRV/sleep-derived scoring formulas (`hrvNorm`, `baseStress`, `sleepHours`, `motivBase`) from the same two input parameters (`morningHrv: Double`, `sleepMinutes: Int`). The only difference between the two call sites is how the computed values get used afterward (as struct fields in one case, as question-response values in the other). If the scoring formula changes, it must currently be updated in two places with no compiler enforcement that both stay in sync.

**Prompt:**

```
SeederService.kt has the same scoring computation duplicated in two
places: generateDailyContext() (around lines 585-599) and
generateQuestionResponses() (around lines 634-648). Both independently
compute, from the same morningHrv: Double and sleepMinutes: Int inputs:

val hrvNorm    = (morningHrv - 20.0) / 80.0
val baseStress = (1.0 - hrvNorm) * 4.0 + 1.0
val sleepHours = sleepMinutes / 60.0
val motivBase  = when { sleepHours >= 8.0 -> ...; sleepHours >= 7.0 -> ...; ... }

(check the actual current motivBase thresholds/values in the file rather
than assuming the above is complete — copy the real logic exactly).

The only difference between the two call sites is what happens to
baseStress and motivBase afterward (used as struct fields in one
function, as question-response values in the other) — the scoring
formula itself is identical.

Fix: extract a data class DayScores(val baseStress: Double, val motivBase:
<correct type, check current code>) and a private fun scoreDayContext(
    morningHrv: Double,
    sleepMinutes: Int,
    rng: <whatever random source the existing code uses, if any>
): DayScores that both generateDailyContext() and
generateQuestionResponses() call instead of recomputing the formula
independently.

Show me the extracted data class + function, and the simplified versions
of both call sites. Confirm a seeded data run produces identical output
before and after — same baseStress and motivBase values for the same
inputs, since this should be a pure refactor.
```

---

### M-12 — Remove `Syncing.progress` field or wire it to real progress data

**Source:** BLE Audit F-8 (Premature Scaffolding)

**Mode:** Normal Mode — the removal path is a small, well-specified UI + state change; only escalate to a bigger refactor if you choose the "wire it up" direction, which needs your input first.

**Problem:** `BleConnectionState.Syncing` carries a `progress: Float` field. It is emitted exactly once, in `BleEngine.triggerSync()` (line 432), hardcoded to `0f`. The UI (`DevicesScreen.kt:748–752`) passes this directly into `LinearProgressIndicator(progress = { state.progress })`, rendering a determinate progress bar permanently frozen at 0% immediately before the state flips to `SyncComplete` — a visibly broken-looking progress indicator. Since the underlying `process()` call is a single-shot suspend function with no intermediate progress callbacks, the field cannot be populated without a real structural change. The sibling `Connecting` state (line 743) already correctly uses an indeterminate indicator instead of a fake determinate one.

**Prompt:**

```
BleConnectionState.Syncing carries a progress: Float field. It is emitted
exactly once, in BleEngine.triggerSync() (line ~432), hardcoded to 0f —
there is no code path that ever sets it to anything else. The UI
(DevicesScreen.kt, lines ~748-752) renders this directly via
LinearProgressIndicator(progress = { state.progress }), which shows a
determinate bar stuck at 0% right up until the state flips to
SyncComplete — a visibly broken progress indicator from the user's
perspective. The sibling Connecting state (line ~743) already correctly
uses an indeterminate (spinning) indicator rather than a fake determinate
one.

This needs a decision before implementing:

(a) QUICK FIX (no behavior change beyond the visual): remove the
    progress field from BleConnectionState.Syncing entirely, and update
    DevicesScreen.kt to render an indeterminate LinearProgressIndicator
    for the Syncing state, matching how Connecting is already handled.
    This closes the visibly-broken-UI gap immediately with a one-field
    removal and a one-line UI change.

(b) REAL FIX (larger): add actual progress-callback infrastructure to
    DeviceSyncProcessor.process() (e.g. an optional onProgress: ((Float)
    -> Unit)? callback parameter, threaded through to wherever
    per-record or per-batch progress could meaningfully be reported), and
    have BleEngine.triggerSync() forward real progress values into
    Syncing.progress as the sync actually proceeds.

Default to (a) unless you tell me there's a near-term plan to build real
progress reporting (in which case (b) is worth the larger investment).
Implement (a) now; only do (b) if I confirm that's the direction I want
instead.

Show me the change once you've implemented (a), or ask me to confirm
before starting (b).
```

---

### M-13 — Resolve the inconsistent meaning of `readingsSkipped` across the live and raw-replay sync paths

**Source:** BLE Audit F-9 (Inconsistent Patterns)

**Mode:** Plan Mode — this affects the meaning of a value used for sync history/reporting; the fix requires either real engineering work (tracking accumulator updates in `routeAll()`) or a type-level change to make the two paths' outputs non-comparable by construction — both are real decisions, not mechanical edits.

**Problem:** In `DeviceSyncProcessor.kt`, `process()` (line 252) computes `readingsSkipped = deviceInsert.readingsSkipped`, sourced from the staging repository's actual IGNORE-on-conflict outcome counter — it counts real database-level duplicates. `processFromRaw()` (line 401) computes `readingsSkipped = readingsAccepted - routeResult.newRecordsInserted - routeResult.accumulatorUpdates`, where `accumulatorUpdates` is hardcoded to always be `0` (confirmed at `MetricRouter.kt:337`). For accumulator-type metrics (STEPS, DISTANCE, etc.), a reading that updates an existing accumulator row is counted as `accumulatorUpdates = 1` on the live path but ends up folded into `readingsSkipped` on the raw-replay path — meaning `SyncSummary`'s fields mean genuinely different things depending on which of the two sync paths produced them, with no indication of this in the type itself.

**Prompt:**

```
DeviceSyncProcessor's two sync entry points compute readingsSkipped
differently, and the difference changes the field's actual meaning, not
just its implementation:

- process() (line ~252): readingsSkipped = deviceInsert.readingsSkipped,
  sourced from the staging repository's real IGNORE-on-conflict counter
  — this counts genuine database-level duplicate rejections.
- processFromRaw() (line ~401): readingsSkipped = readingsAccepted -
  routeResult.newRecordsInserted - routeResult.accumulatorUpdates, where
  accumulatorUpdates is confirmed (MetricRouter.kt:337) to always be
  hardcoded to 0 in this path.

Consequence: for an accumulator-type metric (e.g. STEPS, DISTANCE) where
a reading updates an existing row rather than inserting a new one, the
live path (process()) correctly attributes this as accumulatorUpdates=1.
The raw-replay path (processFromRaw()) has no way to detect this (since
accumulatorUpdates is hardcoded 0 there), so the same kind of update gets
folded into readingsSkipped instead. SyncSummary — the shared output type
of both paths — therefore means something different depending on which
code path produced it, with nothing in the type signaling this.

This needs a decision, not a mechanical fix. Pick one:

(a) FIX THE ROOT CAUSE: make routeAll() (used by processFromRaw())
    properly track accumulator updates, the same way the live path's
    staging-repo detection logic does. This requires understanding how
    the staging repo currently detects an accumulator update on the live
    path and replicating that detection inside routeAll() / MetricRouter
    for the batch path. This is real engineering work, not a quick
    rename — investigate the staging repo's detection logic first and
    confirm it's feasible to replicate before committing to this
    direction.

(b) MAKE THE DIVERGENCE EXPLICIT IN THE TYPE: if (a) isn't feasible or
    isn't worth the investment, change SyncSummary so its fields are not
    silently comparable across paths when they don't mean the same thing
    — e.g. split into two distinct result types (one for live sync, one
    for raw-replay) with field names that don't imply false equivalence,
    or make accumulatorUpdates nullable specifically on the raw-replay
    output to signal "this path cannot determine this value" rather than
    a misleading hardcoded 0.

Tell me which direction you'd recommend after investigating how feasible
(a) actually is, and I'll confirm before you implement either one.
```

---

### M-14 — Extract `BleEngine`'s notification-setup/sync-command-execution logic into a focused collaborator

**Source:** BLE Audit F-11 (Oversized / Multi-Responsibility)

**Mode:** Plan Mode — a structural extraction of a real architectural seam from a 28-field, six-responsibility class; needs careful planning of the new collaborator's interface (specifically how it talks to the GATT object) before touching `BleEngine` itself.

**Problem:** `BleEngine.kt` (the full file, ~893 lines) has 28+ instance fields and is responsible for at least six distinct concerns: scan lifecycle; GATT connection lifecycle (connect, callbacks, close, retry/backoff); notification setup sequencing; sync command execution; packet reassembly/WASM parsing/in-memory accumulation; and quiescence detection plus summary-worker scheduling. The audit identifies the clearest extraction seam as concerns 3+4 together (notification setup sequencing + sync command execution — "what do I send/enable and in what order," the post-connection synchronous protocol), estimating this alone would reduce `BleEngine` by roughly 120 lines and let that concern evolve independently of the other five.

**Prompt:**

```
BleEngine.kt (~893 lines, 28+ instance fields) currently owns at least six
distinct responsibilities in one class:
1. Scan lifecycle (startScan, stopScan, scanCallback, scanTimeoutJob)
2. GATT connection lifecycle (connect, gattCallback, closeGatt,
   scheduleRetry, retry/backoff state)
3. Notification setup sequencing (notifySetupQueue, enableNextNotification,
   enableNotification)
4. Sync command execution (commandIndex, inSyncCommandNotify,
   executeNextSyncCommand)
5. Packet reassembly, WASM parsing, in-memory accumulation
   (reassemblyBuffers, pendingMetrics, pendingSleep, pendingActivities,
   seenSleepStartMs, handleNotification)
6. Quiescence detection + summary worker scheduling (quiescenceJob,
   isQuiescent, affectedDates, packetCount)

The clearest extraction seam is concerns 3+4 together — the
post-connection synchronous protocol logic ("what do I send/enable, in
what order") — which is largely self-contained from the other four
concerns.

Fix: extract a new focused class (e.g. SyncCommandExecutor) that owns:
notifySetupQueue, commandIndex, inSyncCommandNotify, and the three
methods executeNextSyncCommand, enableNotification,
enableNextNotification.

This new class needs to issue GATT writes (writeDescriptor /
writeCharacteristic) without taking a direct BluetoothGatt reference
itself (to avoid just relocating the coupling rather than reducing it).
Define a small GattWriteDelegate interface wrapping just those two calls,
have BleEngine implement or provide it, and have the new
SyncCommandExecutor depend on the interface instead of BluetoothGatt
directly.

BleEngine's GATT callbacks (onDescriptorWrite, onCharacteristicWrite, the
parts that currently call into the notification/command sequencing logic)
should delegate to the new SyncCommandExecutor instead of containing that
logic inline. Keep the call graph shallow — BleEngine should call into
SyncCommandExecutor, not the reverse, and SyncCommandExecutor should not
need to know about scanning, reassembly, or quiescence detection at all.

This is a structural extraction only — no behavior should change. The
sequencing of notification setup and sync command execution, the retry
semantics, and all timing should be identical before and after.

Show me the new SyncCommandExecutor class, the GattWriteDelegate
interface, and the simplified BleEngine with the extracted logic removed
and replaced with delegation calls. Confirm the line-count reduction in
BleEngine.
```

**After:** This is the largest single structural change in this leanness pass for the BLE system — rebuild, then run a full connect → sync → disconnect cycle against a real or simulated device to confirm notification setup and sync command sequencing behave identically to before the extraction.

---

## LOW

### L-1 — Remove unused `durationMinutes` field from `SleepWasmDto` and `ActivityWasmDto`

**Source:** Driver Audit F-7 (Category 3)

**Mode:** Normal Mode — a single field removal from two DTOs with the exact reasoning and fix already specified.

**Problem:** Both `SleepWasmDto` and `ActivityWasmDto` (`WasmParseDto.kt:20, 31`) carry a `durationMinutes` field (default `0`). `WasmDriverEngine.kt` (lines 147, 173) ignores this field entirely in both cases and recomputes duration from `(endMs − startMs) / 60_000`. The driver authoring guide already instructs authors to set the field to `0`. The field's only practical purpose is letting JSON containing a `durationMinutes` key deserialize without error — but `ignoreUnknownKeys = true` already makes that protection unnecessary.

**Prompt:**

```
Both SleepWasmDto and ActivityWasmDto (WasmParseDto.kt, lines ~20 and
~31) declare a durationMinutes field with a default of 0.
WasmDriverEngine.kt (lines ~147, ~173) ignores this field completely in
both cases, instead recomputing duration as (endMs - startMs) / 60_000.
The driver authoring guide already instructs authors to set this field to
0 — i.e. its documented behavior is "this does nothing." The field's only
practical effect is letting JSON that includes a durationMinutes key
deserialize without error, but kotlinx.serialization's
ignoreUnknownKeys = true (already configured in this codebase) makes that
protection unnecessary — JSON with an unrecognized key would deserialize
fine without the field declared at all.

Fix: remove the durationMinutes field from both SleepWasmDto and
ActivityWasmDto. Confirm WasmDriverEngine.kt's parseSleep/parseActivity
functions don't reference the field anywhere (they shouldn't, per the
audit, but verify before removing). Update
docs/DRIVER_AUTHORING_GUIDE.md's JSON schema tables for parseSleep and
parseActivity to remove the durationMinutes row / change its instruction
from "set to 0" to "omit this field — duration is always computed by the
app from start/end timestamps."

Show me the updated DTOs, confirm no engine code referenced the field,
and show the updated guide sections.
```

---

### L-2 — Replace the redundant `loadedManifest` re-fetch inside the WASM trap handler with the already-in-scope variable

**Source:** Driver Audit F-8 (Category 7)

**Mode:** Normal Mode — single-line replacement with the exact fix specified; no ambiguity.

**Problem:** Inside `callParse()`, `manifest` and `wasm` (cast as `ParsingConfig.WasmParsing`) are already resolved and in scope from earlier in the function (line 232), inside the same `parseMutex.withLock` block. After catching a `ChicoryException`, the handler (line 261) redundantly re-fetches `loadedManifest?.parsing as? ParsingConfig.WasmParsing` — but since the mutex guarantees `loadedManifest` cannot change between function entry and the exception handler, this re-fetch is a null-safe cast that can never actually be null in practice, adding a meaningless `?.` and an `if (wasm != null)` guard around what is really just the already-known `wasm` variable.

**Prompt:**

```
Inside WasmDriverEngine.kt's callParse() function, at the top of the
function (around line 232), manifest is fetched and wasm (cast as
ParsingConfig.WasmParsing) is already resolved and held in scope for the
remainder of the function, inside the same parseMutex.withLock block.
After catching a ChicoryException, the handler (around line 261)
redundantly re-fetches: loadedManifest?.parsing as? ParsingConfig.WasmParsing,
then checks if (wasm != null) before using it.

Since this all happens within the same parseMutex.withLock block,
loadedManifest cannot change between function entry and the exception
handler — the re-fetch can never actually produce a different or null
value than the wasm variable already in scope.

Fix: replace the re-fetch with a direct reference to the already-captured
wasm variable: instance = instantiate(wasm.wasmBytes). Remove the
if (wasm != null) guard and the redundant cast — they're unreachable
dead conditions now that the real wasm variable from the outer scope is
used directly.

Show me the updated exception handler.
```

---

### L-3 — Remove explicit `"parseSleep": null, "parseActivity": null` from `example_wasm.json`

**Source:** Driver Audit F-9 (Category 3)

**Mode:** Normal Mode — a JSON file edit removing two keys, fully specified.

**Problem:** `example_wasm.json` (lines 47–48) explicitly includes `"parseSleep": null` and `"parseActivity": null` for a metrics-only example driver. `WasmExports.parseSleep` and `parseActivity` are both nullable `String?` with a default of `null`, so including them as explicit nulls adds no behavioral meaning — it only risks teaching driver authors (who copy the example as a starting template) that these keys must always be present even when unused, when in fact any driver supporting only metrics should simply omit them.

**Prompt:**

```
app/src/main/assets/drivers/example_wasm.json (lines ~47-48) explicitly
includes:

"parseSleep": null,
"parseActivity": null

WasmExports.parseSleep and parseActivity are both nullable (String? =
null by default), so including them as explicit null values changes
nothing behaviorally — but it risks teaching anyone copying this example
as a starting template that these keys must be present (even as null)
for a metrics-only driver, when the correct minimal form is to omit them
entirely.

Fix: remove both "parseSleep": null and "parseActivity": null lines from
example_wasm.json, so the example models the minimal-valid JSON for a
driver that supports only metrics (no sleep, no activity).

Show me the updated example_wasm.json.
```

---

### L-4 — Remove the dead `processedCount` variable from `BleEngine`'s notification consumer loop

**Source:** BLE Audit F-1 (Dead or Unreachable Code)

**Mode:** Normal Mode — single variable removal, no ambiguity.

**Problem:** `BleEngine.kt:169–173` declares `var processedCount = 0`, increments it on every notification processed in the consumer loop, but never reads it anywhere. It appears to have been intended as a diagnostic counter that was never wired up to logging or telemetry.

**Prompt:**

```
BleEngine.kt (lines ~169-173) has:

var processedCount = 0
for ((uuid, bytes) in notificationChannel) {
    handleNotification(uuid, bytes)
    processedCount++
}

processedCount is incremented on every loop iteration but never read
anywhere in the file or codebase. Confirm this with a fresh search before
removing (it's possible something started reading it since the audit
that found this), then delete the variable declaration and the increment
line. The loop body (handleNotification(uuid, bytes)) is unaffected.

Show me the updated loop.
```

---

### L-5 — Remove the unreachable `MetricType.entries.contains()` check in `SyncValidator`

**Source:** BLE Audit F-2 (Dead or Unreachable Code)

**Mode:** Normal Mode — single branch removal with the exact reasoning given; no ambiguity.

**Problem:** `SyncValidator.kt:34` has `!MetricType.entries.contains(reading.metricType) -> "unknown MetricType: ${reading.metricType}"`. Since `reading.metricType` is statically typed as `MetricType` (a Kotlin enum), it is impossible to construct a `MetricReading` with a value outside the enum's defined entries — if WASM produced a malformed type string during deserialization, that would throw during deserialization itself, before ever reaching this validator. This branch can never fire.

**Prompt:**

```
SyncValidator.kt:34 has a validation branch:

!MetricType.entries.contains(reading.metricType) -> "unknown MetricType: ${reading.metricType}"

reading.metricType is statically typed as MetricType, a Kotlin enum. It
is impossible to construct a MetricReading instance with a metricType
value that isn't one of the enum's defined entries — if a WASM module
produced a malformed type string, deserialization itself would throw
before a MetricReading object with that field could ever exist. This
validation branch can never actually fire.

Fix: delete this branch from SyncValidator. If you want a defensive guard
against a driver author's mistake at a different layer, note that
MetricRouter.route()'s when expression is already an exhaustive match
over the full MetricType enum by construction — that already provides the
relevant guarantee at compile time, with no runtime check needed.

Show me the updated SyncValidator with the branch removed.
```

---

### L-6 — Extract a shared `computeFinalStatus()` function (currently triplicated identically)

**Source:** BLE Audit F-4 (Duplication / Near-Duplicate Functions)

**Mode:** Normal Mode — character-for-character identical logic in three places, fully specified extraction.

**Problem:** `DeviceSyncProcessor.process()` (lines 185–189), `DeviceSyncProcessor.processFromRaw()` (lines 343–347), and `DeviceReprocessor.reprocess()` (lines 121–125) all contain the exact same character-for-character identical block computing a `SyncStatus` from accepted/rejected counts.

**Prompt:**

```
The following block is character-for-character identical in three
locations:
- DeviceSyncProcessor.process() (lines ~185-189)
- DeviceSyncProcessor.processFromRaw() (lines ~343-347)
- DeviceReprocessor.reprocess() (lines ~121-125)

val finalStatus = when {
    totalRejected == 0 -> SyncStatus.SUCCESS
    totalAccepted == 0 -> SyncStatus.FAILED
    else -> SyncStatus.PARTIAL
}

Fix: extract this to a single package-internal top-level function in the
sync package: fun computeFinalStatus(accepted: Int, rejected: Int): SyncStatus,
containing the same when logic with accepted/rejected as parameters.
Replace all three identical inline blocks with a call to this function.

Show me the extracted function and the three updated call sites.
```

---

### L-7 — Extract a shared "mark session failed" helper (currently triplicated with only variable-name differences)

**Source:** BLE Audit F-5 (Near-Duplicate Functions)

**Mode:** Plan Mode — though the logic is simple, the extraction needs to correctly generalize across three call sites whose local variable names differ (`result`, `session`, `device`), so the new helper's parameter list needs care to make sure nothing is lost in the generalization.

**Problem:** `DeviceSyncProcessor.process()` (lines 262–278), `DeviceSyncProcessor.processFromRaw()` (lines 411–427), and `DeviceReprocessor.reprocess()` (lines 166–183) all share the same catch-block structure: inside `catch (e: Exception)`, run `runCatching { syncSessionRepository.update(SyncSession(... status = SyncStatus.FAILED, recordsImported = 0, errorMessage = e.message)) }`, then `throw e`. The only differences across the three are local variable names referencing the same underlying values (`result.driverId` vs `session.driverId` vs `device.driverId`, etc.) — the actual logic is identical.

**Prompt:**

```
DeviceSyncProcessor.process() (lines ~262-278),
DeviceSyncProcessor.processFromRaw() (lines ~411-427), and
DeviceReprocessor.reprocess() (lines ~166-183) all share the same
catch-block pattern: inside catch (e: Exception), wrap a
syncSessionRepository.update() call (setting status = SyncStatus.FAILED,
recordsImported = 0, errorMessage = e.message) in runCatching, then
rethrow e. The only differences across the three call sites are local
variable names referring to the same underlying values (e.g.
result.driverId vs session.driverId vs device.driverId) — the actual
logic and field values being set are identical.

Fix: extract a package-internal suspend helper:

suspend fun markSessionFailed(
    repo: SyncSessionRepository,
    id: Long,
    deviceId: Long,
    driverId: String,
    startedAt: Instant,
    error: Throwable
)

containing the runCatching { ... update ... } logic, parameterized on the
values that currently differ only in variable name across the three call
sites. Update all three catch blocks to call this helper instead of
duplicating the runCatching/update/rethrow logic inline (the `throw e`
after calling the helper should remain at each call site, since
rethrowing is the caller's responsibility, not the helper's).

Show me the extracted helper and the three updated catch blocks. Confirm
the helper's parameter list correctly captures everything each of the
three original call sites needs — don't drop any field that any of the
three was setting.
```

---

### L-8 — Extract a shared raw-payload-parsing loop (currently duplicated between `processFromRaw()` and `reprocess()`)

**Source:** BLE Audit F-6 (Near-Duplicate Functions)

**Mode:** Plan Mode — the two call sites differ in whether progress reporting happens inside the loop, so the extracted function's signature needs to accommodate an optional progress callback without forcing the no-progress caller to handle a callback it doesn't need.

**Problem:** `DeviceSyncProcessor.processFromRaw()` (lines 297–306) and `DeviceReprocessor.reprocess()` (lines 81–88) both iterate a `List<RawPayload>`, calling `driverRegistry.parseMetrics`, `.parseSleep`, and `.parseActivity` for each payload and accumulating results into three separate lists (metrics, sleep sessions, activities). The only difference: `reprocess()` calls `onProgress()` inside the loop on each iteration; `processFromRaw()` does not call any progress callback at all.

**Prompt:**

```
DeviceSyncProcessor.processFromRaw() (lines ~297-306) and
DeviceReprocessor.reprocess() (lines ~81-88) both contain a loop that
iterates a List<RawPayload> and calls driverRegistry.parseMetrics(),
.parseSleep(), and .parseActivity() for each payload, accumulating
results into three separate lists (metric readings, sleep sessions,
activities). The only difference between the two loops: reprocess() also
calls onProgress() on each iteration to report progress; processFromRaw()
has no progress reporting at all.

Fix: extract a package-internal function:

suspend fun parsePayloads(
    payloads: List<RawPayload>,
    manifest: <whatever manifest type is used>,
    registry: DriverRegistry,
    onProgress: ((Float) -> Unit)? = null
): Triple<List<MetricReading>, List<SleepSession>, List<Activity>>

containing the shared loop logic. The progress-fraction calculation
currently inside reprocess() (index / size * 0.80f, or whatever the
actual current formula is — check the real code) should move inside this
function, only invoked if onProgress is non-null. processFromRaw() calls
this with onProgress = null (its current default, no progress reporting);
reprocess() calls it passing its real progress callback.

Show me the extracted function and both updated call sites. Confirm
processFromRaw()'s behavior is unchanged (still no progress reporting)
and reprocess()'s progress reporting still fires at the same rate/values
as before.
```

---

### L-9 — Add the missing `rejectionReasons` collection to `DeviceReprocessor.reprocess()`

**Source:** BLE Audit F-7 (Duplication)

**Mode:** Normal Mode — the extraction target and the fix for the missing third instance are both clearly specified.

**Problem:** `DeviceSyncProcessor.process()` (lines 224–231) and `DeviceSyncProcessor.processFromRaw()` (lines 378–386) both build a `rejectionReasons` list via `buildList { readingResults.filterIsInstance<ValidationResult.Rejected<MetricReading>>().forEach { add(it.reason) }; ... }`. `DeviceReprocessor.reprocess()` has no equivalent at all — it returns no rejection reasons, meaning any UI surfacing rejection diagnostics for a reprocess operation will show an empty list even when records were genuinely rejected during that reprocess.

**Prompt:**

```
DeviceSyncProcessor.process() (lines ~224-231) and
DeviceSyncProcessor.processFromRaw() (lines ~378-386) both build a
rejectionReasons list using the same pattern:

buildList {
    readingResults.filterIsInstance<ValidationResult.Rejected<MetricReading>>()
        .forEach { add(it.reason) }
    // (plus whatever equivalent collection happens for sleep/activity
    // rejections — check the actual full pattern in the code)
}

DeviceReprocessor.reprocess() has no equivalent logic at all — it
currently returns no rejection reasons, meaning any caller that surfaces
rejection diagnostics after a reprocess will see an empty list even when
the reprocess actually rejected records.

Fix:
1. Extract the shared collection pattern into a package-internal function,
   e.g. fun collectRejectionReasons(readingResults: List<ValidationResult<MetricReading>>, <whatever other ValidationResult lists are involved>): List<String>,
   taking whatever ValidationResult lists are relevant (check the actual
   parameter types process() and processFromRaw() use).
2. Call this shared function from process() and processFromRaw(),
   replacing their duplicated inline buildList blocks.
3. Call the same shared function from DeviceReprocessor.reprocess() too,
   so it also produces real rejection reasons instead of none.
4. If DeviceReprocessor's result type (e.g. ReprocessSummary) doesn't
   currently have a field for rejection reasons, add one so this new
   data has somewhere to go and callers can actually see it.

Show me the extracted function, the two simplified call sites in
DeviceSyncProcessor, and the updated DeviceReprocessor.reprocess()
(including any result-type field addition needed).
```

---

### L-10 — Document (or fix) the missing DB-merge lookup in `DeviceReprocessor`'s sleep session handling

**Source:** BLE Audit F-10 (Inconsistent Patterns)

**Mode:** Plan Mode — this is a behavioral question (is force-replace semantics intentional for reprocess?) requiring a decision before either documenting or changing it.

**Problem:** `DeviceSyncProcessor.process()` and `processFromRaw()` (lines 453–464) both call `buildMergedSessions()`, which fetches existing sleep sessions for the same `(driverId, date)` from the database before calling `mergeSleepSessions()` — preserving any data already present that the new sessions don't overwrite. `DeviceReprocessor.reprocess()` (line 108) calls `mergeSleepSessions()` directly with only the incoming sessions, never fetching existing rows first — meaning the reprocess path can clobber a sleep session that was partially updated by a different source since the raw data was originally stored. The audit notes this may be intentional force-replace behavior for reprocess specifically, but it's undocumented and makes the reprocess path behave differently from the other two in a way that isn't obvious from reading the code.

**Prompt:**

```
DeviceSyncProcessor.process() and processFromRaw() (around lines 453-464)
both call buildMergedSessions(), which fetches existing sleep sessions
for the same (driverId, date) from the database BEFORE calling
mergeSleepSessions() — this means any data already present that the new
sessions don't overwrite is preserved. DeviceReprocessor.reprocess()
(line ~108) calls mergeSleepSessions() directly with only the incoming
sessions, skipping the existing-data fetch entirely. This means a
reprocess can silently clobber a sleep session that was partially updated
by some other source (e.g. a manual edit, or a different sync) since the
original raw data was stored.

This may be intentional — reprocess could reasonably be meant as a
"force-replace with corrected parsing" operation that should NOT merge
with whatever's currently there. But there's no comment anywhere stating
this is the intent, so it's currently indistinguishable from an oversight.

Decide first: should DeviceReprocessor.reprocess() use force-replace
semantics (current behavior) or should it merge with existing DB state
like the other two paths do? Consider: reprocess exists specifically to
re-run historical raw data through corrected/updated parsing logic — does
that purpose imply the corrected result should win outright, or that it
should respect other changes made since the original processing?

Based on your conclusion (or mine, if you ask and I confirm):

(a) If force-replace IS the intended behavior: add a clear comment at the
    mergeSleepSessions() call site in DeviceReprocessor.reprocess()
    explicitly stating "intentional force-replace — does not merge with
    existing DB state, unlike process()/processFromRaw()" so a future
    reader understands this is deliberate, not a gap.

(b) If it should match the other two paths: change
    DeviceReprocessor.reprocess() to call buildMergedSessions() the same
    way process()/processFromRaw() do, fetching existing sessions before
    merging.

Tell me your recommendation and reasoning before implementing either
direction — this changes (or documents changing) real data-handling
behavior for the reprocess path.
```

---

### L-11 — Note the structural duplication in the 10 near-identical entity classes (lower priority than DAO/repo collapse)

**Source:** DB Audit Finding 1-C (Category 1)

**Mode:** Normal Mode — this is primarily a documentation/acknowledgment prompt rather than a structural change, since the audit itself notes entity inheritance isn't practical with Room.

**Problem:** All 10 `*ReadingEntity` classes share 7 fields (`id`, `recordedAt`, `createdAt`, `source`, `driverId`, `confidence`, `metaJson`) and add only 1–2 type-specific value fields each (`bpm`, `rmssdMs`, `percentage`, etc.). The unique index annotation (`@Index(value = ["driver_id", "recorded_at"], unique = true)`) is identical across all 10. The audit explicitly notes this is lower-priority than the DAO/repository duplication (addressed in H-3) because Room entities cannot practically use inheritance for persistence — each entity needs its own concrete class for Room's schema generation regardless of shared fields.

**Prompt:**

```
All 10 *ReadingEntity classes (HrReadingEntity, HrvReadingEntity, etc.)
share the same 7 fields — id, recordedAt, createdAt, source, driverId,
confidence, metaJson — and the same unique index annotation
(@Index(value = ["driver_id", "recorded_at"], unique = true)), each
adding only 1-2 type-specific value fields (e.g. bpm for HR, rmssdMs for
HRV, percentage for SpO2).

Room's persistence model means these entities cannot practically share
an implementation via inheritance — each @Entity class needs its own
concrete declaration for Room's compile-time schema generation, even
though the field set is largely identical. This is fundamentally
different from the DAO/repository duplication across these same 10
types, which IS collapsible via a shared base (check the current state
of the 10 *ReadingDao and *ReadingRepository files — they may still be
10 independent near-identical files, or may already have been
consolidated into a shared base abstraction by a prior cleanup pass). Do
not attempt to introduce class inheritance or a shared base entity here
for the entities themselves — that would fight Room's model rather than
work with it, regardless of what's happened to the DAO/repository layer.

What IS worth doing: add a short KDoc comment at the top of one
representative entity file (or a short markdown note in the data/model/
directory, whichever fits this codebase's documentation conventions
better) explaining that all 10 *ReadingEntity classes intentionally share
this 7-field shape plus the same unique index, and that this repetition
is a deliberate consequence of Room's persistence model rather than an
oversight — so a future reader doesn't mistake this for the same kind of
problem as the DAO/repository layer (whether or not that layer has
already been consolidated).

Show me where you placed this note and its content.
```

---

### L-12 — Decide whether `SleepStageRepository` should expose a domain model instead of the raw `SleepStageEntity`

**Source:** DB Audit Category 2 note (Unnecessary Abstraction — not a failure, but flagged as worth reviewing)

**Mode:** Plan Mode — changing a repository's exposed type is an API-shape decision; consider the actual benefit before introducing a new domain model type that didn't exist before.

**Problem:** `SleepStageRepository` exposes `SleepStageEntity` directly in its public contract — callers of `getReadingsInRange()` receive raw `SleepStageEntity` objects, meaning the persistence layer leaks through the repository abstraction boundary. This is inconsistent with `SleepRepository`, which maps to/from a `SleepSession` domain model rather than exposing its underlying entity directly. The audit frames this as worth reviewing only if testability against a non-Room backend is a real future goal — not a clear-cut bug.

**Prompt:**

```
SleepStageRepository exposes SleepStageEntity directly in its public
interface — e.g. getReadingsInRange() returns SleepStageEntity objects,
not a domain-level type. This is inconsistent with SleepRepository, which
maps to/from a SleepSession domain model rather than exposing
SleepSessionEntity (or equivalent) directly to callers.

This is NOT a clear bug — exposing the entity directly is reasonable if
there's no expectation of ever swapping the persistence backend or
needing a domain representation that differs from the storage
representation. It only matters if testability against a non-Room
backend, or a domain model that diverges from the storage shape, is a
real goal.

Investigate first: is there any current or planned need for
SleepStageRepository's callers to work with something other than the raw
Room entity (e.g. a UI screen needing fields not present on the entity,
or test code that would benefit from a fake not tied to Room's
@Entity-annotated class)? Check current callers of
SleepStageRepository.getReadingsInRange() to see if any are already
doing ad-hoc mapping from SleepStageEntity to something else, which would
be a sign this is already an active pain point.

If you find real evidence this matters: introduce a SleepStage domain
model and have SleepStageRepository map to/from it, matching
SleepRepository's existing pattern.

If you find no real evidence this matters today: leave SleepStageRepository
as-is, but tell me so I have the option to revisit later if a concrete
need comes up (e.g. a non-Room test backend, or a UI screen needing a
shape the entity doesn't provide).

Show me what you found and your recommendation before making any change.
```

---

### L-13 — Trim restated documentation in `docs/DRIVER_AUTHORING_GUIDE.md`

**Source:** Driver Audit's Documentation Audit section (BATTERY routing restated 3x, in-progress sleep session constraint restated 3x, "Stateless Parsing" section overlapping an earlier WASM-state callout)

**Mode:** Normal Mode — all three issues are minor wording/structure trims with the exact locations and fixes specified; bundled into one prompt since each individually is too small to warrant its own session.

**Problem:** The driver authoring guide's documentation audit found the guide largely accurate and well-structured (most of it PASSED), but flagged three specific instances of restating the same point more times than needed: (1) "BATTERY is routed to device metadata, not metric_readings_staging" appears verbatim at lines 97–99, 371–377, and 651–655 — three times, when two (the reference table plus a callout at point of use) would be sufficient. (2) "If sleepEndMs is 0 or ≤ sleepStartMs, return 0, do not emit" appears at lines 398–404, 911–914, and 1088 — three times, when two would suffice. (3) The "Stateless Parsing is Strongly Preferred" section (lines 515–527) re-explains the same WASM-state trap/re-instantiation scenario already covered by an earlier callout (lines 72–79) before adding genuinely new guidance about accumulator design — the overlap could be replaced with a one-line cross-reference. Two other documentation findings checked out as fine and need no action: `specVersion` documentation being split across three locations is appropriate (each location serves a different purpose), and no speculative undocumented-capability sections were found.

**Prompt:**

```
docs/DRIVER_AUTHORING_GUIDE.md has three spots where the same point is
restated more times than necessary:

1. "BATTERY is routed to device metadata, not metric_readings_staging" —
   appears at lines ~97-99, ~371-377, and ~651-655 (three times: the
   routing table, the Date Attribution Rules section, and a callout
   under Supported Metric Types). Keep two occurrences (the table, for
   reference, and one callout at the most useful point of use) and
   remove the third repetition. Pick whichever two locations best serve
   a reader looking the information up vs. a reader encountering it
   while reading sequentially.

2. "If sleepEndMs is 0 or <= sleepStartMs, return 0, do not emit" —
   appears at lines ~398-404, ~911-914, and ~1088 (Date Attribution
   Rules, the parseSleep schema callout, and the checklist). Keep two
   occurrences and remove the third, using the same reasoning as above.

3. The "Stateless Parsing is Strongly Preferred" section (lines ~515-527)
   re-explains the same WASM-state trap/re-instantiation scenario already
   covered by an earlier callout (lines ~72-79), before adding genuinely
   new guidance about accumulator design that the earlier callout doesn't
   cover. Replace the re-explanation at the start of this later section
   with a one-line cross-reference back to the earlier callout (e.g. "See
   [earlier section] for why WASM state cannot be trusted across calls.")
   and keep only the new accumulator-design guidance that follows.

Do NOT change anything else in the guide — specVersion's documentation
being split across the top-level fields table, the Memory Layout section,
and the checklist is appropriate (each serves a different purpose at a
different point in the reading) and should be left as-is. No speculative
undocumented-capability sections were found elsewhere in the guide either
— don't go looking for more to trim beyond these three specific spots.

Show me the diff for each of the three changes.
```

---

## NEEDS HUMAN JUDGMENT

These items were explicitly flagged by the audits as requiring a decision only you can make — either because the code's intent can't be determined from reading it alone, or because the "right" answer depends on product plans the auditor has no visibility into. Each is written as an investigate-then-decide prompt rather than a blind fix. Do not let Claude Code make these calls unilaterally; review its findings and confirm a direction before it changes anything.

### NHJ-1 — Driver: is double-validation in `DriverStorage` (save-time + load-time) load-bearing?

**Source:** Driver Audit J-1

**Problem:** `ManifestValidator.validate()` runs both when a driver is saved (`DriverStorage.kt:33–34`, before writing the file) and again every time a driver is loaded from disk (`DriverStorage.kt:48–50`). The save-time check alone guarantees only valid JSON ever reaches the drivers directory under normal operation. The load-time check exists to catch two scenarios: file corruption after the fact (unlikely but possible), or validation rules that tightened between app versions — a driver that was valid under an older app version could fail validation under a newer one's stricter rules. The audit can't determine from the code alone which scenario, if either, has actually occurred historically — only you know whether validation rules have changed across deployed versions.

**Prompt:**

```
DriverStorage.kt calls ManifestValidator.validate() in two places:
saveDriver() (lines ~33-34, before writing the file to disk) and
loadAllDrivers() (lines ~48-50, every time a driver is loaded from disk
at app start or whenever drivers are refreshed).

The save-time validation alone guarantees that only valid JSON reaches
the drivers directory under normal operation. The load-time validation
re-checks every driver again on every load, which only matters if either:
(a) the driver file was corrupted on storage after being saved validly,
    or
(b) validation rules have been tightened in a later app version than the
    one that originally saved the driver, so a driver valid under an
    older app version could now fail under current rules.

Investigate: has ManifestValidator's validation logic changed at all
across released versions of this app (check git history / changelog for
ManifestValidator.kt)? If validation rules have always been stable, the
load-time check is pure redundant overhead (a short CPU pass per driver
per app start, with no realistic chance of ever catching anything the
save-time check didn't already guarantee). If validation rules have
changed even once, the load-time check is load-bearing and protects users
who saved a driver under an older app version.

Tell me what you find in the git history, and I'll decide whether to:
(a) keep both validations as-is (if rules have changed historically, or
    might reasonably change again and this is cheap insurance), or
(b) remove the load-time validation and rely on save-time validation
    alone (if rules have always been stable and the drivers directory is
    confirmed app-private with no path for external writes).

Do not remove either validation call without my confirmation — this
affects whether a corrupted or stale driver file fails safely or could
cause a crash/bad-data scenario downstream.
```

---

### NHJ-2 — Driver: is the spec v1 memory layout branch in `WasmDriverEngine` dead code, or does it protect drivers already in the field?

**Source:** Driver Audit J-2

**Problem:** Both shipped drivers (`example_wasm.json` and `HumeBandDriver.json`) declare `specVersion: "2"`. The Kotlin data class default for an omitted `specVersion` is `"1"`. The v1 branch inside `callParse` (`WasmDriverEngine.kt:233–252`, plus the default in `WasmDriverManifest.kt:28`) logs a migration warning on every parse call when triggered. If no v1 driver has ever actually been distributed to real users, this branch — and the `isV2` flag plus two offset constants it requires — is dead weight that complicates `callParse` for no live benefit. If even one v1 driver is in the field (e.g. on a user's device, sideloaded, or distributed before v2 became standard), removing this branch silently breaks backward compatibility for those users with no warning.

**Prompt:**

```
WasmDriverEngine.kt's callParse() function (lines ~233-252) has a branch
for spec v1 memory layout handling, with the v1/v2 distinction also
present as a default value in WasmDriverManifest.kt (line ~28, where
specVersion defaults to "1" if omitted from the manifest JSON). Both
currently-shipped drivers (example_wasm.json and HumeBandDriver.json)
explicitly declare specVersion: "2", meaning neither shipped driver
exercises the v1 branch today. The v1 branch logs a migration warning via
Timber on every parse call when triggered, and requires maintaining an
isV2 flag plus two separate memory-offset constants that wouldn't be
needed if only v2 existed.

This decision depends entirely on information only you have: has a v1
driver manifest ever been distributed to real users (sideloaded,
distributed via an older app version, or otherwise present on any device
in the field today)? The code itself gives no signal either way — there's
no way to determine this by reading source alone.

If you confirm no v1 driver has ever been distributed to users (or none
remain in the field today): remove the v1 branch from callParse, remove
the isV2 flag and the two v1-specific offset constants, and change
WasmDriverManifest's specVersion default to "2" (or make it required with
no default, if that's a cleaner signal of the simplified contract). This
materially simplifies callParse.

If you tell me a v1 driver IS or MIGHT BE in the field: leave the branch
as-is. Optionally, if the migration warning's log level/frequency seems
like more noise than necessary for a "temporarily supported, expected to
phase out" path, consider downgrading its log verbosity — but don't
remove the underlying support.

Ask me directly which case applies before making any change — this is
not something to infer from the code.
```

---

### NHJ-3 — Driver: is the `MatchConfidence.PROBABLE` resolution path in `DriverRegistry` worth keeping?

**Source:** Driver Audit J-3

**Problem:** `DriverRegistry.resolve()` (lines 71–73) tries `CERTAIN`-confidence drivers first, then falls back to `PROBABLE` drivers. Both currently shipped drivers declare `matchConfidence: CERTAIN`. The `PROBABLE` branch is reachable code (not dead in the strict sense — a `PROBABLE` driver manifest would actually exercise it) but has no current manifest that exercises it, meaning it's effectively untested in practice. The audit notes the complexity cost of keeping it is low (a single `firstOrNull` call), so removal is only worth it if the `MatchConfidence.PROBABLE` enum value is confirmed to have no realistic future use (e.g. for a multi-manufacturer device scenario where the same BLE UUID maps to different device models that can only be probabilistically distinguished).

**Prompt:**

```
DriverRegistry.resolve() (lines ~71-73) tries CERTAIN-confidence drivers
first, then falls back to a second pass for PROBABLE-confidence drivers.
Both currently shipped drivers (example_wasm.json, HumeBandDriver.json)
declare matchConfidence: CERTAIN, so the PROBABLE fallback path is
reachable code but has no current manifest that actually exercises it —
it's untested in practice, though not technically dead.

This is a low-stakes decision either way — the complexity cost of keeping
this is low (a single firstOrNull call for the fallback pass). It's only
worth removing if there's no realistic future use for the PROBABLE
confidence tier at all.

Is there a concrete scenario where a future driver would need
PROBABLE-confidence matching — e.g. a multi-manufacturer device where the
same BLE service/characteristic UUID is shared across different device
models that can only be distinguished probabilistically (not via a
certain, unique identifier)? If you're not sure, ask me directly rather
than assuming either way.

If there's no realistic future use: remove the PROBABLE fallback pass
from resolve() and remove MatchConfidence.PROBABLE from the enum (check
first whether anything else references this enum value before removing
it).

If there IS a plausible future use, even if not imminent: leave this
as-is — the cost of keeping it is low enough that "might be needed
later" is a reasonable bar to clear here, unlike some of the
higher-cost scaffolding decisions elsewhere in this audit (e.g. the
DeviceDriver/MetricProcessor interfaces, which have a much higher
ongoing cost).

Tell me your finding and recommendation before removing anything.
```

---

### NHJ-4 — DB: confirm the `DailySummaryWorker` timezone question and close out the cross-referenced fix

**Source:** DB Audit NHJ-1, the same underlying fact addressed as a hard inconsistency in M-9 above

**Problem:** This is not a separate fix from M-9 — it's the same fact, framed as an open question rather than a stated inconsistency. The DB audit's main findings section frames this as "DailySummaryWorker uses local timezone for day boundaries; all other components use UTC" (a FAILED finding, addressed in M-9). The audit's own Needs Human Judgment section frames the identical fact as an open question: is the local-timezone window in `DailySummaryWorker.kt:65–68` intentional design (summary rows should cover the user's local calendar day, even though raw readings are stored in UTC) or an actual bug (readings written between UTC midnight and local midnight get attributed to the wrong summary day)?

**Prompt:**

```
This is the same underlying fact as the DailySummaryWorker timezone fix
above (M-9 in this document, if you're working through prompts in order)
— do not investigate or fix this twice. If M-9 has already been resolved
(the investigation into whether the UI displays local or UTC calendar
days, and the resulting decision about which side — DailySummaryWorker or
the UTC-based writers — needed to change), there is nothing further to do
here.

If you're encountering this prompt before M-9: go to M-9 first. It
contains the full investigation steps (checking what the UI actually
displays for DailySummary.date) and the resulting decision tree. This
entry exists only to flag that the audit surfaced the same fact twice —
once as a stated inconsistency (Category 6, Finding 6-B) and once as an
open judgment question (NHJ-1) — and both should be resolved by the same
single investigation and decision, not two separate ones.
```

---

### NHJ-5 — DB: confirm the `MetricRouter` conflict-strategy divergence and close out the cross-referenced fix

**Source:** DB Audit NHJ-2, the same underlying fact addressed in M-8 above

**Problem:** Same pattern as NHJ-4 — this is the identical fact as M-8 (the `REPLACE` vs `IGNORE` divergence between `route()` and `routeAll()`), surfaced once as a stated inconsistency (Finding 6-A) and once as an open judgment question (NHJ-2), which notes the three-path design (live / raw-replay / corrected-reprocess) may be intentionally distinct and asks that the intent be verified before acting on Finding 6-A.

**Prompt:**

```
This is the same underlying fact as the MetricRouter conflict-strategy
fix above (M-8 in this document) — do not investigate or fix this twice.
If M-8 has already been resolved (the investigation into whether
route()'s REPLACE and routeAll()'s IGNORE conflict strategies are
deliberate, and the resulting decision to either document the rationale
or unify the strategies), there is nothing further to do here.

If you're encountering this prompt before M-8: go to M-8 first. It
contains the full investigation steps and the resulting decision tree.
This entry exists only to flag that the audit surfaced the same fact
twice — once as a stated inconsistency (Category 6, Finding 6-A) and once
as an open judgment question (NHJ-2) — and both should be resolved by the
same single investigation and decision, not two separate ones.
```

---

### NHJ-6 — BLE: is `DeviceSyncProcessor`'s `shutdown()`/`pruneScope` lifecycle contract misleading rather than broken?

**Source:** BLE Audit J-1

**Problem:** `DeviceSyncProcessor` creates its own `CoroutineScope(Dispatchers.IO + SupervisorJob())` and exposes a `shutdown()` method to cancel it, called from `Application.onTerminate()` (`AthleteDataApplication.kt:79`). Android's own documentation states `onTerminate()` is not called in production builds — only in emulator/test contexts. This means in any real device deployment, `pruneScope` is never actually cancelled via this path. Since `DeviceSyncProcessor` is a `@Singleton`, this is not a real leak (the scope simply lives exactly as long as the app process, which is fine) — but the existence of a `shutdown()` method implies more lifecycle control than actually exists in practice, which could mislead a future reader into thinking this scope is properly cleaned up when it never is outside test contexts.

**Prompt:**

```
DeviceSyncProcessor.kt (lines ~48-52) creates its own
CoroutineScope(Dispatchers.IO + SupervisorJob()) for fire-and-forget prune
operations, and exposes a shutdown() method that cancels this scope.
shutdown() is called from AthleteDataApplication.onTerminate() (line
~79). Android's documentation states onTerminate() is not called in
production builds — only in emulator/test environments. This means in
any real device deployment, shutdown() (and therefore the scope
cancellation) never actually fires.

Since DeviceSyncProcessor is a @Singleton, this is NOT a real leak — the
scope simply lives exactly as long as the app process does, which is a
perfectly normal outcome for a singleton-owned background scope. The
issue is purely that shutdown()'s existence implies a degree of lifecycle
control that doesn't actually exist outside of test/emulator contexts,
which could mislead a future reader.

This is a low-stakes choice between two reasonable options — tell me
which you'd prefer, or implement your recommendation and tell me why:

(a) Leave the current shape as-is. It's harmless in production (no real
    leak) and the shutdown() path still provides real value in
    test/emulator contexts and for testing hygiene generally. Optionally
    add a comment at the shutdown() declaration noting that
    onTerminate() (and therefore this method) is not invoked in
    production builds, so a future reader isn't confused into thinking
    this is the app's normal cleanup path.

(b) Replace pruneScope with GlobalScope (or a shared
    application-level scope already used elsewhere in the codebase, if
    one exists) for the fire-and-forget prune operations specifically,
    making the "this is never explicitly cancelled in production"
    reality explicit in the code's structure rather than implied by an
    Android platform behavior a reader would need to already know about.
    Remove the now-misleading shutdown() method and its
    onTerminate() wiring if you take this path.

Tell me which direction you're taking before implementing — this is a
judgment call between "harmless as-is, just clarify with a comment" and
"restructure to make the real lifecycle explicit," not a bug fix.
```

---

### NHJ-7 — BLE: verify `DriverRegistry`'s WASM lifecycle before deciding whether the `isWasmLoaded` check placement in `BleEngine` is correct or misplaced

**Source:** BLE Audit J-2

**Problem:** `BleEngine.kt:383–388` checks `!driverRegistry.isWasmLoaded(manifest)` immediately after `parseMetrics`/`parseSleep`/`parseActivity` have already returned results for a packet — i.e. after WASM has already run successfully for that packet. If WASM had failed to initialize, parsing would have returned empty results quietly rather than throwing, so this check firing is the correct signal that something is wrong on the packet immediately following a total WASM failure. But the check could also appear to fire spuriously mid-stream if WASM can be hot-swapped or unloaded between packets during a driver-reload scenario — whether that scenario is even possible depends on `DriverRegistry`'s WASM lifecycle, which was explicitly out of scope for the BLE audit that flagged this.

**Prompt:**

```
BleEngine.kt (lines ~383-388) has:

if (!driverRegistry.isWasmLoaded(manifest)) {
    _connectionState.value = BleConnectionState.Error(...)
    return
}

This check runs immediately AFTER parseMetrics/parseSleep/parseActivity
have already returned results for the current packet — meaning WASM
already executed successfully for this packet by the time this check
runs. If WASM had failed to initialize at all, parsing would have
returned empty results quietly (not thrown), so this check firing is
actually the correct signal to catch a total WASM failure — but only on
the packet immediately AFTER the failure happened, not the one during
which it happened.

Whether this placement is correct or subtly wrong depends entirely on
DriverRegistry's WASM lifecycle, which needs investigation:

1. Is WASM loading all-or-nothing at startup (loaded once when the driver
   is first registered, never reloaded for the lifetime of that driver
   instance)? If so, this check can only ever fire if WASM never loaded
   in the first place — meaning it should fire on the FIRST packet, not
   require warming through an initial successful parse. Investigate
   whether that's actually possible given how isWasmLoaded() and the
   parse functions interact — if WASM truly never loaded, would the
   parse calls before this check have already thrown or behaved
   differently in a way that makes reaching this check at all
   unreachable in that scenario? Resolve this ambiguity by reading
   DriverRegistry's actual WASM load/check logic carefully.

2. Can WASM be hot-swapped or unloaded mid-session (e.g. during a driver
   reload while a BLE connection is already active and receiving
   packets)? If yes, this check is correctly placed exactly where it is
   — it would catch a real mid-stream WASM unload event between packets,
   which is exactly the kind of transient failure this check seems
   designed for.

Investigate DriverRegistry's WASM lifecycle thoroughly (this was
explicitly out of scope for the audit that flagged this, so you're
covering new ground here) and tell me which of the two scenarios actually
applies. Do not move or remove this check until you've confirmed which
scenario is real — moving it incorrectly could either introduce a false
error on the first packet of a connection (if scenario 1 applies and the
check is moved earlier without accounting for it) or miss a real
mid-stream failure (if scenario 2 applies and the check is removed).
```

---

## Summary

| Source Audit | High | Medium | Low | Needs Human Judgment | Total |
|---|---|---|---|---|---|
| Driver System | 2 (H-1, H-2 — shared with DB/BLE) | 4 (M-1 through M-4) | 3 (L-1, L-2, L-3) + L-13 (doc trim) | 3 (NHJ-1, NHJ-2, NHJ-3) | 12 findings → ~13 prompts |
| Database Layer | 2 (H-1, H-3 — H-1 shared with Driver) | 7 (M-5 through M-11, M-8 shared with BLE) | 2 (L-11, L-12) | 2 (NHJ-4, NHJ-5 — cross-references) | 15 findings → 13 prompts |
| BLE System | 1 (H-2 — shared with Driver) | 4 (M-8 shared, M-12, M-13, M-14) | 7 (L-4 through L-10) | 2 (NHJ-6, NHJ-7) | 13 findings → 13 prompts |
| **Totals** | **4 distinct prompts** | **14 distinct prompts** | **13 distinct prompts** | **7 distinct prompts** | **40 findings → 38 prompts** |

(40 findings collapse to 38 prompts because H-1 merges Driver F-1 + DB Finding 8-A, and H-2 merges Driver F-2 + BLE F-3 — each pair was the same underlying issue independently flagged by two different audits.)

**If you only want to act on a handful of these,** each audit's own summary section converges on similar highest-leverage picks. Across all three systems, the strongest candidates for "most value, least risk" are:

1. **H-1** (unify MetricRouter's entity mapping) — flagged as the single highest-leverage change by the Driver audit, and independently by the DB audit.
2. **H-4** (remove confirmed-dead DAO methods) — zero risk, confirmed dead by exhaustive search, ~60 method declarations removed.
3. **H-2** (centralize DEDICATED_METRIC_TYPES) — a five-minute change flagged independently by two audits as worth doing.

Everything else is genuinely optional — these are leanness improvements, not bugs, so there's no requirement to act on all (or even most) of them. Pick based on which parts of the codebase you expect to keep touching: a part you're about to extend heavily benefits more from cleanup than a stable part you rarely revisit.