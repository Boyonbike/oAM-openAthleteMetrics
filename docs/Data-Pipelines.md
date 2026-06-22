# Data Pipelines

---

# HRV Pipeline

End-to-end walkthrough of how a single HRV reading travels from the BLE device driver to every UI surface that displays it.

---

## Flow Diagram

```
BLE Device
    │  raw characteristic bytes
    ▼
BleEngine.handleNotification()          ble/BleEngine.kt
    │  byte[] → reassemble fragmented packets
    ▼
WasmDriverEngine.parseMetrics()         ble/wasm/WasmDriverEngine.kt
    │  WASM export called; returns JSON string
    ▼
MetricWasmDto (deserialised)            ble/wasm/WasmParseDto.kt
    │  { metricType: HRV, value: 45.0, unit: "ms",
    │    recordedAtMs: Long, confidence: Float?, metaJson: String? }
    ▼
MetricReading (domain model)            data/model/MetricReading.kt
    │  { metricType: HRV, value: 45.0, unit: "ms",
    │    recordedAt: Instant, createdAt: Instant,
    │    source: DEVICE, driverId: String?, ... }
    ▼
SyncValidator.validateReadings()        ble/sync/SyncValidator.kt
    │  drop if invalid; pass if accepted
    ▼
BleEngine.routeReading()
    │  HRV is a DEDICATED_METRIC_TYPE → skips pendingMetrics staging map
    ▼
MetricRouter.route()                    ble/sync/MetricRouter.kt
    │  when (MetricType.HRV) → HrvReadingRepository.insert(reading.toHrvEntity())
    ▼
MetricReading.toHrvEntity()             data/db/HrvReadingEntity.kt
    │  generic `value` (Double) → typed `rmssdMs` (Double)
    ▼
HrvReadingDao.insert()                  data/db/HrvReadingDao.kt
    │  @Insert(onConflict = REPLACE)
    ▼
SQLite  hrv_readings table
    │
    ├──► DailySummaryWorker              (aggregation — see Stage 4)
    │
    ├──► History Screen                  (trend chart via DailySummary)
    ├──► Daily Detail Screen             (day view via DailySummary + raw reads)
    ├──► Metric Detail Screen            (single-metric trend via raw reads)
    └──► Dashboard Widget                (navigates to Daily Detail)
```

---

## Stage 1 — Driver Output

The WASM driver engine is called when a BLE characteristic notification arrives. It invokes the WASM binary's `parseMetrics` export, which returns a JSON array. Each element is decoded into a `MetricWasmDto`:

```kotlin
// ble/wasm/WasmParseDto.kt
@Serializable
internal data class MetricWasmDto(
    val metricType: MetricType,   // MetricType.HRV
    val value: Double,            // RMSSD in milliseconds
    val unit: String,             // "ms"
    val recordedAtMs: Long,       // device timestamp (epoch ms)
    val confidence: Float? = null,
    val metaJson: String? = null,
)
```

`WasmDriverEngine.parseMetrics()` immediately converts each DTO to the app's domain model:

```kotlin
MetricReading(
    metricType = dto.metricType,      // HRV
    value      = dto.value,           // RMSSD ms
    unit       = dto.unit,            // "ms"
    recordedAt = Instant.ofEpochMilli(dto.recordedAtMs),
    createdAt  = Instant.now(),       // time of parse, not device time
    source     = DataSource.DEVICE,
    driverId   = driverId,
    confidence = dto.confidence,
    metaJson   = dto.metaJson,
)
```

**Key files:**
- `ble/wasm/WasmParseDto.kt` — `MetricWasmDto`
- `ble/wasm/WasmDriverEngine.kt` — `parseMetrics()`
- `data/model/MetricReading.kt` — domain model
- `data/model/MetricType.kt` — `MetricType.HRV` declared here; also in `DEDICATED_METRIC_TYPES`

---

## Stage 2 — Validation & Routing

`BleEngine` passes each `MetricReading` through `SyncValidator.validateReadings()`. Rejected readings are dropped with a warning log. Accepted readings are routed:

```kotlin
// ble/BleEngine.kt
when (result) {
    is ValidationResult.Accepted -> {
        routeReading(result.item)
        // HRV is in DEDICATED_METRIC_TYPES so it is NOT added to pendingMetrics
        if (reading.metricType !in MetricType.DEDICATED_METRIC_TYPES) {
            pendingMetrics[...] = reading
        }
    }
}
```

`MetricRouter` handles the final dispatch:

```kotlin
// ble/sync/MetricRouter.kt
when (reading.metricType) {
    MetricType.HRV -> hrvReadingRepository.insert(reading.toHrvEntity())
    ...
}
```

The extension function `MetricReading.toHrvEntity()` (in `HrvReadingEntity.kt`) performs the only field rename in the pipeline: the generic `value: Double` becomes the typed `rmssdMs: Double`. All other fields map directly.

**Key files:**
- `ble/BleEngine.kt`
- `ble/sync/MetricRouter.kt`
- `data/db/HrvReadingEntity.kt` — `toHrvEntity()` extension

---

## Stage 3 — Database

### Table: `hrv_readings`

Added in `MIGRATION_10_11`. Current DB version: 13 (no HRV schema changes since v11).

| Column | SQLite type | Kotlin type | Notes |
|--------|-------------|-------------|-------|
| `id` | INTEGER PK AUTOINCREMENT | `Long` | |
| `recorded_at` | INTEGER NOT NULL | `Instant` | stored as epoch ms via TypeConverter |
| `created_at` | INTEGER NOT NULL | `Instant` | time of parse/insert |
| `source` | TEXT NOT NULL | `DataSource` | DEVICE / MANUAL / SEEDER |
| `driver_id` | TEXT | `String?` | nullable; part of unique index |
| `confidence` | REAL | `Float?` | signal quality [0.0, 1.0] |
| `meta_json` | TEXT | `String?` | driver-specific extras |
| `rmssd_ms` | REAL NOT NULL | `Double` | RMSSD in milliseconds |
| `computed_by_version` | INTEGER NOT NULL | `Int` | algorithm version for future migrations |

**Unique index:** `(driver_id, recorded_at)` — the REPLACE conflict strategy means a later sync of the same reading overwrites any partial earlier write.

### DAO: `HrvReadingDao`

```kotlin
// data/db/HrvReadingDao.kt
getReadingsInRangeOnce(startMs: Long, endMs: Long): List<HrvReadingEntity>
// SELECT * FROM hrv_readings WHERE recorded_at >= startMs AND recorded_at < endMs ORDER BY recorded_at ASC

insert(entity)                   // REPLACE on conflict
insertAll(entities)              // REPLACE on conflict
insertAllOrIgnore(entities)      // IGNORE on conflict; returns row IDs (-1 for skips)
deleteBySource(source)
deleteAll()
```

### Repository

`RoomHrvReadingRepository` is a thin wrapper over the DAO, adding error logging via the base class. Injected as `HrvReadingRepository` (Hilt singleton).

**Key files:**
- `data/db/HrvReadingEntity.kt`
- `data/db/HrvReadingDao.kt`
- `data/repository/RoomHrvReadingRepository.kt`
- `data/db/Converters.kt` — `Instant ↔ Long`, `DataSource ↔ String`

---

## Stage 4 — Daily Aggregation

`DailySummaryWorker` (a `CoroutineWorker`) runs after each sync session and on demand. For HRV it fetches the day's raw readings and computes four summary values:

| Field | Computation |
|-------|-------------|
| `avgHrvMs` | Arithmetic mean of all `rmssd_ms` values for the day |
| `morningHrvMs` | `rmssd_ms` of the first reading at or after 05:00 local time (basal/resting indicator) |
| `hrvMinMs` | Lowest `rmssd_ms` for the day |
| `hrvMaxMs` | Highest `rmssd_ms` for the day |

These are written into `DailySummaryEntity` (table `daily_summary`, keyed by `LocalDate`) via `DailySummaryRepository.upsert()`.

**Key files:**
- `worker/DailySummaryWorker.kt`
- `data/model/DailySummary.kt`
- `data/db/DailySummaryEntity.kt`

---

## Stage 5 — UI Layer

### History Screen

**Source:** `ui/history/HistoryViewModel.kt`, `ui/history/HistoryScreen.kt`

The History screen lets the user overlay up to 5 metrics on a Vico line chart with selectable time range (7 d / 30 d / 90 d / 1 y) and aggregation regularity (daily / weekly / monthly).

**Data path for HRV:**

1. `fetchSeriesFlow("HRV", endDate, days)` queries `DailySummaryRepository.getSummariesForRange(from, endDate)`.
2. `extractWearableValue("HRV", summary)` returns `summary.morningHrvMs ?: summary.avgHrvMs` — morning HRV is preferred as the more clinically meaningful baseline; average is the fallback when no morning reading exists.
3. Results become `List<ChartEntry>`, aggregated per `Regularity` using `AggregationType.AVG`.
4. Each tile displays the selected date's value as `"XX ms"` (integer-rounded). Tile arrows step through periods; expand shows the full historical data table.

### Daily Detail Screen

**Source:** `ui/dailydetail/DailyDetailViewModel.kt`, `ui/dailydetail/DailyDetailScreen.kt`

The Daily Detail screen shows both summary stats and the full intra-day reading log for a single day.

**Data path for HRV:**

1. `DailySummary` for the day is loaded → mapped into `CardiovascularData(morningHrvMs, avgHrvMs, hrvMinMs, hrvMaxMs)`.
2. Raw `hrv_readings` for the day are loaded via `HrvReadingRepository.getReadingsInRangeOnce()` → each `HrvReadingEntity` becomes a `TimestampedReading(timeLabel: "HH:mm", value: "%.1f".format(rmssdMs), unit: "ms")`.

**UI display:**
- Collapsed `CardiovascularTile`: `"HRV XX ms"` (morning value, integer-rounded).
- Expanded `MetricSubsection "HRV"`:
  - Primary line: `"Morning XX ms"`
  - Secondary: `"Avg: XX ms"`
  - Secondary: `"Range: XX – XX ms"` (min–max)
  - Intra-day line chart (Vico, shown when ≥ 2 readings exist)
  - Collapsible readings table (timestamp + value for every raw reading)

### Metric Detail Screen

**Source:** `ui/metric/MetricDetailViewModel.kt`

Single-metric trend view. Fetches raw `hrv_readings` directly, groups by `LocalDate`, and averages RMSSD per day. Unit displayed: `"ms"`.

### Dashboard

The HRV dashboard widget, when tapped, fires `DashboardNavigationEvent.OpenDailyDetail(date, DailyDetailSection.CARDIOVASCULAR, "HRV")` — navigating to the Daily Detail screen with the Cardiovascular section pre-focused.

---

## Design Decisions

| Decision | Rationale |
|----------|-----------|
| HRV stored as RMSSD only | RMSSD is the standard wearable HRV metric; other algorithms (SDNN, pNN50) can be derived from raw RR intervals if added later via `meta_json` or a separate table |
| `DEDICATED_METRIC_TYPES` bypass | HRV readings are written directly to their typed table without going through the pending-metrics staging map, avoiding double-writes during session flush |
| REPLACE conflict strategy | Allows a re-sync to correct a partial or erroneous earlier reading for the same `(driver_id, recorded_at)` |
| `morningHrvMs` as primary History value | Morning (basal) HRV is a more stable day-to-day training readiness indicator than average; average used only as fallback |
| `computed_by_version` column | Forward-compatibility — allows a future migration to recompute RMSSD from raw RR data if the algorithm changes, without losing provenance of existing records |
