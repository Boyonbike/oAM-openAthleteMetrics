# Bug Report (Reordered: Severity → Similarity)
Autonomous read-only bug-hunting pass over the OpenAthleteMetrics codebase. Findings only — nothing has been fixed yet.

### Medium Severity

### Stale in-memory snapshot silently overwrites newer data

**`ui/settings/ProfileTab.kt`** — Lines 97-111 (`saveField`), 141-146, 212, 125: every save path does a read-modify-write against a cached `profile` snapshot (a `StateFlow` backed by an async Room `Flow`) rather than the DB. Two edits committed faster than the write→re-emit round trip (e.g. Height then immediately Date of Birth) cause the second save's `.copy()` to silently revert the first field. Not yet fixed. Suggested fix: perform the merge inside the repository/DAO as a partial update, or serialize saves off a single always-current source of truth.

**`ui/questions/DailyQuestionsViewModel.kt`** — Lines 74-86 (`init`) + `buildContext()` (163-181) + `saveQuestions()`/`saveWeight()` (104-116, 124-136): loads today's `DailyContext` **once** at construction rather than continuously collecting, then always writes back **all** fields from the in-memory snapshot. This ViewModel is long-lived (e.g. held by `SettingsScreen`). If the row is modified elsewhere (e.g. `DashboardViewModel`) while this instance stays alive, the next save overwrites those changes back to stale values. Not yet fixed. Suggested fix: re-read/merge the latest row instead of writing back a point-in-time snapshot.

**`ui/metric/MetricDetailScreen.kt`** — Line 72-74: `hiltViewModel(...)` is called with no explicit `key`, so the returned ViewModel is cached solely by class under the current `ViewModelStoreOwner` — the `creationCallback`'s `metricType` is only consulted on first creation. If this screen is shown for one metric, dismissed, then reopened for a different metric while the same Activity is alive, it keeps returning the ViewModel bound to the first metric, silently showing stale data. Currently unreachable since `NavGraph.kt`'s `pendingMetricType` is never assigned — but the mechanism is real. Not yet fixed. Suggested fix: pass `key = metricType.name`.

**`ui/settings/ProfileTab.kt`** — Lines 113-127 (`saveZone`) + 374-408: `editingZone = null` executes unconditionally before validating `lower`/`upper`; a blank/non-numeric field on focus-loss silently closes the editor and discards input with no error. Additionally only the "upper" field has an `onFocusChanged` handler that triggers `saveZone()` — the "lower" field has none, so typing only the Lower value and tapping away never saves. Not yet fixed. Suggested fix: validate and surface an error, and add save-on-blur for the lower field too.

### UI acts on the wrong target / shows duplicates

**`ui/history/HistoryViewModel.kt`** — Line 214-220 (`stepTileDate`), with `HistoryScreen.kt` line 526-528: always operates on `metricTiles.value.first()` regardless of which tile's arrow was pressed — every tile's step callback is wired to the same handler without tile identity. With 2+ overlaid metrics, pressing the second tile's arrow moves according to the *first* tile's spacing/index. Not yet fixed. Suggested fix: thread the tapped tile's `metricKey` through `onStepTile`.

**`ui/overview/WidgetTapDestinationResolver.kt`** — Line 48-55: the `STARRED_LIFESTYLE_BAR` case checks `questionRepo.getResponsesForDate(date).isEmpty()` for *any* response, regardless of category. If the user answered only custom/habit questions, tapping the Lifestyle widget wrongly navigates to the (mostly empty) detail view instead of the questions entry flow. The sibling `CUSTOM_QUESTIONS_BAR` case correctly filters first. Not yet fixed. Suggested fix: check `responses.any { it.questionId in lifestyleIds }`.

**`ui/devices/DevicesScreen.kt`** — Line 306-329: scan results are never filtered against already-saved devices, so an already-paired device advertising nearby appears twice in the "Add Device" grid — once as a saved cell, once as a candidate. Tapping "ADD" on the duplicate re-triggers `connectToDevice` with whatever driver the scan matched, not guaranteed to be the one already saved. Not yet fixed. Suggested fix: filter `discoveredCandidates` against saved `bleAddress`es before rendering.

### BLE engine robustness (sync hangs / resource growth)

**`ble/BleEngine.kt`** — Lines 1146-1156, 957-969: return values of `writeCharacteristic`/`writeDescriptor` are never checked. If either returns `false`, the corresponding callback never fires, and in the plain "immediate advance" branch there's no fallback timeout, so the sync permanently hangs until manual disconnect. Not yet fixed. Suggested fix: check the return value and retry/fail fast, and/or add a generic per-command timeout.

**`ble/BleEngine.kt`** — Lines 390-401 (`handleNotification`): `reassemblyBuffers[characteristicUuid]` grows via `existing + bytes` with no max-size cap or per-characteristic timeout. A misbehaving device sending undersized fragments indefinitely grows this buffer unbounded, risking OOM/crash and a possible throw when handed to WASM's fixed-size linear memory. Not yet fixed. Suggested fix: cap the buffer and drop/reset with a warning if exceeded.

**`ble/BleEngine.kt`** — Line 337 (`disconnect()`), 1294-1296: `activeGatt?.disconnect()` has no timeout waiting for the callback that releases the native GATT client. If the callback never arrives (known on some OEM stacks), the GATT client leaks (Android caps concurrent clients per app) and internal state stays stuck referencing a supposedly-disconnected device. Not yet fixed. Suggested fix: add a bounded timeout that force-calls `closeGatt()` if the callback doesn't arrive.

### Uncaught exception / never-invoked cleanup

**`glance/WidgetConfigActivity.kt`** — Lines 85-99 (`onTemplateSelected`): the launched coroutine has no try/catch around `getGlanceIdBy`, `updateAppWidgetState`, or `MetricGlanceWidget().update(...)`. Any exception here is uncaught (no app-wide `CoroutineExceptionHandler` exists), crashing the process instead of degrading gracefully — defeating the `setResult(RESULT_CANCELED)` set earlier specifically to let the host discard the widget cleanly. Not yet fixed.

**`AthleteDataApplication.kt`** — Lines 94-98: `onTerminate()` calls `bleEngine.shutdown()`/`syncProcessor.shutdown()`, but `Application.onTerminate()` is documented to never be invoked on real devices — only in the emulator. In production, the BLE engine and sync processor never get a chance to shut down gracefully. Latent resource-leak/cleanup gap. Not yet fixed.

### Worker / seeder correctness

**`worker/DailySummaryWorker.kt`** — Lines 72-239: the entire `doWork()` body, including `LocalDate.parse(dateStr)`, is wrapped in one broad catch that unconditionally returns `Result.retry()`. This doesn't distinguish transient failures from permanent ones (malformed date, logic/null-pointer bug, schema mismatch) — a permanent failure retries forever under WorkManager's backoff, silently consuming battery/CPU indefinitely. Not yet fixed. Suggested fix: catch parse/programmer errors separately and return `Result.failure()`.

**`seeder/SeederService.kt`** — Lines 132-186: if an exception is thrown partway through a date's insert sequence, the catch block records the date as failed but doesn't roll back writes already committed. A subsequent run's `alreadySeeded` check sees the partial data and treats the date as fully seeded, permanently skipping repair. Not yet fixed.

### Data-integrity gaps at the DB layer

**`data/db/HrReadingEntity.kt`** — Line 13: the unique index on `["driver_id", "recorded_at"]` is meant to deduplicate readings, but `driver_id` is nullable and SQLite treats every `NULL` as distinct — so `MANUAL`/`SEEDER`-sourced readings (always `driver_id = null`) get **no** duplicate protection. The seeder works around this with an app-level check, but any other manual-entry path without the same guard can accumulate duplicates. Same pattern recurs in `HrvReadingEntity.kt`, `SpO2ReadingEntity.kt`, `RespirationReadingEntity.kt`, `SkinTempReadingEntity.kt`, `StepsReadingEntity.kt`, `BloodPressureReadingEntity.kt`, `GlucoseReadingEntity.kt`, `ActiveCalorieReadingEntity.kt`, `TotalCalorieReadingEntity.kt`, `ActivityEntity.kt`, `SleepSessionEntity.kt`, and `MetricReadingStagingEntity.kt`. Not yet fixed. Suggested fix: enforce a non-null sentinel `driver_id` for manual/seeder rows, or perform an explicit existence check before insert.

### Migration / schema seeding gaps

**`di/DatabaseModule.kt`** — Lines 169-197: migrations start at `MIGRATION_2_3` with no `MIGRATION_1_2` and no `.fallbackToDestructiveMigration()`, despite `AppDatabase.kt`'s schema-history comment confirming version 1 was a real prior schema. Any installed copy still on schema v1 hits Room's "no migration found" exception at database-open time with no recovery path short of clearing app data. Not yet fixed.

**`data/db/AppDatabase.kt`** — No `RoomDatabase.Callback`/`onCreate` hook is registered anywhere, and `widget_layout` is only ever populated inside migrations. A brand-new install that creates the database fresh at version 19 never runs any migration, so `widget_layout` starts empty and the dashboard has zero default widgets until manually added. Not yet fixed. Suggested fix: seed the default layout via `RoomDatabase.Callback.onCreate()` in addition to migration-time reseeding, or check-and-seed at app startup.

### Backup / privacy configuration

**`AndroidManifest.xml`** — Line 50-52: `android:allowBackup="true"` plus unmodified boilerplate `data_extraction_rules.xml`/`backup_rules.xml` mean the entire Room database (HR, HRV, SpO2, blood pressure, glucose, sleep-stage readings) plus DataStore preferences are included in Android's default Auto Backup with no filtering — a privacy-relevant gap for a health-data app that should have deliberate include/exclude rules (or `allowBackup="false"`). Not yet fixed.

## Low Severity

### Unvalidated input causes crash or invalid stored value

**`data/repository/RoomBaselineRepository.kt`** — Lines 40-49 (`recalculate`): the guard `if (values.size < minDays) return` doesn't trigger when a user-configured override resolves `minDays` to `0` (no validation on the interface) and `values` is empty — `values.average()` on an empty list produces `NaN`, which gets persisted as the baseline's mean/stdDev/lower/upper. Currently latent (no UI wires `minimumDays = 0`). Not yet fixed. Suggested fix: clamp/validate `minimumDays >= 1`, or guard `values.isEmpty()` before computing.

**`data/repository/DataStoreSettingsRepository.kt`** — Lines 33-37 (`getThemePreference`): `ThemePreference.valueOf(raw)` throws if the persisted string doesn't match a current enum constant (future rename/removal, or corrupted preference). Running inside `dataStore.data.map { }`, the exception propagates as a flow failure to every collector with no fallback, crashing theme resolution app-wide instead of falling back to `SYSTEM`. Not yet fixed. Suggested fix: `runCatching { ThemePreference.valueOf(raw) }.getOrDefault(ThemePreference.SYSTEM)`.

**`seeder/SeederService.kt`** — Line 215: `rng.nextInt(dates.size - duration - 2) + 2` can throw `IllegalArgumentException` when `dates.size - duration - 2 <= 0`. Currently unreachable since `seedDays` is only ever invoked with `days = 30` or `1`, but fragile against any future caller passing a small day count. Not yet fixed.

### Glance widget minor issues

**`glance/MetricGlanceWidget.kt`** — Lines 151-156, 163-171, 173-182, 325-327: `lastUpdatedText` is derived independently of whether the specific metric's value is actually present. The "Updated {time}" caption shows whenever a summary/context row was recently updated, even if this particular field is still null, implying a refreshed value when there is none. Not yet fixed.

**`glance/MetricGlanceWidget.kt`** — Lines 80-95 (`provideGlance`): no error handling wraps any of the suspend/Flow calls. If any throw, `provideGlance` never reaches `provideContent`, silently freezing the widget on its last-rendered state with no error surfaced or retried. Not yet fixed.

### BLE engine minor issues

**`ble/BleEngine.kt`** — Lines 547-552 vs. `dispatchPostStreamParse()` (1277-1378): `triggerSync()` logs and discards leftover `reassemblyBuffers` content before clearing it, but the EOS-based path never inspects or logs `reassemblyBuffers` at all — any mid-assembly partial fragment at end-of-stream is silently dropped with no diagnostic trace. Not yet fixed. Suggested fix: mirror the discard-and-log handling in `dispatchPostStreamParse()`.

**`ble/BleEngine.kt`** — Line 349 (`acknowledgeSyncComplete()`): when transitioning back to `Connected`, the displayed `packetsReceived` resets to `0`, but the internal `packetCount` field is never reset — the next notification continues incrementing from the old count, so the UI-visible packet counter can jump to a large stale-looking number after an acknowledged sync. Not yet fixed. Suggested fix: reset `packetCount = 0` in `acknowledgeSyncComplete()`.

### NavGraph minor issues

**`ui/nav/NavGraph.kt`** — Line 114: `intent.getStringExtra(WidgetDeepLink.EXTRA_DATE)?.let(LocalDate::parse)` isn't wrapped in a try/catch. A stale or malformed date string (e.g. from an old widget instance after a format change) throws `DateTimeParseException` uncaught inside the `LaunchedEffect`, crashing the composition. Not yet fixed. Suggested fix: `runCatching { LocalDate.parse(it) }.getOrNull() ?: LocalDate.now()`.

**`ui/nav/NavGraph.kt`** — Lines 111-113: when `widgetIntent` is non-null but fails to resolve a known `templateId`, the function returns without calling `onWidgetIntentConsumed()`, leaving the caller's intent state dangling — unlike every other exit path in this effect. Not yet fixed.

### Misc UI state races / edge cases

**`ui/questions/QuestionsViewModel.kt`** — Lines 75-84 (`saveResponse`), 86-94 (`clearResponse`): both read `localDate.value` from inside the `viewModelScope.launch { }` body rather than capturing the date at the moment of user interaction. If the displayed date changes between tap and coroutine execution, the response could be attributed to the wrong date. Low-likelihood given single-threaded scheduling. Not yet fixed. Suggested fix: pass/capture the date explicitly from the caller.

**`ui/components/PillSelector.kt`** — Lines 46-49: `continuousIndex.coerceIn(0f, (tabs.lastIndex).toFloat())` throws `IllegalArgumentException` if `tabs` is ever empty (`lastIndex == -1`). Currently unreachable since every call site passes a non-empty list, but latent in this shared, reusable component. Not yet fixed. Suggested fix: guard with `if (tabs.isEmpty()) return@drawBehind`.

**`ui/dailydetail/DailyDetailViewModel.kt`** — Line 113-121 (`onTileReordered`): `current` is built without sorting by `sortOrder`, then indices from the UI's sorted list are applied directly against this unsorted list. Every writer today happens to keep list-position equal to `sortOrder`, so indices stay aligned in practice, but it's an unenforced invariant — any future write path persisting tiles out of order would desync indices and silently move the wrong tile. Not yet fixed. Suggested fix: `.sortedBy { it.sortOrder }` before the index-based mutation.

**`ui/devices/DevicesViewModel.kt`** — Line 137-139: `onDeviceCellTapped()` only allows reconnecting from `Idle`/`Error`, but `onAddDeviceTapped()` additionally allows `GattCacheError`. Tapping the device tile directly while in `GattCacheError` silently does nothing, even though the banner's "Retry" button (calling `onAddDeviceTapped`) works fine in the same state. Not yet fixed. Suggested fix: include `GattCacheError` in the allowed-states check for `onDeviceCellTapped` too.