# Open Athlete Metrics — Architecture Reference

> **Root package:** `com.athletedata.openAthleteMetrics`
> **Database version:** 13 · **Min SDK:** 26 · **Target SDK:** 36

---

## 1. System Architecture Overview

### Layer Diagram

```
┌─────────────────────────────────────────────────────────────┐
│  UI Layer  (Jetpack Compose, @AndroidEntryPoint)            │
│  Screens · Composables · ui/components · ui/theme           │
└───────────────────┬─────────────────────────────────────────┘
                    │ StateFlow / SharedFlow / events
┌───────────────────▼─────────────────────────────────────────┐
│  ViewModel Layer  (@HiltViewModel / @AssistedFactory)        │
│  No direct DAO access · suspend funs for mutations           │
└───────────────────┬─────────────────────────────────────────┘
                    │ suspend / Flow
┌───────────────────▼─────────────────────────────────────────┐
│  Repository Layer  (interfaces + Room/DataStore impls)       │
│  BaseReadingRepository<T> · BaseRoomReadingRepository<T,DAO> │
│  Side-effect: enqueue DailySummaryWorker after writes        │
└───────────┬───────────────────────────────┬─────────────────┘
            │ Room DAOs                      │ DataStore
┌───────────▼──────────┐        ┌────────────▼──────────────┐
│  Room Database        │        │  DataStore<Preferences>    │
│  AppDatabase v13      │        │  settings.preferences_pb   │
│  22 entities, 21 DAOs │        │  (theme, tile config, …)   │
└───────────┬───────────┘        └────────────────────────────┘
            │ SQLite
┌───────────▼───────────────────────────────────────────────┐
│  athlete_data.db                                           │
└────────────────────────────────────────────────────────────┘

Side-channel components (all inject into the Repository/ViewModel layers):

  WorkManager ──► DailySummaryWorker   (aggregates daily metrics)
                  AppStartupWorker     (cleans stale sync sessions)

  BleEngine ──► DriverRegistry ──► WasmDriverEngine (Chicory)
              ► MetricRouter    ──► typed repositories
              ► SyncValidator
              ► DeviceSyncProcessor

  SeederService  (DEBUG builds only)
```

### MVVM Responsibilities

| Layer | Responsibility | What it must NOT do |
|-------|---------------|---------------------|
| **UI (Compose)** | Collect StateFlow; render state; forward user events to ViewModel | Call suspend funs directly; touch repositories or DAOs |
| **ViewModel** | Hold StateFlow/SharedFlow; call repository suspend funs; translate domain objects to UI models | Access DAOs directly; hold Android context (except ApplicationContext via Hilt) |
| **Repository** | Implement interface contracts; delegate to DAOs; enqueue WorkManager jobs after writes | Hold UI state; know about ViewModels |
| **DAO** | Typed Room queries; return `Flow<T>` or `suspend` results | Contain business logic |
| **Entity** | Map SQLite rows to Kotlin types | Be exposed above the repository boundary |

**Boundary rule:** Room entity types (`*Entity`) never cross the repository boundary. Repositories map entities to plain domain model types (`data/model`) before returning them.

### StateFlow vs Flow vs suspend

| Primitive | Where used | Why |
|-----------|-----------|-----|
| `StateFlow<T>` | ViewModels → UI | Always-current state; UI gets the latest value immediately on collection; drives Compose recomposition |
| `Flow<T>` | DAOs → Repositories → ViewModels | Cold, database-backed stream; each collector gets its own lifecycle; DAO emits on every DB write |
| `suspend fun` | Repository mutations; ViewModel event handlers | One-shot write operations; no ongoing subscription needed |
| `SharedFlow<T>` | ViewModel → UI (one-shot effects) | Navigation events, snackbar errors — fire-and-forget, not replayed on recomposition |

### Hilt Dependency Graph

```
SingletonComponent
├── DatabaseModule
│   ├── provides AppDatabase  (Room.databaseBuilder + 13 migrations)
│   ├── provides HrReadingDao, HrvReadingDao, … (21 DAO methods)
│   └── binds HrReadingRepository → RoomHrReadingRepository
│        HrvReadingRepository → RoomHrvReadingRepository
│        … (15+ interface → impl bindings, all @Singleton)
│
├── DataStoreModule
│   ├── provides DataStore<Preferences>  ("settings.preferences_pb")
│   └── binds SettingsRepository → DataStoreSettingsRepository
│
└── WorkManagerModule
    └── provides WorkManager  (WorkManager.getInstance)

ViewModelComponent  (@HiltViewModel on every ViewModel)
ActivityComponent   (@AndroidEntryPoint on MainActivity)

Worker injection:
  AthleteDataApplication implements Configuration.Provider
  → returns HiltWorkerFactory (injected via @Singleton)
  → DailySummaryWorker and AppStartupWorker use @HiltWorker + @AssistedInject

Special case:
  MetricDetailViewModel uses @AssistedFactory to receive MetricType at construction
  (not from SavedStateHandle, because it's a strongly-typed enum parameter)
```

---

## 2. Data Flow — All Scenarios

### 2.1 Manual Entry (DailyContext)

User adjusts fatigue/stress/motivation sliders, illness flag, or habit toggles on the Questions screen.

```
DailyQuestionsScreen
  └─ user input
       └─ DailyQuestionsViewModel
            ├─ onFatigueChange() / onStressChange() / onHabitToggle() / …
            └─ saveQuestions()
                 └─ DailyContextRepository.upsert(DailyContext)
                      └─ DailyContextDao.upsert(DailyContextEntity)
                           └─ daily_context table  (PK = date, INSERT OR REPLACE)
```

No WorkManager job is triggered — DailyContext is not aggregated into `daily_summary`; it is read directly by the Dashboard and Daily Detail screens.

### 2.2 Manual Metric Entry

No UI currently exists for inserting raw time-series readings manually. `DailyContext` (user-scored fields) is the mechanism for manually recorded wellbeing data. If raw readings were added in future:

```
ViewModel.saveReading()
  └─ XyzReadingRepository.insert(entity)
       └─ XyzReadingDao.insert()
            └─ xyz_readings table
                 └─ enqueueSummaryWorker(date, workManager)  ← side-effect in repo
```

### 2.3 Seeder (DEBUG builds only)

```
SettingsScreen  [BuildConfig.DEBUG guard]
  └─ SeederViewModel.seedThirtyDays()
       └─ SeederService.seedDays(30 dates)
            │  (per date, in order)
            ├─ hrReadingRepo.insertAll(List<HrReadingEntity>)     // 5-min intervals
            ├─ hrvReadingRepo.insertAll(…)                        // hourly
            ├─ spo2ReadingRepo.insertAll(…)                       // overnight only
            ├─ skinTempReadingRepo.insertAll(…)
            ├─ respirationReadingRepo.insertAll(…)
            ├─ stepsReadingRepo.insertAll(…)
            ├─ bloodPressureReadingRepo.insertAll(…)
            ├─ glucoseReadingRepo.insertAll(…)
            ├─ activeCalorieReadingRepo.insertAll(…)
            ├─ totalCalorieReadingRepo.insertAll(…)
            ├─ sleepRepo.insert(SleepSession)
            ├─ sleepStageRepo.insertAll(List<SleepStage>)
            ├─ dailyContextRepo.upsert(DailyContext)
            ├─ questionRepo.saveResponses(…)
            ├─ activityRepo.insert(Activity)
            └─ enqueueSummaryWorker(date, workManager)
                   └─ DailySummaryWorker runs for that date

            progress lambda ──► SeederState.Running(0.0 … 1.0)
                                     └─ SeederViewModel StateFlow
                                          └─ UI progress bar
```

All rows written with `DataSource.SEEDER` for isolation. On clear:

```
SeederViewModel.clearSeederData()
  └─ SeederService.clearSeederData()
       └─ every repo.deleteBySource(DataSource.SEEDER)
            └─ re-enqueue DailySummaryWorker for affected dates
```

### 2.4 Device Driver (BLE Sync)

```
BLE device
  └─ GATT notify characteristic
       └─ BluetoothGattCallback.onCharacteristicChanged()
            └─ BleEngine.notificationChannel  (buffered Channel, DROP_OLDEST)
                 └─ single IO coroutine consumer (sequential, no concurrent WASM)
                      ├─ reassemble fragmented packets
                      ├─ DriverRegistry.resolve(deviceName, serviceUuids)
                      │    └─ WasmDriverEngine.parse(bytes)  [Chicory WASM sandbox]
                      │         └─ List<MetricReading>
                      └─ MetricRouter.route(readings)
                           ├─ DEDICATED types (HR, HRV, SpO2, …)
                           │    └─ typed repo.insertAll()   (source=DEVICE)
                           └─ other types
                                └─ MetricReadingStagingRepository.insertAll()

       track affected dates
       on quiescence (3 s idle):
            └─ enqueueSummaryWorker(date) for each affected date
                 └─ DailySummaryWorker aggregates → daily_summary upsert
                      └─ DashboardViewModel Flow<DailySummary?> emits
                           └─ Compose re-renders StatCards
```

RawDeviceData packets are also persisted to `raw_device_data` for driver replay / recovery.

### 2.5 Dashboard Load

```
DashboardScreen launched / date changed
  └─ DashboardViewModel
       ├─ DailySummaryRepository.getSummaryForDate(date) : Flow<DailySummaryEntity?>
       │    └─ DailySummaryDao.getSummaryForDate(date)   [Room Flow]
       ├─ WidgetLayoutRepository.getLayout() : Flow<List<WidgetLayout>>
       ├─ DailyContextRepository.getForDate(date) : Flow<DailyContextEntity?>
       └─ combine(…) → uiState: StateFlow<DashboardUiState>
            └─ DashboardScreen collects uiState
                 └─ each widget composable renders value or "--" when null
```

No loading spinner for missing data — null summary renders placeholder strings immediately.

### 2.6 History Page Load

```
HistoryScreen
  └─ HistoryViewModel
       ├─ pageDate: StateFlow<LocalDate>     ← graph window right edge (date picker)
       ├─ tileDate: StateFlow<LocalDate>     ← detail cursor (slider / arrows)
       ├─ rangeToggle: StateFlow<RangeToggle>
       ├─ regularity: StateFlow<Regularity>  ← Daily / Weekly / Monthly
       └─ metricKeys: StateFlow<List<String>>   (max 5 active metrics)

       Data fetch (triggered on any state change):
         DailySummaryRepository.getSummariesSince(epoch) : Flow<List<DailySummaryEntity>>
         DailyContextRepository.getForRange(…)           : Flow<List<DailyContextEntity>>
              └─ aggregate to SeriesData per metric key
                   └─ seriesList: StateFlow<List<SeriesData>>   → Vico chart
                        └─ metricTiles: StateFlow<List<MetricTile>>  → detail tiles
                             (each tile carries canStepBack / canStepForward flags)
```

Full history is always fetched (since epoch); range/regularity filtering happens in-memory after fetch, not in the SQL query.

---

## 3. DailySummaryWorker — Full Specification

### Input Parameters

| Key | Type | Description |
|-----|------|-------------|
| `KEY_DATE` | String (ISO LocalDate, e.g. `"2025-03-15"`) | The calendar date to aggregate |

### Worker Identity

- **Unique work name:** `"daily_summary_$date"`
- **Conflict policy:** `ExistingWorkPolicy.REPLACE` — a second enqueue for the same date cancels any pending prior job and schedules a fresh one
- **Result:** `Result.success()` on completion; `Result.retry()` on exception (WorkManager applies default exponential backoff)

### Aggregates Computed

All queries use a day-boundary window: `dayStartMs` (00:00:00 local) → `dayEndMs` (23:59:59 local).

| Field | Formula | Edge case |
|-------|---------|-----------|
| `avgHrBpm` | mean of all HR readings in window | null if no readings |
| `restingHrBpm` | minimum of 5-min rolling-average HR, restricted to pre-sunrise (00:00–06:00) | null if no overnight HR |
| `avgHrvMs` | mean of all HRV (rmssd_ms) readings in window | null if no readings |
| `morningHrvMs` | earliest HRV reading in 05:00–06:00 window | null if no reading in window |
| `minHrvMs` / `maxHrvMs` | min/max of all HRV readings | null if no readings |
| `avgSpo2Pct` | mean of SpO2 percentage readings | null if no readings |
| `minSpo2Pct` / `maxSpo2Pct` | min/max SpO2 | null if no readings |
| `avgSkinTempC` | mean skin temp readings | null if no readings |
| `minSkinTempC` / `maxSkinTempC` | min/max skin temp | null if no readings |
| `avgRespirationBpm` | mean respiration rate | null if no readings |
| `steps` | max value of cumulative_steps column for the day | null if no steps readings; 0 if readings present but all zero |
| `activeMinutes` | count of 1-hour intervals where step delta exceeds 500 steps | 0 if no steps readings |
| `activeCalories` | sum of all active_calorie_readings in window | null if no readings |
| `totalCalories` | value of the total_calorie reading with the latest `recorded_at` | null if no readings |
| `sleepMinutes` | `SleepSessionEntity.duration_minutes` for the session whose date matches | null if no session |
| `sleepDeepMinutes` | sum of `SleepStageEntity` rows with `stage=DEEP` | 0 if session exists but no stages |
| `sleepLightMinutes` | sum of `stage=LIGHT` | 0 if session exists but no stages |
| `sleepRemMinutes` | sum of `stage=REM` | 0 if session exists but no stages |
| `sleepAwakeMinutes` | sum of `stage=AWAKE` | 0 if session exists but no stages |
| `dominantSource` | most frequently occurring `DataSource` across all readings for the day | `DataSource.MANUAL` if no readings |

### Upsert Strategy

```sql
INSERT OR REPLACE INTO daily_summary (date, …) VALUES (?, …)
```

PK is `date`. Re-running the worker for the same date overwrites the previous row entirely. The computation is deterministic given the same underlying readings, making it safe to re-trigger without risk of data corruption.

### Trigger Points

| Caller | When |
|--------|------|
| `SeederService.seedDays()` | After all readings for a given date are inserted |
| `BleEngine` (quiescence handler) | 3 seconds after the last BLE packet arrives for a date |
| `RoomSleepRepository.insert()` | After any sleep session is written |
| `RoomActivityRepository.insert()` | After any activity record is written |

The `enqueueSummaryWorker(date, workManager)` top-level function is the single call site used by all callers; it constructs the `OneTimeWorkRequest` and calls `workManager.enqueueUniqueWork(...)`.

### Failure Handling

WorkManager retries the worker on any uncaught exception using its default backoff policy. The `daily_summary` row for that date retains its previous value (or is absent) until the worker succeeds. The Dashboard reads from `daily_summary` via a `Flow` — it will display the stale or missing summary until the next successful worker run emits a new value.

AppStartupWorker (runs once per app launch) cleans up `sync_sessions` rows stuck in `IN_PROGRESS` for more than 24 hours, marking them `FAILED`. This is unrelated to `DailySummaryWorker` but runs via the same Hilt worker infrastructure.

### Hilt Wiring

`DailySummaryWorker` is annotated `@HiltWorker` and uses `@AssistedInject` for its constructor. `AthleteDataApplication` implements `WorkManager.Configuration.Provider` and returns a `HiltWorkerFactory` (provided via `@Inject`). WorkManager calls this factory to construct workers, which lets Hilt inject the DAOs and repositories.

---

## 4. Seeder — Technical Reference

### Build Guard

The seeder is a three-layer guard:

1. **Service level:** `SeederService` public methods are annotated / wrapped so they are no-ops at runtime in release builds. The service itself is still compiled in (it is a Hilt singleton) but its logic is never reached.
2. **ViewModel level:** `SeederViewModel` is only wired in the Settings screen when `BuildConfig.DEBUG == true` via a compile-time constant check.
3. **UI level:** The seeder card in `SettingsScreen` is wrapped in `if (BuildConfig.DEBUG)` — it is not compiled into the release APK's UI.

### Fixed Random Seed

All random generation uses `Random(42L)`. Every call to `seedThirtyDays()` produces the exact same 30 × N rows. This enables:
- Deterministic UI screenshots for documentation
- Repeatable integration tests against known values
- Safe "clear and re-seed" without drift

### Generation Logic (Per Date)

**Pre-computation (before per-date loop):**
- Workout schedule: 1–2 days per week, randomly placed but consistent across runs
- Illness block: 2–4 consecutive days, randomly positioned in the 30-day window
- Dip nights: nightly probability of SpO2 dip (1 in 8)
- Sleep durations: sampled per night (6.5–8.5 h)
- Weight series: slight day-to-day variation around a baseline

**Per-date generation:**

| Data type | Interval / count | Value range | Notes |
|-----------|-----------------|-------------|-------|
| HR readings | Every 5 min (288/day) | 50–65 bpm overnight; 65–85 bpm daytime | +30–50 bpm during workout window |
| HRV readings | Hourly (24/day) | Circadian wave 30–70 ms | Mid-week dip −20%; illness days lower |
| SpO2 readings | 8 samples, overnight only | 95–100% base | 1-in-8 nights: one sample dips to 88–93% |
| Skin temp | 4–8 random hours | 33–37 °C | Slight overnight drop |
| Respiration | Hourly (24/day) | 12–18 breaths/min | Lower during sleep |
| Steps | 16 waking hours, cumulative | Daily total 6 000–12 000 (non-workout); 10 000–18 000 (workout) | Stored as cumulative_steps |
| Active calories | Single value if workout day | 250–600 kcal | Absent on non-workout days |
| Total calories | Single end-of-day value | 1 800–3 200 kcal | |
| Blood pressure | Morning reading | Systolic 110–130, Diastolic 65–85 | Slightly elevated on illness days |
| Glucose | 4 windows | Fasting 70–100, post-meal spikes | Fasting, post-breakfast, post-lunch, post-dinner |
| SleepSession | 1 per night | Duration from pre-computed schedule | sleep_start ~22:00–23:30, stages JSON follow |
| SleepStage rows | 90-min NREM/REM cycles | LIGHT → DEEP → LIGHT → REM → LIGHT → AWAKE | Repeated until session ends |
| DailyContext | 1 per date | Fatigue/stress/motivation 1–10 | Correlated to HRV + sleep duration; illness → elevated fatigue |
| QuestionResponses | 1 per lifestyle question | Mapped from DailyContext scores | |
| Activity | 1 per date | 45–90 min workout (run/cycle) or 20–40 min walk | Type from workout schedule |

### Batch Insert Strategy

Each data type is collected into a `List<T>` for the day, then written in a single `insertAll(list)` call. This keeps the Room transaction count low (~12 transactions per date vs. hundreds of row-by-row inserts). The `insertAll()` functions use `INSERT OR REPLACE` conflict strategy; existing seeder rows for the same `(driver_id, recorded_at)` key are overwritten on re-seed.

### Progress Reporting

```
SeederService.seedDays()
  └─ progress callback: (completedDates / totalDates).toFloat()
       └─ SeederViewModel updates _state
            └─ state: StateFlow<SeederState>

SeederState sealed class:
  Idle
  Running(progress: Float)      // 0.0 → 1.0
  Done
  PartialSuccess(failedDates: List<LocalDate>)
  Error(message: String)
```

Exceptions during a single date are caught; the date is added to `failedDates` and seeding continues. If all dates fail, the state transitions to `Error`.

### Clear Seeder Data

```
SeederService.clearSeederData(onProgress)
  ├─ hrReadingRepo.deleteBySource(DataSource.SEEDER)
  ├─ hrvReadingRepo.deleteBySource(DataSource.SEEDER)
  ├─ spo2ReadingRepo.deleteBySource(DataSource.SEEDER)
  ├─ skinTempReadingRepo.deleteBySource(DataSource.SEEDER)
  ├─ respirationReadingRepo.deleteBySource(DataSource.SEEDER)
  ├─ stepsReadingRepo.deleteBySource(DataSource.SEEDER)
  ├─ bloodPressureReadingRepo.deleteBySource(DataSource.SEEDER)
  ├─ glucoseReadingRepo.deleteBySource(DataSource.SEEDER)
  ├─ activeCalorieReadingRepo.deleteBySource(DataSource.SEEDER)
  ├─ totalCalorieReadingRepo.deleteBySource(DataSource.SEEDER)
  ├─ sleepRepo.deleteBySource(DataSource.SEEDER)
  ├─ activityRepo.deleteBySource(DataSource.SEEDER)
  └─ dailyContextRepo.deleteAll()  (no source field on daily_context)
       └─ re-enqueue DailySummaryWorker for affected dates
```

After clearing, `daily_summary` rows for the affected dates are recomputed by the re-enqueued workers. If no real-device data exists, summary rows will have all-null fields.

### Determinism Contract

`Random(42L)` + fixed date range → identical output on every run. There are no time-dependent values (no `Clock.System.now()` in the generation path; dates are computed from the seed parameters). A clear + re-seed produces byte-for-byte identical rows.

---

## 5. Package Structure — Complete Reference

Root: `com.athletedata.openAthleteMetrics`

### `data/db`

**What belongs here:** Room database class, all DAO interfaces, all `*Entity` data classes, `Converters` TypeConverter class.

**Key files:**
- `AppDatabase.kt` — `@Database(entities=[…], version=13)`; companion object holds all 13 migration objects; implements `Configuration.Provider` for HiltWorkerFactory
- `Converters.kt` — TypeConverters: `LocalDate ↔ String` (ISO-8601), `Instant ↔ Long` (Unix ms), `MetricType ↔ String`, `DataSource ↔ String`, `UserCategory ↔ String`, `SyncStatus ↔ String`, `SleepStage ↔ String`
- `HrReadingEntity.kt`, `HrvReadingEntity.kt`, … (10 typed reading entities)
- `SleepSessionEntity.kt`, `SleepStageEntity.kt`
- `DailySummaryEntity.kt`, `DailyContextEntity.kt`
- `DeviceEntity.kt`, `SyncSessionEntity.kt`, `RawDeviceDataEntity.kt`
- `ActivityEntity.kt`
- `QuestionDefinitionEntity.kt`, `QuestionResponseEntity.kt`
- `WidgetLayoutEntity.kt`
- `MetricReadingStagingEntity.kt`

**Depends on:** Room, kotlinx.serialization (for stage JSON)
**Depended on by:** `data/repository`, `di`

### `data/repository`

**What belongs here:** Repository interfaces and their Room / DataStore implementations. Base classes for shared patterns.

**Key abstractions:**
- `BaseReadingRepository<T>` — interface: `insert`, `insertAll`, `insertAllOrIgnore`, `deleteBySource`, `deleteAll`, `getReadingsInRangeOnce`
- `BaseRoomReadingRepository<T, DAO>` — abstract Room implementation of the above; all 10 typed reading repos extend it

**Implementations:**
- `RoomHrReadingRepository`, `RoomHrvReadingRepository`, `RoomSpO2ReadingRepository`, `RoomRespirationReadingRepository`, `RoomSkinTempReadingRepository`, `RoomStepsReadingRepository`, `RoomBloodPressureReadingRepository`, `RoomGlucoseReadingRepository`, `RoomActiveCalorieReadingRepository`, `RoomTotalCalorieReadingRepository`
- `RoomSleepRepository`, `RoomSleepStageRepository`
- `RoomDailySummaryRepository`, `RoomDailyContextRepository`
- `RoomDeviceRepository`, `RoomActivityRepository`
- `RoomSyncSessionRepository`, `RoomRawDeviceDataRepository`
- `RoomMetricReadingStagingRepository`
- `RoomQuestionRepository`
- `RoomWidgetLayoutRepository`
- `DataStoreSettingsRepository`

**Depends on:** `data/db`, `data/model`, WorkManager (for enqueueing DailySummaryWorker after writes)
**Depended on by:** `di` (bindings), all ViewModels, `SeederService`, `BleEngine`, `worker`

### `data/model`

**What belongs here:** Plain Kotlin data classes and enums that cross layer boundaries. No Room annotations, no Android imports.

**Key types:**

| Type | Kind | Values / fields |
|------|------|----------------|
| `MetricType` | enum | HR, HRV, RHR, SPO2, STEPS, SLEEP_STAGE, BATTERY, SKIN_TEMP, BODY_TEMP, TEMP_DEVIATION, VO2_MAX, DISTANCE, ELEVATION_GAIN, ELEVATION_LOSS, CALORIES, ACTIVE_CALORIES, BASAL_CALORIES, RESPIRATION, TOTAL_CALORIES, BLOOD_PRESSURE, GLUCOSE |
| `DataSource` | enum | DEVICE, MANUAL, SEEDER |
| `SleepStage` | enum | DEEP, LIGHT, REM, AWAKE |
| `SyncStatus` | enum | IN_PROGRESS, SUCCESS, FAILED |
| `QuestionType` | enum | SCALE, BOOLEAN, TEXT |
| `QuestionCategory` | enum | LIFESTYLE, CUSTOM |
| `ThemePreference` | enum | LIGHT, DARK, SYSTEM |
| `UserCategory` | enum | Activity type classification for activities |
| `DailySummary` | data class | date + 25+ nullable metric fields + dominantSource |
| `DailyContext` | data class | date, fatigue, stress, motivation, sleepQuality, performanceFeel, isIll, weight, bodyFat, notes, habitsJson |
| `Device` | data class | id, bleAddress, driverId, displayName, lastSeenMs, lastSyncMs, lastBatteryPct |
| `SyncSession` | data class | id, deviceId, driverId, startedAt, endedAt, status, recordsImported, packetsReceived, syncedBeforeQuiescence |
| `Activity` | data class | startTime, endTime, durationMinutes, deviceName, userCategory, source, avgHrBpm, maxHrBpm, minHrBpm |
| `BleConnectionState` | sealed class | Idle, Scanning, Connecting, Connected, Syncing, SyncComplete, Error, GattCacheError |
| `SeederState` | sealed class | Idle, Running(progress), Done, PartialSuccess(failedDates), Error(message) |
| `RangeToggle` | enum | DAYS_7, DAYS_30, DAYS_90, DAYS_365 |
| `Regularity` | enum | DAILY, WEEKLY, MONTHLY |

**Depends on:** Nothing (pure Kotlin)
**Depended on by:** All layers

### `domain/usecase`

**What belongs here:** Use-case classes that coordinate multiple repositories. Currently thin — the layer exists for future expansion.

**Current files:**
- `SyncActivityUseCase.kt` — coordinates BLE sync + activity classification

**Depends on:** `data/repository`
**Depended on by:** ViewModels (selectively)

### `di`

**What belongs here:** Hilt `@Module` classes only. No business logic.

- `DatabaseModule.kt` — `@InstallIn(SingletonComponent::class)`; provides `AppDatabase` + 21 DAO methods + all repository bindings
- `DataStoreModule.kt` — `@InstallIn(SingletonComponent::class)`; provides `DataStore<Preferences>` + binds `SettingsRepository`
- `WorkManagerModule.kt` — `@InstallIn(SingletonComponent::class)`; provides `WorkManager`

**Depends on:** All `data/*` packages, Hilt
**Depended on by:** Application startup (Hilt component graph)

### `worker`

**What belongs here:** WorkManager `CoroutineWorker` subclasses and the `enqueueSummaryWorker()` helper function.

- `DailySummaryWorker.kt` — `@HiltWorker`; aggregates daily metrics into `daily_summary`
- `AppStartupWorker.kt` — `@HiltWorker`; marks stale `IN_PROGRESS` sync sessions as `FAILED` on app launch
- `enqueueSummaryWorker(date, workManager)` top-level function — single enqueue call site for `DailySummaryWorker`

**Depends on:** `data/repository`, WorkManager, Hilt worker support
**Depended on by:** Repositories (call `enqueueSummaryWorker` after writes), `SeederService`, `BleEngine`, `AthleteDataApplication`

### `seeder`

**What belongs here:** Debug-only data generation. Must not be used from release builds.

- `SeederService.kt` — `@Singleton`; generates and inserts 30 days of synthetic readings

**Depends on:** All typed reading repositories, SleepRepository, DailyContextRepository, ActivityRepository, QuestionRepository, WorkManager
**Depended on by:** `ui/settings` (via `SeederViewModel`, debug only)

### `ble`

**What belongs here:** Bluetooth LE connection management, packet processing, and metric routing.

- `BleEngine.kt` — `@Singleton`; manages GATT lifecycle, packet channel, driver dispatch, quiescence detection
- `MetricRouter.kt` — routes parsed `MetricReading` objects to the correct typed repository or staging
- `SyncValidator.kt` — range and temporal validation of parsed readings before insert
- `DeviceSyncProcessor.kt` — post-parse deduplication and sleep stage merging
- `DeviceReprocessor.kt` — replays `raw_device_data` packets through the current driver version for recovery

**Depends on:** `data/repository`, `worker`, `ble/driver`, `ble/wasm`
**Depended on by:** `ui/devices`

### `ble/driver`

**What belongs here:** WASM driver manifest loading and registry.

- `DriverRegistry.kt` — `@Singleton`; loads manifests, resolves device → driver, lazy-loads WASM modules
- `DriverStorage.kt` — reads manifest JSON from app assets or app-specific storage
- `WasmDriverManifest.kt` — data class representing a driver manifest (id, name, serviceUuids, syncCommands, …)

**Depends on:** Chicory (WASM), kotlinx.serialization
**Depended on by:** `BleEngine`, `ui/devices`

### `ble/wasm`

**What belongs here:** The Chicory WASM interpreter wrapper.

- `WasmDriverEngine.kt` — loads WASM bytecode, exposes `parse(bytes): List<MetricReading>`, sandboxes execution

**Depends on:** Chicory runtime
**Depended on by:** `DriverRegistry`

### `ble/sync`

**What belongs here:** Sync session orchestration and recovery helpers referenced from `BleEngine`.

- `DeviceSyncProcessor.kt`, `DeviceReprocessor.kt` (also listed under `ble`)

### `ui/overview`

**What belongs here:** Dashboard screen, its ViewModel, and all individual widget composables.

- `DashboardScreen.kt` — reorderable `LazyVerticalGrid` of widgets; date navigation; edit mode; weight sheet
- `DashboardViewModel.kt` — `uiState: StateFlow<DashboardUiState>`; navigation events; widget CRUD; weight entry
- `widgets/` — individual composable per widget type (HR, HRV, RHR, SpO2, Sleep, Steps, Activities, Weight, LifestyleQuestion, HabitQuestion, Starred variants, …)

**Depends on:** `data/repository`, `ui/components`, `ui/theme`, Reorderable
**Depended on by:** `ui/nav`

### `ui/dailydetail`

**What belongs here:** Detailed single-day breakdown with expandable tiles.

- `DailyDetailScreen.kt` — reorderable tiles (Cardiovascular, Sleep, Activity, Body, Questions, Context scores); hypnogram; raw reading tables
- `DailyDetailViewModel.kt` — `uiState: StateFlow<DailyDetailUiState>`; tile expansion/visibility; tile reorder; activity category update

**Depends on:** All reading repositories, `data/repository`, Reorderable
**Depended on by:** `ui/nav`

### `ui/metric`

**What belongs here:** Single-metric deep-dive screen with charting.

- `MetricDetailScreen.kt` — Vico line/bar chart; time-range selector (7d/14d/30d/90d); stats (min/max/avg)
- `MetricDetailViewModel.kt` — `@AssistedFactory` receives `MetricType`; fetches and aggregates raw readings for the range

**Depends on:** All reading repositories, `DailySummaryRepository`, Vico
**Depended on by:** `ui/nav`

### `ui/history`

**What belongs here:** Multi-metric history charting with range and regularity controls.

- `HistoryScreen.kt` — Vico chart (index-based x-axis for periods); metric overlay selector; detail tiles with step navigation; date picker (controls graph window only)
- `HistoryViewModel.kt` — dual cursor: `pageDate` (graph right edge) + `tileDate` (detail cursor); full history fetch, in-memory aggregation by `RangeToggle` + `Regularity`

**Depends on:** `DailySummaryRepository`, `DailyContextRepository`, `SettingsRepository`, Vico
**Depended on by:** `ui/nav`

### `ui/questions`

**What belongs here:** Daily lifestyle questions and custom question management.

- `QuestionsScreen.kt` — lifestyle + custom question lists; add/edit/delete custom questions; star for dashboard; visibility toggle
- `QuestionsViewModel.kt` — question definitions + responses by date; CRUD for custom questions
- `DailyQuestionsScreen.kt` — sliders for fatigue/stress/motivation/sleepQuality/performanceFeel; illness flag; habit toggles; weight entry
- `DailyQuestionsViewModel.kt` — merges mutations into `DailyContext`; `saveQuestions()` + `saveWeight()` → `DailyContextRepository.upsert()`

**Depends on:** `QuestionRepository`, `DailyContextRepository`
**Depended on by:** `ui/nav`

### `ui/devices`

**What belongs here:** BLE device management, driver management, and (in debug builds) the seeder controls.

- `DevicesScreen.kt` — device list; BLE scan; driver tab; reprocess; recovery sessions
- `DevicesViewModel.kt` — `devices`, `connectionState`, `discoveredCandidates`, `reprocessState`; scan/connect/sync/reprocess/recovery flows
- `SeederViewModel.kt` (debug only) — `state: StateFlow<SeederState>`; `seedThirtyDays()`, `seedToday()`, `clearSeederData()`

**Depends on:** `BleEngine`, `DriverRegistry`, `DriverStorage`, `SeederService` (debug), `SyncSessionRepository`
**Depended on by:** `ui/nav`

### `ui/settings`

**What belongs here:** App-level settings (theme, data export/import, reset).

- `SettingsScreen.kt` — theme picker; export/import database; multi-step reset confirmation; seeder section (debug only)
- `SettingsViewModel.kt` — `themePreference: StateFlow<ThemePreference>`; database export/import; multi-step reset (deletes tables in FK-safe order)

**Depends on:** `SettingsRepository`, `AppDatabase`, `SeederViewModel` (debug)
**Depended on by:** `ui/nav`

### `ui/nav`

**What belongs here:** Navigation graph, bottom navigation bar, nav-level ViewModel.

- `NavGraph.kt` / `AppNavGraph.kt` — manual backstack (`mutableStateListOf<Page>()`); 4 main pages (DASHBOARD, DAILY_DETAIL, QUESTIONS, HISTORY) + modal overlays (Settings, Devices, Metric Detail); pending-state variables for deep links from Dashboard
- `BottomNavBar.kt` — 4–5 bottom tab destinations
- `NavHostViewModel.kt` — forwards `BleConnectionState` + battery % to the nav bar; `onDevicesLongPressed()` triggers manual sync

**Depends on:** All UI screens
**Depended on by:** `MainActivity`

### `ui/components`

**What belongs here:** Shared Compose primitives reused across multiple screens.

- `StatCard.kt` — metric value card with label, value, unit
- `MetricChip.kt` — small pill-style metric tag
- `SectionHeader.kt` — section title row
- `PillSelector.kt` — horizontal period/range selector (7d / 30d / 90d / 1y)
- `TopBar.kt` — app bar with date navigation arrows and title
- `LoadingState.kt`, `EmptyState.kt`, `ScreenState.kt` — composable loading/empty/error states

**Depends on:** `ui/theme`
**Depended on by:** All UI screens

### `ui/theme`

**What belongs here:** Material 3 theme, colour tokens, typography, and sizing constants.

- `Theme.kt` — `LightColorScheme`, `DarkColorScheme`, `AthleteDataTheme` composable; reads `ThemePreference` from `SettingsRepository` via `ThemeViewModel`
- `Color.kt` — named colour tokens (primary, secondary, tertiary, error, background, surface, on-* variants, metric-specific accent colours)
- `Type.kt` — `Typography` object (displayLarge, headlineMedium, bodyMedium, labelSmall, …)
- `Dimens.kt` — spacing and sizing constants (padding, corner radius, card elevation)
- `ThemeViewModel.kt` — `themePreference: StateFlow<ThemePreference>`; reads from `SettingsRepository`

**Depends on:** Material 3, `SettingsRepository`
**Depended on by:** All UI screens, all components

---

## 6. Tech Stack — Dependency Reference

All versions sourced from `gradle/libs.versions.toml`.

| Library | Version | Gradle ref | Purpose in this app | Key packages |
|---------|---------|-----------|---------------------|--------------|
| **Kotlin** | 2.2.10 | `kotlin-android` plugin | Language; coroutines, serialization plugins | All |
| **KSP** | 2.2.10-2.0.2 | `ksp` plugin | Code generation for Room (`@Dao`, `@Entity`) and Hilt (`@HiltViewModel`, `@HiltWorker`) | `data/db`, `di` |
| **Android Gradle Plugin** | 9.2.1 | `androidGradlePlugin` | Build toolchain; `BuildConfig` generation | Build only |
| **Compose BOM** | 2026.06.00 | `androidx-compose-bom` | Aligns all Compose library versions | All `ui/*` |
| **compose.ui** | (BOM) | `androidx-compose-ui` | Core Compose runtime and layout | All `ui/*` |
| **compose.material3** | (BOM) | `androidx-compose-material3` | Material 3 components, theming, colour | All `ui/*`, `ui/theme` |
| **compose.ui.tooling** | (BOM) | `androidx-compose-ui-tooling` | `@Preview` support in Android Studio | `ui/*` (debug) |
| **Room** | 2.8.4 | `androidx-room-runtime`, `androidx-room-ktx`, `androidx-room-compiler` (KSP) | Local SQLite ORM; 22-entity database; Flow-returning DAOs | `data/db`, `data/repository` |
| **Hilt (Dagger)** | 2.59.2 | `hilt-android`, `hilt-android-compiler` (KSP) | Dependency injection; singleton graph; `@HiltAndroidApp`, `@AndroidEntryPoint` | `di`, all ViewModels, `AthleteDataApplication` |
| **Hilt AndroidX** | 1.3.0 | `androidx-hilt-navigation-compose`, `androidx-hilt-work`, `androidx-hilt-compiler` (KSP) | `@HiltViewModel` + `hiltViewModel()`, `@HiltWorker`, Navigation Compose integration | All ViewModels, `worker` |
| **Coroutines** | 1.11.0 | `kotlinx-coroutines-android`, `kotlinx-coroutines-test` | Structured concurrency; `StateFlow`, `SharedFlow`, `Flow`, `viewModelScope` | All async code |
| **DataStore** | 1.2.1 | `androidx-datastore-preferences` | Key-value preference storage for theme, tile config, last-viewed metric key | `di/DataStoreModule`, `data/repository/DataStoreSettingsRepository` |
| **WorkManager** | 2.11.2 | `androidx-work-runtime-ktx` | `DailySummaryWorker` (daily aggregation), `AppStartupWorker` (stale session cleanup) | `worker`, repos, `seeder`, `ble` |
| **Lifecycle** | 2.11.0 | `androidx-lifecycle-runtime-ktx`, `androidx-lifecycle-viewmodel-ktx`, `androidx-lifecycle-runtime-compose` | `ViewModel`, `viewModelScope`, `collectAsStateWithLifecycle` | All ViewModels, all screens |
| **Navigation Compose** | 2.9.8 | `androidx-navigation-compose` | `NavHost`, `NavController`, screen routing | `ui/nav` |
| **Vico** | 2.1.2 | `vico-compose-m3`, `vico-compose`, `vico-core` | Compose-native line/bar charts in History and Metric Detail screens | `ui/history`, `ui/metric` |
| **Reorderable** | 3.1.0 | `reorderable` | Drag-to-reorder `LazyVerticalGrid` (Dashboard widget grid) and `LazyColumn` (Daily Detail tile list) | `ui/overview`, `ui/dailydetail` |
| **Timber** | 5.0.1 | `timber` | Structured logcat logging; `DebugTree` planted in debug builds only | App-wide (debug) |
| **Chicory** | 1.7.5 | `chicory-runtime` | Pure-JVM WebAssembly interpreter; runs sandboxed device driver WASM modules without native `.so` files | `ble/wasm` |
| **kotlinx.serialization** | 1.11.0 | `kotlinx-serialization-json` plugin + `kotlinx-serialization-json` lib | WASM driver manifest JSON deserialisation; sleep stage JSON; habits JSON in `DailyContext` | `ble/driver`, `data/model` |
| **JUnit** | 4.13.2 | `junit` | Unit test runner | `test/` |
| **MockK** | 1.14.11 | `mockk` | Kotlin-idiomatic mocking for repository / DAO unit tests | `test/` |
| **Espresso** | 3.7.0 | `androidx-espresso-core` | UI instrumentation test framework | `androidTest/` |

### Notable Build Configuration

**KSP Room schema export:**
```kotlin
// app/build.gradle.kts
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
```
Generates a JSON schema file per database version in `app/schemas/`. Used to audit migration correctness and detect accidental schema drift.

**BuildConfig generation:**
```kotlin
buildFeatures {
    buildConfig = true
}
```
Required for `BuildConfig.DEBUG` checks that guard the Seeder in `SeederService`, `SeederViewModel`, and `SettingsScreen`.

**Applied plugins (app/build.gradle.kts):**
- `com.android.application`
- `org.jetbrains.kotlin.android`
- `com.google.devtools.ksp`
- `com.google.dagger.hilt.android`
- `org.jetbrains.kotlin.plugin.serialization`
- `org.jetbrains.kotlin.plugin.compose`
