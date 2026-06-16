# Database Structure

**File:** `athlete_data.db`
**Version:** 10
**Export Schema:** false
**AppDatabase:** `app/src/main/java/com/athletedata/openAthleteMetrics/data/db/AppDatabase.kt`

---

## Entities Registered in AppDatabase

```
MetricReadingEntity, SleepSessionEntity, DailySummaryEntity, DailyContextEntity,
QuestionDefinitionEntity, QuestionResponseEntity, ActivityEntity,
DeviceEntity, SyncSessionEntity, RawDeviceDataEntity
```

---

## Entity 1 — MetricReadingEntity

**File:** `data/db/MetricReadingEntity.kt`
**Table:** `metric_readings`
**PK:** `id` (Long, autoGenerate)

| Column | Type | Nullable | Default |
|---|---|---|---|
| id | Long | No | AUTO |
| metric_type | MetricType → String | No | — |
| value | Double | No | — |
| unit | String | No | — |
| recorded_at | Instant → Long | No | — |
| created_at | Instant → Long | No | — |
| source | DataSource → String | No | — |
| driver_id | String | Yes | null |
| confidence | Float | Yes | null |
| meta_json | String | Yes | null |

**Indexes:**
- `(metric_type ASC, recorded_at DESC)`
- `(driver_id, metric_type, recorded_at)` UNIQUE — dedup index

---

## Entity 2 — SleepSessionEntity

**File:** `data/db/SleepSessionEntity.kt`
**Table:** `sleep_sessions`
**PK:** `id` (Long, autoGenerate)

| Column | Type | Nullable | Default |
|---|---|---|---|
| id | Long | No | AUTO |
| date | LocalDate → String | No | — |
| sleep_start_ms | Instant → Long | No | — |
| sleep_end_ms | Instant → Long | No | — |
| duration_minutes | Int | No | — |
| stages_json | String | Yes | null |
| source | DataSource → String | No | — |
| driver_id | String | Yes | null |

**Indexes:**
- `(driver_id, date)` UNIQUE — dedup index (added v8)

---

## Entity 3 — DailySummaryEntity

**File:** `data/db/DailySummaryEntity.kt`
**Table:** `daily_summary`
**PK:** `date` (LocalDate → String, no autoGenerate)

| Column | Type | Nullable | Default |
|---|---|---|---|
| date | LocalDate → String | No | — |
| avg_hr_bpm | Double | Yes | null |
| resting_hr_bpm | Double | Yes | null |
| avg_hrv_ms | Double | Yes | null |
| morning_hrv_ms | Double | Yes | null |
| avg_spo2_pct | Double | Yes | null |
| steps | Int | Yes | null |
| sleep_minutes | Int | Yes | null |
| source | DataSource → String | No | — |
| computed_at | Instant → Long | No | — |

**Indexes:** None

---

## Entity 4 — DailyContextEntity

**File:** `data/db/DailyContextEntity.kt`
**Table:** `daily_context`
**PK:** `date` (LocalDate → String, no autoGenerate)

| Column | Type | Nullable | Default |
|---|---|---|---|
| date | LocalDate → String | No | — |
| fatigue | Int | Yes | null |
| stress | Int | Yes | null |
| motivation | Int | Yes | null |
| sleep_quality | Int | Yes | null |
| performance_feel | Int | Yes | null |
| is_ill | Boolean → Int | No | false (0) |
| illness_notes | String | Yes | null |
| habits_json | String | Yes | null |
| weight_kg | Double | Yes | null |
| body_fat_pct | Double | Yes | null |
| notes | String | Yes | null |
| updated_at | Instant → Long | No | — |

**Indexes:** None

---

## Entity 5 — QuestionDefinitionEntity

**File:** `data/db/QuestionDefinitionEntity.kt`
**Table:** `question_definitions`
**PK:** `id` (Long, autoGenerate)

| Column | Type | Nullable | Default |
|---|---|---|---|
| id | Long | No | AUTO |
| name | String | No | — |
| type | String | No | — |
| category | String | No | — |
| is_visible | Boolean → Int | No | true (1) |
| sort_order | Int | No | — |
| is_starred | Boolean → Int | No | false (0) |

**Indexes:** None

---

## Entity 6 — QuestionResponseEntity

**File:** `data/db/QuestionResponseEntity.kt`
**Table:** `question_responses`
**PK:** `id` (Long, autoGenerate)
**FK:** `question_id` → `question_definitions(id)` ON DELETE CASCADE

| Column | Type | Nullable | Default |
|---|---|---|---|
| id | Long | No | AUTO |
| question_id | Long | No | — |
| date | String | No | — |
| value | String | No | — |
| recorded_at | Long | No | — |

**Indexes:**
- `(question_id)`
- `(question_id, date)` UNIQUE

---

## Entity 7 — ActivityEntity

**File:** `data/db/ActivityEntity.kt`
**Table:** `activities`
**PK:** `id` (Long, autoGenerate)

| Column | Type | Nullable | Default |
|---|---|---|---|
| id | Long | No | AUTO |
| start_time | Instant → Long | No | — |
| end_time | Instant → Long | No | — |
| duration_minutes | Int | No | — |
| device_name | String | No | — |
| user_category | UserCategory → String | Yes | null |
| notes | String | Yes | null |
| source | DataSource → String | No | — |
| driver_id | String | Yes | null |
| avg_hr_bpm | Double | Yes | null |
| max_hr_bpm | Double | Yes | null |
| min_hr_bpm | Double | Yes | null |
| calories | Double | Yes | null |
| active_calories | Double | Yes | null |
| distance_meters | Double | Yes | null |
| steps | Int | Yes | null |
| hr_zones_json | String | Yes | null |

**Indexes:**
- `(driver_id, start_time)` UNIQUE — dedup index (made unique v8)

---

## Entity 8 — DeviceEntity

**File:** `data/db/DeviceEntity.kt`
**Table:** `devices`
**PK:** `id` (Long, autoGenerate)

| Column | Type | Nullable | Default |
|---|---|---|---|
| id | Long | No | AUTO |
| ble_address | String | No | — |
| driver_id | String | No | — |
| display_name | String | No | — |
| last_seen_ms | Long | Yes | null |
| last_sync_ms | Long | Yes | null |
| last_battery_pct | Int | Yes | null |

**Indexes:**
- `(ble_address)` UNIQUE

---

## Entity 9 — SyncSessionEntity

**File:** `data/db/SyncSessionEntity.kt`
**Table:** `sync_sessions`
**PK:** `id` (Long, autoGenerate)
**FK:** `device_id` → `devices(id)` ON DELETE NO_ACTION ON UPDATE NO_ACTION

| Column | Type | Nullable | Default |
|---|---|---|---|
| id | Long | No | AUTO |
| device_id | Long | No | — |
| driver_id | String | No | — |
| started_at | Instant → Long | No | — |
| ended_at | Instant → Long | Yes | null |
| status | SyncStatus → String | No | — |
| records_imported | Int | No | 0 |
| error_message | String | Yes | null |
| packets_received | Int | No | 0 |
| synced_before_quiescence | Boolean → Int | No | false (0) |

**Indexes:**
- `(device_id ASC, started_at DESC)`

---

## Entity 10 — RawDeviceDataEntity

**File:** `data/db/RawDeviceDataEntity.kt`
**Table:** `raw_device_data`
**PK:** `id` (Long, autoGenerate)
**FK:** `sync_session_id` → `sync_sessions(id)` ON DELETE NO_ACTION ON UPDATE NO_ACTION

| Column | Type | Nullable | Default |
|---|---|---|---|
| id | Long | No | AUTO |
| sync_session_id | Long | No | — |
| characteristic_uuid | String | No | — |
| payload | String (Base64) | No | — |
| received_at | Long | No | — |

**Indexes:**
- `(sync_session_id)`

---

## TypeConverters

**File:** `data/db/Converters.kt`
**Registered at:** `@TypeConverters(Converters::class)` on AppDatabase (applies globally)

| Kotlin type | SQLite type | Notes |
|---|---|---|
| `LocalDate` | `String` | ISO-8601 "YYYY-MM-DD" |
| `Instant` | `Long` | Unix epoch milliseconds |
| `MetricType` | `String` | enum name |
| `DataSource` | `String` | enum name |
| `UserCategory` | `String?` | nullable enum name |
| `SyncStatus` | `String` | enum name |

---

## DAOs

### MetricReadingDao — `data/db/MetricReadingDao.kt`

```
insert(entity): suspend Unit                                         @Insert REPLACE
insertAll(entities: List): suspend Unit                              @Insert REPLACE
insertAllOrIgnore(entities: List): suspend List<Long>                @Insert IGNORE
insertOrIgnore(entity): suspend Long                                 @Insert IGNORE (protected)
upsert(entity): suspend Unit                                         @Insert REPLACE
upsertAll(entities: List): suspend Unit                              @Insert REPLACE
upsertAccumulator(reading): suspend AccumulatorWriteOutcome          @Transaction (guarded monotonic upsert)
delete(entity): suspend Unit                                         @Delete
deleteBySource(source: DataSource): suspend Unit                     DELETE FROM metric_readings WHERE source = :source
deleteAll(): suspend Unit                                            DELETE FROM metric_readings
getByDriverTypeAndDate(driverId, metricType, recordedAt): suspend MetricReadingEntity?
getReadingsInRange(metricType, startMs, endMs): Flow<List<MetricReadingEntity>>
getLatestReading(metricType): Flow<MetricReadingEntity?>
getReadingsInRangeOnce(metricType, startMs, endMs): suspend List<MetricReadingEntity>
countSourceDataInRange(source, startMs, endMs): Flow<Int>
countSourceDataInRangeOnce(source, startMs, endMs): suspend Int
```

### SleepSessionDao — `data/db/SleepSessionDao.kt`

```
insert(entity): suspend Unit                                         @Insert REPLACE
insertOrIgnore(entity): suspend Long                                 @Insert IGNORE
delete(entity): suspend Unit                                         @Delete
deleteBySource(source: DataSource): suspend Unit                     DELETE FROM sleep_sessions WHERE source = :source
deleteAll(): suspend Unit                                            DELETE FROM sleep_sessions
getSessionForDate(date: LocalDate): Flow<SleepSessionEntity?>
getSessionsForRange(from, to: LocalDate): Flow<List<SleepSessionEntity>>
getSessionForDateOnce(date: LocalDate): suspend SleepSessionEntity?
getByDriverAndDate(driverId: String, date: LocalDate): suspend SleepSessionEntity?
```

### DailySummaryDao — `data/db/DailySummaryDao.kt`

```
upsert(entity): suspend Unit                                         @Insert REPLACE
delete(entity): suspend Unit                                         @Delete
getSummaryForDate(date: LocalDate): Flow<DailySummaryEntity?>
getSummariesForRange(from, to: LocalDate): Flow<List<DailySummaryEntity>>
getSummaryForDateOnce(date: LocalDate): suspend DailySummaryEntity?
deleteAll(): suspend Unit                                            DELETE FROM daily_summary
```

### DailyContextDao — `data/db/DailyContextDao.kt`

```
upsert(entity): suspend Unit                                         @Insert REPLACE
delete(entity): suspend Unit                                         @Delete
deleteAll(): suspend Unit                                            DELETE FROM daily_context
getContextForDate(date: LocalDate): Flow<DailyContextEntity?>
getContextForDateOnce(date: LocalDate): suspend DailyContextEntity?
getContextsForRange(from, to: LocalDate): Flow<List<DailyContextEntity>>
```

### QuestionDefinitionDao — `data/db/QuestionDefinitionDao.kt`

```
insertAll(entities: List): suspend Unit                              @Insert IGNORE
insert(entity): suspend Long                                         @Insert REPLACE
count(): suspend Int                                                 SELECT COUNT(*) FROM question_definitions
maxCustomSortOrder(): suspend Int?                                   SELECT MAX(sort_order) FROM question_definitions WHERE category = 'CUSTOM'
getLifestyle(): Flow<List<QuestionDefinitionEntity>>
getCustom(): Flow<List<QuestionDefinitionEntity>>
getStarredVisibleLifestyle(): Flow<List<QuestionDefinitionEntity>>
getStarredVisibleCustom(): Flow<List<QuestionDefinitionEntity>>
show(id: Long): suspend Unit                                         UPDATE ... SET is_visible = 1 WHERE id = :id
hideAndUnstar(id: Long): suspend Unit                                UPDATE ... SET is_visible = 0, is_starred = 0 WHERE id = :id
setStar(id: Long, starred: Boolean): suspend Unit                    UPDATE ... SET is_starred = :starred WHERE id = :id
updateNameAndType(id, name, type): suspend Unit                      UPDATE ... SET name = :name, type = :type WHERE id = :id
updateSortOrder(id, sortOrder): suspend Unit                         UPDATE ... SET sort_order = :sortOrder WHERE id = :id
deleteById(id: Long): suspend Unit                                   DELETE FROM question_definitions WHERE id = :id
getLifestyleOnce(): suspend List<QuestionDefinitionEntity>
```

### QuestionResponseDao — `data/db/QuestionResponseDao.kt`

```
upsert(entity): suspend Unit                                         @Insert REPLACE
upsertAll(entities: List): suspend Unit                              @Insert REPLACE
getResponsesForDate(date: String): Flow<List<QuestionResponseEntity>>
getResponseOnce(questionId: Long, date: String): suspend QuestionResponseEntity?
deleteResponse(questionId: Long, date: String): suspend Unit         DELETE FROM question_responses WHERE question_id = :questionId AND date = :date
deleteAll(): suspend Unit                                            DELETE FROM question_responses
getResponsesForRange(questionId, from, to: String): Flow<List<QuestionResponseEntity>>
```

### ActivityDao — `data/db/ActivityDao.kt`

```
insert(entity): suspend Unit                                         @Insert REPLACE
insertAll(entities: List): suspend Unit                              @Insert REPLACE
insertAllOrIgnore(entities: List): suspend List<Long>                @Insert IGNORE
insertOrIgnore(entity): suspend Long                                 @Insert IGNORE
deleteBySource(source: DataSource): suspend Unit                     DELETE FROM activities WHERE source = :source
deleteAll(): suspend Unit                                            DELETE FROM activities
updateCategory(id, category: UserCategory): suspend Unit             UPDATE activities SET user_category = :category WHERE id = :id
updateCategoryAndNotes(id, category?, notes?): suspend Unit          UPDATE activities SET user_category = :category, notes = :notes WHERE id = :id
findNear(driverId, windowStart, windowEnd): suspend ActivityEntity?
getActivitiesInRange(startMs, endMs): Flow<List<ActivityEntity>>
getActivitiesInRangeOnce(startMs, endMs): suspend List<ActivityEntity>
```

### DeviceDao — `data/db/DeviceDao.kt`

```
upsert(entity): suspend Unit                                         @Insert REPLACE
delete(entity): suspend Unit                                         @Delete
deleteAll(): suspend Unit                                            DELETE FROM devices
updateLastSeen(bleAddress, timestampMs): suspend Unit                UPDATE devices SET last_seen_ms = :timestampMs WHERE ble_address = :bleAddress
updateLastSync(bleAddress, timestampMs): suspend Unit                UPDATE devices SET last_sync_ms = :timestampMs WHERE ble_address = :bleAddress
updateLastBatteryPct(bleAddress, pct): suspend Unit                  UPDATE devices SET last_battery_pct = :pct WHERE ble_address = :bleAddress
getAllDevices(): Flow<List<DeviceEntity>>
getById(id: Long): suspend DeviceEntity?
getDeviceByAddress(bleAddress: String): suspend DeviceEntity?
```

### SyncSessionDao — `data/db/SyncSessionDao.kt`

```
insert(entity): suspend Long                                         @Insert REPLACE
update(entity): suspend Unit                                         @Update REPLACE
deleteForDevice(deviceId: Long): suspend Unit                        DELETE FROM sync_sessions WHERE device_id = :deviceId
deleteOlderThan(cutoffMs: Long): suspend Unit                        DELETE FROM sync_sessions WHERE started_at < :cutoffMs
deleteAll(): suspend Unit                                            DELETE FROM sync_sessions
markOldPartialsAsFailed(cutoffMs): suspend Unit                      UPDATE sync_sessions SET status = 'FAILED' WHERE status = 'PARTIAL' AND ended_at IS NULL AND started_at < :cutoffMs
getById(id: Long): suspend SyncSessionEntity?
getRecentPartial(cutoffMs: Long): suspend List<SyncSessionEntity>    WHERE status='PARTIAL' AND ended_at IS NULL AND started_at > :cutoffMs
getSessionsForDevice(deviceId: Long): Flow<List<SyncSessionEntity>>  ORDER BY started_at DESC
getLatestSessionForDevice(deviceId: Long): suspend SyncSessionEntity?
```

### RawDeviceDataDao — `data/db/RawDeviceDataDao.kt`

```
insertAll(entities: List): suspend Unit                              @Insert REPLACE
deleteForSession(syncSessionId: Long): suspend Unit                  DELETE FROM raw_device_data WHERE sync_session_id = :syncSessionId
deleteForDevice(deviceId: Long): suspend Unit                        DELETE ... WHERE sync_session_id IN (SELECT id FROM sync_sessions WHERE device_id = :deviceId)
deleteOlderThan(thresholdMs: Long): suspend Unit                     DELETE FROM raw_device_data WHERE received_at < :thresholdMs
deleteAll(): suspend Unit                                            DELETE FROM raw_device_data
getForSession(syncSessionId: Long): suspend List<RawDeviceDataEntity>
getForDeviceAfter(deviceId, sinceMs): suspend List<RawDeviceDataEntity>  INNER JOIN sync_sessions WHERE device_id = :deviceId AND received_at > :sinceMs
```

---

## Migrations

All migrations are defined in `AppDatabase.Companion` and registered in `DatabaseModule`.

| Migration | Change |
|---|---|
| 2→3 | Creates `question_definitions` and `question_responses` tables; seeds lifestyle questions |
| 3→4 | `ALTER TABLE question_definitions ADD COLUMN is_starred INTEGER NOT NULL DEFAULT 0`; sets is_starred=1 for first 5 questions |
| 4→5 | `CREATE UNIQUE INDEX index_metric_readings_dedup ON metric_readings(driver_id, metric_type, recorded_at)` |
| 5→6 | Creates `devices`, `sync_sessions`, `raw_device_data`, `activities` tables with all columns |
| 6→7 | `ALTER TABLE devices ADD COLUMN last_battery_pct INTEGER` |
| 7→8 | Creates `UNIQUE INDEX index_sleep_sessions_dedup ON sleep_sessions(driver_id, date)`; drops old activities index and creates `UNIQUE INDEX index_activities_dedup ON activities(driver_id, start_time)` |
| 8→9 | `DELETE FROM sleep_sessions WHERE sleep_end_ms = 0 OR sleep_end_ms <= sleep_start_ms OR (sleep_end_ms - sleep_start_ms) < 60000` (corrupt data purge) |
| 9→10 | `ALTER TABLE sync_sessions ADD COLUMN packets_received INTEGER NOT NULL DEFAULT 0`; `ALTER TABLE sync_sessions ADD COLUMN synced_before_quiescence INTEGER NOT NULL DEFAULT 0` |

**Fallback:** `.fallbackToDestructiveMigration()` — missing migration wipes and rebuilds the database.

---

## Hardcoded Schema Assumptions Outside DAOs

**`SettingsViewModel.kt`** — the only non-DAO database interaction:
- Issues `PRAGMA wal_checkpoint(TRUNCATE)` directly on the SQLite helper (no schema dependency)
- Calls `deleteAll()` on all 9 data DAOs in FK-safe order (raw_device_data → sync_sessions → devices, question_responses first, then metrics/sleep/summary/context/activities); `question_definitions` is intentionally excluded from reset

No other repository or ViewModel files contain raw SQL, column name strings, or table name strings. All schema access flows through the DAOs.

---

## Foreign Key Relationships

```
question_responses.question_id → question_definitions.id  (CASCADE delete)
sync_sessions.device_id        → devices.id               (NO ACTION)
raw_device_data.sync_session_id → sync_sessions.id        (NO ACTION)
```

## Critical Unique Indexes

| Table | Columns | Purpose |
|---|---|---|
| metric_readings | (driver_id, metric_type, recorded_at) | dedup |
| sleep_sessions | (driver_id, date) | dedup |
| activities | (driver_id, start_time) | dedup |
| question_responses | (question_id, date) | dedup |
| devices | (ble_address) | identity |
| sync_sessions | (device_id, started_at DESC) | lookup |
