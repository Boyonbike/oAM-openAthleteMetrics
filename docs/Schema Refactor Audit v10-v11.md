# Schema Refactor Audit — v10 → v11

**Date:** 2026-06-16
**Audited by:** Claude Code (read-only pass, no files changed)
**Scope:** 5-session database schema refactor introducing 10 dedicated time-series tables,
sleep_stages, updated DailySummaryEntity, MetricProcessor driver interface, rewritten
SeederService and DailySummaryWorker.

---

## PASSED

### Schema Integrity

- **10 new time-series entities — standard columns** (`HrReadingEntity`, `HrvReadingEntity`,
  `SpO2ReadingEntity`, `RespirationReadingEntity`, `SkinTempReadingEntity`,
  `StepsReadingEntity`, `BloodPressureReadingEntity`, `GlucoseReadingEntity`,
  `ActiveCalorieReadingEntity`, `TotalCalorieReadingEntity`): all carry `id`, `recorded_at`,
  `created_at`, `source`, `driver_id`, `confidence`, `meta_json`.

- **`HrvReadingEntity` has `computed_by_version`** — `HrvReadingEntity.kt:29`.

- **`SleepStageEntity` has `computed_by_version`** — `SleepStageEntity.kt:50`.

- **`SleepStageEntity` unique index is `(session_id, start_ms)`** — `SleepStageEntity.kt:32`:
  `Index(value = ["session_id", "start_ms"], unique = true)`.

- **`SleepStageEntity` has FK to `sleep_sessions(id)` ON DELETE CASCADE** —
  `SleepStageEntity.kt:24–31`.

- **`SleepSessionEntity` has NO `stages_json` column** — confirmed; only `id`, `date`,
  `sleep_start_ms`, `sleep_end_ms`, `duration_minutes`, `source`, `driver_id`.

- **`DailySummaryEntity` has all 16 required new columns** — all present: `computed_by_version`,
  `sleep_deep_minutes`, `sleep_light_minutes`, `sleep_rem_minutes`, `sleep_awake_minutes`,
  `skin_temp_avg_c`, `skin_temp_min_c`, `skin_temp_max_c`, `respiration_avg`, `hrv_min_ms`,
  `hrv_max_ms`, `spo2_min_pct`, `spo2_max_pct`, `steps_active_minutes`, `total_calories`,
  `active_calories`.

- **`MetricReadingStagingEntity` uses table name `metric_readings_staging`** —
  `MetricReadingStagingEntity.kt:21`.

- **All 21 entities registered in `AppDatabase`** — `AppDatabase.kt:27–49`: MetricReadingStaging,
  SleepSession, DailySummary, DailyContext, QuestionDefinition, QuestionResponse, Activity,
  Device, SyncSession, RawDeviceData, HrReading, HrvReading, SpO2Reading, RespirationReading,
  SkinTempReading, StepsReading, BloodPressureReading, GlucoseReading, ActiveCalorieReading,
  TotalCalorieReading, SleepStage.

- **`AppDatabase.version = 11`** — `AppDatabase.kt:50`.

---

### Migration

- **`MIGRATION_10_11` explicitly DROPs every v10 table** including `metric_readings` — all
  present: `raw_device_data`, `sync_sessions`, `question_responses`, `activities`,
  `metric_readings`, `sleep_sessions`, `daily_summary`, `daily_context`, `devices`,
  `question_definitions` — `AppDatabase.kt:308–329`.

- **Drop order is FK-safe (children before parents)** — `raw_device_data` (child of
  sync_sessions) first; `sync_sessions` (child of devices) second; `question_responses` (child
  of question_definitions) before `question_definitions`; `sleep_sessions` before `devices`.

- **All tables and UNIQUE indexes recreated** — every table is followed by its UNIQUE index DDL.
  All 18 unique indexes confirmed in the recreate phase.

- **`fallbackToDestructiveMigration` removed** — no hits in the codebase.

- **9 lifestyle questions reseeded** — `LIFESTYLE_SEEDS` has 9 entries; all 9 are inserted
  in `MIGRATION_10_11`. *(See FAILED-3 for a name-casing issue.)*

---

### Repositories

- **All 12 required repository interfaces and Room implementations exist** — `HrReadingRepository`
  + `RoomHrReadingRepository`, `HrvReadingRepository` + `RoomHrvReadingRepository`,
  `SpO2ReadingRepository` + `RoomSpO2ReadingRepository`, `RespirationReadingRepository` +
  `RoomRespirationReadingRepository`, `SkinTempReadingRepository` +
  `RoomSkinTempReadingRepository`, `StepsReadingRepository` + `RoomStepsReadingRepository`,
  `BloodPressureReadingRepository` + `RoomBloodPressureReadingRepository`,
  `GlucoseReadingRepository` + `RoomGlucoseReadingRepository`,
  `ActiveCalorieReadingRepository` + `RoomActiveCalorieReadingRepository`,
  `TotalCalorieReadingRepository` + `RoomTotalCalorieReadingRepository`,
  `SleepStageRepository` + `RoomSleepStageRepository`,
  `MetricReadingStagingRepository` + `RoomMetricReadingStagingRepository`.

- **All repositories bound in `DatabaseModule`** — `DatabaseModule.kt:84–142` has `@Binds`
  entries for all 12 new repositories plus all pre-existing ones. All DAO `@Provides` entries
  are also present.

- **`SleepStageRepository` exposes `getStagesForSession()` and `getStagesInRange()`** —
  `SleepStageRepository.kt:14` and `:17`; correctly implemented in `RoomSleepStageRepository`
  and `SleepStageDao`.

---

### Driver Architecture

- **`MetricProcessor` interface exists with exactly two methods** — `MetricProcessor.kt:14`
  (`onReading`) and `:22` (`onSyncComplete`). Return types and signatures match the spec.

- **`DeviceDriver` interface has `createProcessor()` with default `null`** —
  `DeviceDriver.kt:12`.

- **`BleEngine` calls `createProcessor()` on fresh connect** — `BleEngine.kt:530`:
  `currentProcessor = (activeManifest as? DeviceDriver)?.createProcessor()` inside the
  `resetRetries = true` branch.

- **`BleEngine` resets processor to `null` on disconnect** — `BleEngine.kt:304`.

- **`BleEngine` calls `processor?.onReading()` after every `parse()` call** —
  `BleEngine.kt:368`.

- **`BleEngine` calls `processor?.onSyncComplete()` at quiescence and routes returned readings**
  — `BleEngine.kt:415–418`.

- **`MetricType` enum contains all 13 required values** — HR, HRV, RHR, SPO2, STEPS,
  SLEEP_STAGE, BATTERY, RESPIRATION, SKIN_TEMP, ACTIVE_CALORIES, TOTAL_CALORIES,
  BLOOD_PRESSURE, GLUCOSE all present in `MetricType.kt`.

- **Routing table covers all `MetricType` values** — explicit `when` branches for the 10
  dedicated types plus SLEEP_STAGE and BLOOD_PRESSURE; `else ->` routes everything else
  (RHR, BATTERY, BODY_TEMP, etc.) to staging.

- **`SLEEP_STAGE` routes to staging with `pending_sleep_stage` flag and TODO comment** —
  `BleEngine.kt:950–953`.

---

### Seeder

- **`SeederService` writes to all 10 dedicated tables and NOT to `metric_readings_staging`
  for known types** — all 10 typed `insertAll()` calls confirmed; no staging writes for
  known metric types.

- **`clearSeederData()` calls `deleteBySource(SEEDER)` on every dedicated table including
  `sleep_stages`** — `SeederService.kt:75–98`: 10 typed repos + `sleepStageRepository` +
  `sleepRepository` in FK-safe order (stages before sessions).

- **Sleep stage seeder: session first, capture id, then insert stages** —
  `SeederService.kt:202–207`: `sleepRepository.insert(session)` →
  `getSessionForDateOnce(date)` → `sleepStageRepository.insertAll(buildSleepStages(inserted.id, ...))`.

- **Sleep stage rows are perfectly contiguous** — `buildSleepStages()` sets `cur = blockEnd`
  after each row; the next row's `startMs` equals the previous row's `endMs`.

- **Sum of `durationMinutes` equals parent `durationMinutes` exactly** —
  `SeederService.kt:592–598`: safety-net absorbs any surplus into the last stage.

- **Random seed 42** — `SeederService.kt:108`: `val rng = Random(42L)`.

- **`StateFlow<SeederState>` progress reporting** — `SeederViewModel.kt:25–26`:
  `MutableStateFlow<SeederState>` exposed as `StateFlow`; `SeederState.Running(progress)` emitted
  on each progress callback.

- **Three public functions present** — `seedThirtyDays()`, `seedToday()`, `clearSeederData()`
  in both `SeederService` and `SeederViewModel`.

---

### Daily Summary Worker

- **Reads from dedicated tables only** — `DailySummaryWorker` injects `HrReadingDao`,
  `HrvReadingDao`, `SpO2ReadingDao`, `SkinTempReadingDao`, `RespirationReadingDao`,
  `StepsReadingDao`, `ActiveCalorieReadingDao`, `TotalCalorieReadingDao`, `SleepSessionDao`,
  `SleepStageDao`. No `MetricReadingStagingDao`.

- **`resting_hr_bpm` computed from lowest 5-minute window average between 00:00 and 06:00** —
  `DailySummaryWorker.kt:84–90`: filters `dayStartMs until nightEndMs` (00:00–06:00), groups
  by `toEpochMilli() / 300_000L` (5-minute buckets), takes `minOrNull()` of bucket averages.

- **`steps_active_minutes` computed from step deltas** — `DailySummaryWorker.kt:116–124`:
  `zipWithNext`, counts intervals where step delta > 500 and scales to minutes.

- **All four sleep stage minute totals computed** — `DailySummaryWorker.kt:134–137`:
  DEEP, LIGHT, REM, AWAKE each filtered and summed.

- **`computed_by_version = 1` set on every `DailySummary` row** — `DailySummaryWorker.kt:182`.

- **Triggers correctly after seeder inserts** — `SeederService.kt:218`:
  `seededDates.forEach { enqueueSummaryWorker(it, workManager) }`.

---

### Settings Reset

- **`SettingsViewModel.confirmReset()` includes all new tables** — `SettingsViewModel.kt:143–161`:
  all 10 typed DAOs, `sleepStageDao`, `metricReadingStagingDao`, `sleepSessionDao`,
  `dailySummaryDao`, `dailyContextDao`, `activityDao`, `deviceDao`, `rawDeviceDataDao`,
  `syncSessionDao`, `questionResponseDao` all called.

- **`sleep_stages` deleted before `sleep_sessions`** — `SettingsViewModel.kt:146`
  (`sleepStageDao`) before `:158` (`sleepSessionDao`).

- **`question_definitions` excluded** — confirmed absent from the reset sequence.

---

### UI Wiring

- **No ViewModel injects `MetricReadingRepository` or `MetricRepository`** — grep of the
  `ui/` tree returned zero hits. `OverviewViewModel`, `MetricDetailViewModel`,
  `DailyDetailViewModel`, `SeederViewModel`, `SettingsViewModel` all inject only dedicated
  repositories or `DailySummaryRepository`.

- **No functional code references `stages_json`** — the only hit (`AppDatabase.kt:22`) is
  inside a schema-history comment, not executable code.

- **`OverviewViewModel` reads all dashboard metric values from `DailySummaryRepository`** —
  `OverviewViewModel.kt:95–96`: all metric cards source from `DailySummary`.

- **Seeder banner checks `hr_readings` for `source = SEEDER`** — `OverviewViewModel.kt:99`:
  `hrReadingRepo.hasSeederDataForDate(date)` → `RoomHrReadingRepository.kt:35`:
  `dao.countSourceDataInRange(DataSource.SEEDER, ...)` on `HrReadingDao`.

---

## FAILED

### FAILED-1 · `MetricReadingRepository` rename is incomplete — old name still active in codebase

**Severity:** High
**Files:** `MetricRepository.kt:5`, `RoomMetricRepository.kt:4`, `DeviceSyncProcessor.kt:32`,
`DeviceReprocessor.kt:26`

`MetricRepository.kt` keeps the old name alive as a typealias:

```kotlin
typealias MetricRepository = MetricReadingStagingRepository
```

`RoomMetricRepository.kt` does the same:

```kotlin
typealias RoomMetricRepository = RoomMetricReadingStagingRepository
```

Both `DeviceSyncProcessor` and `DeviceReprocessor` still inject `MetricRepository` by the old
name — not `MetricReadingStagingRepository`. The rename is incomplete.

Additionally, the comment in `MetricRepository.kt:4` claims "existing callers
(DeviceSyncProcessor, DeviceReprocessor, **OverviewViewModel, SeederService**) inject
MetricRepository". Both `OverviewViewModel` and `SeederService` have already been updated and
no longer inject it. The comment is stale and misleads about the scope of remaining work.

**Fix:** Update `DeviceSyncProcessor` and `DeviceReprocessor` to inject
`MetricReadingStagingRepository` directly, then delete both typealias files.

---

### FAILED-2 · `MetricDetailViewModel` does not route `BLOOD_PRESSURE` to `BloodPressureReadingRepository`

**Severity:** Medium
**File:** `MetricDetailViewModel.kt:173–175`

`BloodPressureReadingRepository` is not injected anywhere in `MetricDetailViewModel`. When
`metricType == MetricType.BLOOD_PRESSURE`, `unsupportedMessage()` returns a hard-coded string:

```
"Blood pressure has two components (systolic/diastolic) and is not displayed here yet."
```

The History screen will always show an empty/unsupported state for blood pressure regardless
of how much data has been seeded. The audit checklist requires routing each `MetricType` to
its correct dedicated repository for history graph data.

**Fix:** Inject `BloodPressureReadingRepository`, add a `BLOOD_PRESSURE` branch to
`fetchAndAggregate()` returning systolic values grouped by date, and remove it from
`unsupportedMessage()`. The two-series (systolic/diastolic) rendering can remain a future
TODO, but the data must be accessible.

---

### FAILED-3 · Lifestyle question names use sentence case instead of the specified title case

**Severity:** Low
**File:** `AppDatabase.kt:93–97` (`LIFESTYLE_SEEDS`)

Four of the nine question seeds have different capitalisation from the audit specification:

| In code | Required by checklist |
|---|---|
| `"Sleep quality"` | `"Sleep Quality"` |
| `"Performance feel"` | `"Performance Feel"` |
| `"Muscle soreness"` | `"Muscle Soreness"` |
| `"Mental clarity"` | `"Mental Clarity"` |

These strings are the primary display labels and potential match keys for future string-based
logic. The same sentence-case names appear in the `MIGRATION_10_11` seed insert
(`AppDatabase.kt:771`) and in the `DatabaseModule` `onOpen` callback (`DatabaseModule.kt:177`).
All three locations derive from the same `LIFESTYLE_SEEDS` constant.

**Fix:** Update the four entries in `LIFESTYLE_SEEDS` to title case. Since all three seed
sites reference the same constant, changing it once fixes all three automatically.

---

## Summary

| Area | Checks | Passed | Failed |
|---|---|---|---|
| Schema Integrity | 9 | 9 | 0 |
| Migration | 5 | 4 | 1 (casing) |
| Repositories | 3 | 2 | 1 |
| Driver Architecture | 9 | 9 | 0 |
| Seeder | 8 | 8 | 0 |
| Daily Summary Worker | 6 | 6 | 0 |
| Settings Reset | 3 | 3 | 0 |
| UI Wiring | 4 | 3 | 1 |
| **Total** | **47** | **44** | **3** |

The core schema, migration structure, repository layer, Hilt wiring, BLE processor lifecycle,
seeder, and daily summary worker are all correctly implemented. Only three items failed:

- **High (1):** The `MetricReadingRepository` rename is incomplete — two typealias shim files
  remain and two callers still use the old name.
- **Medium (1):** `MetricDetailViewModel` does not route `BLOOD_PRESSURE` to its dedicated
  repository — blood pressure history is permanently unavailable from the History screen.
- **Low (1):** Four lifestyle question names use sentence case rather than the specified title
  case; a one-line change to `LIFESTYLE_SEEDS` fixes all three seed sites simultaneously.
