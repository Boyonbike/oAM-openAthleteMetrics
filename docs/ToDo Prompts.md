# Athlete Data App — Database Refactor
## Claude Code Prompt Sequence

Work through these steps in order. One step = one Claude Code session.
Run the app and verify a clean build after each session before moving to the next.

**Mode guide:**
- **Plan mode** — use when the session creates or rewrites many files. Review the plan before accepting so you can catch structural mistakes before any code is written.
- **Normal mode** — use for smaller, well-scoped sessions where the change surface is limited and predictable.

---

## Session 1 — New Entities and DAOs
**Mode: Plan** — creates a large number of new files. Review the plan to verify every table and column is correct before accepting.

```
I am refactoring the database schema for my Android athlete tracking 
app. This session creates new entity and DAO files only. Do not touch 
AppDatabase.kt, do not change the version number, do not write any 
migration yet.

PROJECT CONTEXT:
- Package: com.athletedata.openAthleteMetrics
- Language: Kotlin
- Architecture: MVVM + Repository, Room, Hilt, Kotlin Coroutines
- Current database version: 10
- Migration strategy in Session 2 will be destructive

CURRENT SCHEMA:
The app currently has these tables:
metric_readings, sleep_sessions, daily_summary, daily_context,
question_definitions, question_responses, activities, devices,
sync_sessions, raw_device_data

metric_readings currently stores ALL metric types in one flat table
with columns: id, metric_type, value, unit, recorded_at, created_at,
source, driver_id, confidence, meta_json

sleep_sessions currently stores sleep stage data as a JSON blob
in a stages_json column

EXISTING ENUMS IN USE:
- DataSource: DEVICE, MANUAL, SEEDER
- MetricType: HR, HRV, RHR, SPO2, STEPS, SLEEP_STAGE, BATTERY
- SleepStage: needs creating if not already present (DEEP, LIGHT, REM, AWAKE)

WHAT TO BUILD:

PART A — STANDARD COLUMNS
Every new time-series entity must have these common columns:
- id: Long, autoGenerate primary key
- recorded_at: Instant stored as Long (unix epoch ms)
- created_at: Instant stored as Long (unix epoch ms)
- source: DataSource enum stored as String
- driver_id: String, nullable
- confidence: Float, nullable
- meta_json: String, nullable
- UNIQUE index on (driver_id, recorded_at) for dedup

Derived metric tables additionally need:
- computed_by_version: Int, not null
  Tracks which algorithm version produced this row so stale 
  derived data can be identified and recomputed later

PART B — NEW DEDICATED TIME-SERIES ENTITIES

Create a Room @Entity and DAO for each:

1. HrReadingEntity — table: hr_readings
   Extra columns: bpm (Int, not null)

2. HrvReadingEntity — table: hrv_readings (DERIVED)
   Extra columns: rmssd_ms (Double, not null)

3. SpO2ReadingEntity — table: spo2_readings
   Extra columns: percentage (Double, not null)

4. RespirationReadingEntity — table: respiration_readings
   Extra columns: breaths_per_minute (Double, not null)

5. SkinTempReadingEntity — table: skin_temp_readings
   Extra columns: celsius (Double, not null)

6. StepsReadingEntity — table: steps_readings
   Extra columns: cumulative_steps (Int, not null)

7. BloodPressureReadingEntity — table: blood_pressure_readings
   Extra columns: systolic (Int, not null), diastolic (Int, not null)

8. GlucoseReadingEntity — table: glucose_readings
   Extra columns: value (Double, not null), 
   unit (String, not null) — either "mmol" or "mg_dl"

9. ActiveCalorieReadingEntity — table: active_calorie_readings
   Extra columns: calories (Double, not null)
   These are calories burned during activity periods only

10. TotalCalorieReadingEntity — table: total_calorie_readings
    Extra columns: calories (Double, not null)
    These are full day expenditure including resting metabolic rate

11. SleepStageEntity — table: sleep_stages (DERIVED)
    This table is different from the others — do not use standard 
    columns. Use these columns instead:
    - id: Long, autoGenerate primary key
    - session_id: Long, FK → sleep_sessions(id) ON DELETE CASCADE
    - stage: SleepStage enum stored as String
    - start_ms: Long (Instant stored as Long)
    - end_ms: Long (Instant stored as Long)
    - duration_minutes: Int
    - source: DataSource enum stored as String
    - driver_id: String, nullable
    - computed_by_version: Int, not null
    - UNIQUE index on (session_id, start_ms)
    Note: no recorded_at, created_at, confidence, or meta_json

PART C — STAGING TABLE
Rename metric_readings to metric_readings_staging. This table is 
now the landing zone for unknown metric types reported by JSON 
manifest drivers that do not have a dedicated table yet.
Keep all existing columns exactly as they are.
Update the entity class name to MetricReadingStagingEntity.
Update the DAO class name to MetricReadingStagingDao.
Keep all existing DAO methods, just update class and table names.

PART D — UPDATED EXISTING ENTITIES

SleepSessionEntity:
- Remove the stages_json column entirely
- All other columns unchanged

DailySummaryEntity — add these new columns, all nullable 
except computed_by_version:
- resting_hr_bpm: Double, nullable (already exists — verify only)
- sleep_deep_minutes: Int, nullable
- sleep_light_minutes: Int, nullable
- sleep_rem_minutes: Int, nullable
- sleep_awake_minutes: Int, nullable
- skin_temp_avg_c: Double, nullable
- skin_temp_min_c: Double, nullable
- skin_temp_max_c: Double, nullable
- respiration_avg: Double, nullable
- hrv_min_ms: Double, nullable
- hrv_max_ms: Double, nullable
- spo2_min_pct: Double, nullable
- spo2_max_pct: Double, nullable
- steps_active_minutes: Int, nullable
- total_calories: Double, nullable
- active_calories: Double, nullable
- computed_by_version: Int, not null, default 0

PART E — DAOs
Create a DAO for each new entity with these methods at minimum:

Standard time-series DAOs (entities 1–10):
- insert(entity): suspend Unit — @Insert REPLACE
- insertAll(entities: List): suspend Unit — @Insert REPLACE
- insertAllOrIgnore(entities: List): suspend List<Long> — @Insert IGNORE
- deleteBySource(source: DataSource): suspend Unit
- deleteAll(): suspend Unit
- getReadingsInRange(startMs: Long, endMs: Long): 
  Flow<List<Entity>>
- getReadingsInRangeOnce(startMs: Long, endMs: Long): 
  suspend List<Entity>
- getLatestReading(): Flow<Entity?>

SleepStageDao additionally:
- getStagesForSession(sessionId: Long): 
  Flow<List<SleepStageEntity>>
- getStagesForSessionOnce(sessionId: Long): 
  suspend List<SleepStageEntity>
- deleteForSession(sessionId: Long): suspend Unit
- getStagesInRange(startMs: Long, endMs: Long): 
  Flow<List<SleepStageEntity>>

DailySummaryDao — do not replace, just verify existing methods 
still work with new columns. No new methods needed.

PART F — TYPE CONVERTERS
In Converters.kt add a SleepStage enum converter if not already 
present. All other types are already handled.

Do not register any new entities in AppDatabase.kt yet.
Do not modify any repository files yet.
Do not modify any ViewModel files yet.
Show all new and modified files with full content.
```

---

## Session 2 — AppDatabase Migration and Repositories
**Mode: Plan** — touches AppDatabase, the migration, and creates all new repositories. Review the plan to verify the drop/create order and repository bindings before accepting.

```
I am continuing a database schema refactor for my Android athlete 
tracking app. Session 1 is complete — all new entity and DAO files 
exist and compile. This session updates AppDatabase, writes the 
migration, and creates all new repositories.

PROJECT CONTEXT:
- Package: com.athletedata.openAthleteMetrics
- Language: Kotlin
- Architecture: MVVM + Repository, Room, Hilt, Kotlin Coroutines
- Current database version: 10, bumping to 11
- Migration strategy: destructive — explicit DROP and CREATE statements
- Hilt modules are in the di/ package
- Repositories follow the pattern: interface + Room-prefixed implementation

NEW ENTITIES FROM SESSION 1:
HrReadingEntity, HrvReadingEntity, SpO2ReadingEntity,
RespirationReadingEntity, SkinTempReadingEntity, StepsReadingEntity,
BloodPressureReadingEntity, GlucoseReadingEntity,
ActiveCalorieReadingEntity, TotalCalorieReadingEntity,
SleepStageEntity, MetricReadingStagingEntity

MODIFIED ENTITIES FROM SESSION 1:
- SleepSessionEntity: stages_json column removed
- DailySummaryEntity: new columns added
- MetricReadingEntity renamed to MetricReadingStagingEntity
- metric_readings table renamed to metric_readings_staging

TASK 1 — AppDatabase.kt
- Bump version from 10 to 11
- Add all new entities to the @Database entities list
- Replace MetricReadingEntity with MetricReadingStagingEntity
- Add abstract DAO functions for all new DAOs
- Replace MetricReadingDao with MetricReadingStagingDao
- Write Migration 10→11 as explicit DROP and CREATE statements.
  Do not rely on fallbackToDestructiveMigration for this migration.
  
  Drop order (respect FK constraints):
  1. raw_device_data
  2. sync_sessions  
  3. question_responses
  4. sleep_stages (if exists from partial previous attempt)
  5. activities
  6. metric_readings (old name)
  7. metric_readings_staging (if exists)
  8. hr_readings, hrv_readings, spo2_readings, respiration_readings,
     skin_temp_readings, steps_readings, blood_pressure_readings,
     glucose_readings, active_calorie_readings, total_calorie_readings
  9. sleep_sessions
  10. daily_summary
  11. daily_context
  12. devices
  13. question_definitions

  Recreate all tables with new schema.
  Recreate all indexes including all UNIQUE dedup indexes.
  Recreate question_definitions seed data for all lifestyle 
  questions that was originally seeded in Migration 2→3:
  Fatigue, Stress, Motivation, Sleep Quality, Performance Feel,
  Energy, Focus, Muscle Soreness, Mental Clarity
  All as category=LIFESTYLE, type=SCALE, is_visible=1, is_starred=0
  with appropriate sort_order values.

- Remove fallbackToDestructiveMigration() — the explicit migration 
  handles everything
- Register the migration in DatabaseModule

TASK 2 — New repositories
Create interface and Room implementation for each new entity.
Follow the existing pattern exactly — interface in data/repository/,
implementation prefixed with Room, bound in DatabaseModule via Hilt.

Repositories needed:
- HrReadingRepository + RoomHrReadingRepository
- HrvReadingRepository + RoomHrvReadingRepository
- SpO2ReadingRepository + RoomSpO2ReadingRepository
- RespirationReadingRepository + RoomRespirationReadingRepository
- SkinTempReadingRepository + RoomSkinTempReadingRepository
- StepsReadingRepository + RoomStepsReadingRepository
- BloodPressureReadingRepository + RoomBloodPressureReadingRepository
- GlucoseReadingRepository + RoomGlucoseReadingRepository
- ActiveCalorieReadingRepository + RoomActiveCalorieReadingRepository
- TotalCalorieReadingRepository + RoomTotalCalorieReadingRepository
- SleepStageRepository + RoomSleepStageRepository
- Rename MetricReadingRepository to MetricReadingStagingRepository
  and update its implementation accordingly

Each standard repository interface exposes:
- suspend fun insert(entity)
- suspend fun insertAll(entities: List)
- suspend fun deleteBySource(source: DataSource)
- fun getReadingsInRange(startMs: Long, endMs: Long): Flow<List<>>
- suspend fun getReadingsInRangeOnce(startMs: Long, endMs: Long): List<>
- fun getLatestReading(): Flow<entity?>

SleepStageRepository additionally:
- fun getStagesForSession(sessionId: Long): Flow<List<SleepStageEntity>>
- suspend fun getStagesForSessionOnce(sessionId: Long): 
  List<SleepStageEntity>
- suspend fun deleteForSession(sessionId: Long)
- fun getStagesInRange(startMs: Long, endMs: Long): 
  Flow<List<SleepStageEntity>>

TASK 3 — SettingsViewModel database reset
Update the deleteAll() sequence to include all new tables in 
FK-safe order:
1. raw_device_data
2. sync_sessions
3. question_responses
4. sleep_stages
5. active_calorie_readings
6. total_calorie_readings
7. hr_readings
8. hrv_readings
9. spo2_readings
10. respiration_readings
11. skin_temp_readings
12. steps_readings
13. blood_pressure_readings
14. glucose_readings
15. metric_readings_staging
16. sleep_sessions
17. daily_summary
18. daily_context
19. activities
20. devices
Note: question_definitions intentionally excluded as before

Show all new and modified files with full content.
```

---

## Session 3 — Seeder and DailySummaryWorker
**Mode: Plan** — rewrites two complex files with detailed generation logic. Review the plan to verify the sleep stage contiguity logic and all aggregation calculations before accepting.

```
I am continuing a database schema refactor for my Android athlete 
tracking app. Sessions 1 and 2 are complete — all new tables exist,
all repositories are wired, migration is done. This session rewrites 
the seeder and DailySummaryWorker to use the new schema.

PROJECT CONTEXT:
- Package: com.athletedata.openAthleteMetrics
- Language: Kotlin
- Architecture: MVVM + Repository, Room, Hilt, Kotlin Coroutines
- All new repositories are available via Hilt injection
- metric_readings_staging is now the catch-all for unknown metrics only
- The following dedicated repositories now exist:
  HrReadingRepository, HrvReadingRepository, SpO2ReadingRepository,
  RespirationReadingRepository, SkinTempReadingRepository,
  StepsReadingRepository, ActiveCalorieReadingRepository,
  TotalCalorieReadingRepository, SleepStageRepository
- sleep_sessions no longer has a stages_json column
- sleep stages are stored in sleep_stages table

TASK 1 — SeederService
Rewrite SeederService to write all data to dedicated tables.
Keep the same random seed (42) for deterministic output.
Keep source = SEEDER on all rows.
Keep the same 30-day window ending today.
Keep progress reporting via StateFlow<SeederState>.
Keep all three public functions: seedThirtyDays(), seedToday(), 
clearSeederData().

clearSeederData() must delete from all dedicated tables by source,
not just metric_readings_staging.

Generate per day:

hr_readings — one row every 5 minutes (288 rows/day):
- 48–62 bpm overnight (22:00–06:00)
- 60–80 bpm daytime with ±3 bpm Gaussian noise
- 1–2 workout blocks per week, 30–60 min at 130–165 bpm

hrv_readings (computed_by_version = 1) — one row per hour:
- Morning values 45–75 ms
- Mild mid-week dip, weekend recovery
- Loosely inverse-correlated with previous night fatigue
- confidence = 0.8f on all seeded HRV rows

spo2_readings — one row per hour overnight (22:00–08:00):
- Range 95–99%
- One night per week with a dip to 93–94%

skin_temp_readings — 4–8 readings per day at irregular intervals:
- Range 34.0–36.5 celsius
- Slight overnight drop

respiration_readings — one row per hour:
- Range 12–18 breaths per minute
- Slightly elevated during workout blocks

steps_readings — one row per hour during waking hours (06:00–22:00):
- Cumulative — each row is running total for the day
- Final daily value 4,000–14,000 steps

active_calorie_readings — one row per workout block:
- Only on days with a workout
- Range 200–600 calories per workout

total_calorie_readings — one row per day at 23:59:
- Range 1,800–3,200 calories
- Higher on workout days

sleep_sessions + sleep_stages (computed_by_version = 1):
- One sleep_session per night, 6–9 hours
- Insert sleep_session first, get its id
- Insert corresponding sleep_stages rows using that session id
- Stage sequence must be physiologically realistic:
  Begin with LIGHT, then cycle DEEP → LIGHT → REM repeatedly
  Typical cycle length 90 minutes
  3–5 complete cycles per night
  Include 1–3 brief AWAKE periods of 5–15 minutes
  All stage rows must be perfectly contiguous:
  end_ms of row N must equal start_ms of row N+1
  duration_minutes must equal (end_ms - start_ms) / 60000
  Sum of all stage durations must equal sleep session 
  duration_minutes exactly

daily_context: unchanged from current implementation

After all data inserted, trigger DailySummaryWorker for each 
seeded date.

TASK 2 — DailySummaryWorker
Rewrite to aggregate from dedicated tables. Keep WorkManager 
setup and date string input contract unchanged.

Compute for the given date:

From hr_readings:
- avg_hr_bpm: mean of all readings for that date
- resting_hr_bpm: mean of the lowest continuous 5-minute window
  between 00:00 and 06:00 local time

From hrv_readings:
- avg_hrv_ms: mean of all readings for that date
- morning_hrv_ms: first reading after 05:00 local time
- hrv_min_ms: minimum value for that date
- hrv_max_ms: maximum value for that date

From spo2_readings:
- avg_spo2_pct: mean of all readings for that date
- spo2_min_pct: minimum value for that date
- spo2_max_pct: maximum value for that date

From skin_temp_readings:
- skin_temp_avg_c: mean of all readings for that date
- skin_temp_min_c: minimum value for that date
- skin_temp_max_c: maximum value for that date

From respiration_readings:
- respiration_avg: mean of all readings for that date

From steps_readings:
- steps: value of the last reading for that date
- steps_active_minutes: count of one-hour windows where the 
  step delta between consecutive readings exceeds 500

From active_calorie_readings:
- active_calories: sum of all readings for that date

From total_calorie_readings:
- total_calories: value of the last reading for that date

From sleep_sessions + sleep_stages:
- sleep_minutes: duration_minutes from sleep_session for that date
- sleep_deep_minutes: sum of duration_minutes WHERE stage = DEEP
- sleep_light_minutes: sum of duration_minutes WHERE stage = LIGHT
- sleep_rem_minutes: sum of duration_minutes WHERE stage = REM
- sleep_awake_minutes: sum of duration_minutes WHERE stage = AWAKE

Set source = most common source among all readings for that date.
Set computed_by_version = 1.

Show all modified files with full content.
```

---

## Session 4 — Driver and Processor Architecture
**Mode: Plan** — introduces new interfaces and modifies the BLE engine routing logic. Review the plan to verify the routing table and session lifecycle changes before accepting.

```
I am continuing a database schema refactor for my Android athlete 
tracking app. Sessions 1–3 are complete. This session implements 
the MetricProcessor architecture and updates the driver system and 
BLE engine to write to the new dedicated tables.

PROJECT CONTEXT:
- Package: com.athletedata.openAthleteMetrics
- Language: Kotlin
- Architecture: MVVM + Repository, Room, Hilt, Kotlin Coroutines
- All new repositories are available via Hilt injection
- The dedicated tables are the primary storage for all known metrics
- metric_readings_staging is the catch-all for unknown metric types only
- The existing DeviceDriver interface is in the driver/ package
- The BLE engine is in the ble/ package

TASK 1 — MetricProcessor interface
Create MetricProcessor.kt in the driver package:

interface MetricProcessor {
    fun onReading(reading: MetricReading)
    fun onSyncComplete(): List<MetricReading>
}

onReading() is called for every MetricReading produced by 
driver.parse() during a sync. The processor buffers what it 
needs internally.

onSyncComplete() is called once when the end-of-sync marker is 
received. Returns all derived MetricReadings computed from the 
buffered data — HRV, sleep stages, and any other derived metrics 
the driver computes. Returns empty list if nothing to compute.

The processor is stateful and session-scoped — a new instance is 
created for each sync session via driver.createProcessor().

TASK 2 — DeviceDriver interface
Add one method to the existing DeviceDriver interface with a 
default implementation:

fun createProcessor(): MetricProcessor? = null

This is the only change. All existing drivers compile without 
any modification.

TASK 3 — BLE Engine routing table
The BLE engine must route MetricReading objects to the correct 
repository based on metric type. Create or update the routing 
logic as follows:

MetricType.HR → HrReadingRepository
MetricType.HRV → HrvReadingRepository
MetricType.SPO2 → SpO2ReadingRepository
MetricType.RESPIRATION → RespirationReadingRepository
MetricType.SKIN_TEMP → SkinTempReadingRepository
MetricType.STEPS → StepsReadingRepository
MetricType.ACTIVE_CALORIES → ActiveCalorieReadingRepository
MetricType.TOTAL_CALORIES → TotalCalorieReadingRepository
MetricType.BLOOD_PRESSURE → BloodPressureReadingRepository
MetricType.GLUCOSE → GlucoseReadingRepository
MetricType.SLEEP_STAGE → this requires special handling.
  Sleep stage MetricReadings cannot be written directly to 
  sleep_stages because that table requires a session_id FK.
  Route SLEEP_STAGE type readings to metric_readings_staging 
  with a meta_json flag: {"pending_sleep_stage": true}
  Add a TODO comment explaining this needs a post-processor 
  that groups pending sleep stage readings into a SleepSession 
  and SleepStage rows once a complete night is available.
All unrecognised MetricType values → MetricReadingStagingRepository

TASK 4 — BLE Engine session lifecycle
Make these changes to the BLE engine:

On connection established:
- Call driver.createProcessor() and store as a nullable 
  session-scoped val. Reset to null on disconnect.

After each driver.parse() call:
- For each MetricReading returned, call processor?.onReading(reading)
- Route each reading to the correct repository per routing table above

On end-of-sync marker received:
- Call processor?.onSyncComplete()
- Route all returned readings through the same routing table
- Collect all dates that received new data during this sync
- Trigger DailySummaryWorker for each affected date

On disconnect:
- Set processor reference to null
- Clear any session-scoped state

TASK 5 — MetricType enum
Add any missing values to the MetricType enum needed for the 
routing table:
RESPIRATION, SKIN_TEMP, ACTIVE_CALORIES, TOTAL_CALORIES,
BLOOD_PRESSURE, GLUCOSE
Keep all existing values.

TASK 6 — Hilt
Inject all new repositories into the BLE engine via Hilt.
Update the BLE engine @Inject constructor accordingly.

Do not implement the WHOOP driver — that is future work.
Do not modify any UI files.
Show all new and modified files with full content.
```

---

## Session 5 — UI Wiring Verification

```
I am continuing a database schema refactor for my Android athlete 
tracking app. Sessions 1–4 are complete. This session verifies and 
fixes all existing UI wiring to ensure every screen reads from the 
correct new tables. No new UI features — only fixing what is broken 
and ensuring everything compiles and runs correctly.

PROJECT CONTEXT:
- Package: com.athletedata.openAthleteMetrics
- Language: Kotlin
- Architecture: MVVM + Repository, Room, Hilt, Kotlin Coroutines
- daily_summary is still the primary source for Dashboard metric cards
- DailySummaryWorker populates daily_summary from dedicated tables
- History page reads time-series data for its charts
- sleep_sessions no longer has stages_json
- metric_readings no longer exists — it is now metric_readings_staging
- MetricReadingRepository is now MetricReadingStagingRepository
- All ViewModels that previously injected MetricReadingRepository 
  must be updated

TASK 1 — Audit all ViewModels
Scan every ViewModel in the codebase. For each one report:
- Which repositories it currently injects
- Whether it references MetricReadingRepository anywhere
- Whether it references metric_type values that have changed
- Whether it references stages_json anywhere
- Whether it references daily_summary columns that no longer exist
  or new columns that need wiring

Fix every issue found.

TASK 2 — Dashboard
Verify OverviewViewModel reads all metric card values from 
daily_summary via DailySummaryRepository. Confirm all column 
mappings in DailySummaryEntity are correctly exposed through 
the repository and consumed by the ViewModel. No new cards —
just ensure nothing is broken or missing.

TASK 3 — History page
MetricDetailViewModel currently serves time-series data for the 
graph. Update it so that for each MetricType it reads from the 
correct dedicated repository rather than MetricReadingRepository.
The data shape exposed to the UI must remain unchanged —
a list of timestamp + value pairs. The ViewModel handles the 
mapping from entity type to that shape internally.

TASK 4 — Sleep screens
Find every screen or ViewModel that previously read stages_json 
from SleepSessionEntity. Since that column no longer exists, 
these references will fail to compile. Update them to read from 
SleepStageRepository instead using getStagesForSession().

TASK 5 — Seeder banner
The Dashboard currently checks metric_readings for 
source = SEEDER to decide whether to show the amber banner.
Update this check to query hr_readings for source = SEEDER 
instead, since hr_readings will always contain seeded data 
if any seeding has occurred.

TASK 6 — Compile and runtime verification
After all fixes:
- Confirm no ViewModel or Repository references the old 
  metric_readings table name or MetricReadingRepository
- Confirm no code references stages_json anywhere
- Confirm no code references daily_summary columns that 
  no longer exist
- Confirm MetricType enum values used in UI match the 
  updated enum
- Build the app and verify it compiles cleanly
- Run the seeder from Settings debug panel and verify:
  - Progress reaches 100% without crashing
  - Dashboard metric cards show values for today
  - Amber seeder banner appears
  - Swiping to yesterday shows different values
  - Swiping to a date beyond 30 days shows empty state

For any screen that would meaningfully benefit from the richer 
data now available — intraday HR graph, sleep stage timeline, 
HRV curve — add a TODO comment noting what could be built but 
do not implement it.

Report every file changed and why.
Show all modified files with full content.
```

---

## Notes

- **Session 3 is the most complex** — the sleep stage seeder with perfectly contiguous rows is the part most likely to need error feedback. Paste errors back and iterate before moving to Session 4.
- **Never move to the next session with a broken build.**
- **Session 5 is verification only** — if Claude suggests new UI features, redirect it back to wiring only.