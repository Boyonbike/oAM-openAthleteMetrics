# UI Navigation and Screen Reference

## 1. Navigation Model

### Pages

The app has four pages defined by the `Page` enum in `ui/nav/NavGraph.kt`:

| Enum value | Label | Bottom nav icon |
|---|---|---|
| `DASHBOARD` | Dashboard | Home (outlined) |
| `DAILY_DETAIL` | Daily Detail | Article (outlined) |
| `QUESTIONS` | Questions | Checklist (outlined) |
| `HISTORY` | History | BarChart (outlined) |

All four pages are composed simultaneously inside a `Box` in `AppNavGraph`. The active page is shown at `alpha=1f, zIndex=1f`; inactive pages are at `alpha=0f, zIndex=0f`. This preserves scroll position and `remember` values across navigation events.

### Stack Model

```kotlin
var currentPage by remember { mutableStateOf(Page.DASHBOARD) }
val backStack = remember { mutableStateListOf<Page>(Page.DASHBOARD) }
```

`navigateTo(page)` pushes onto the stack (no-op if page is already at the top). Back press pops the stack and sets `currentPage` to the new top. Initial stack: `[DASHBOARD]`.

### Settings and Devices

Settings and Devices are not pages — they are full-screen modal composables layered above the page stack via `if (showSettings)` / `if (showDevices)` guards. When either is active, the bottom nav bar hides itself and no page indicator is lit.

Opening Settings or Devices does NOT push to `backStack`. Back press dismisses the overlay and re-shows the bottom nav.

### Bottom Navigation Bar

`BottomNavBar` in `ui/nav/BottomNavBar.kt`. Pill-shaped surface floating 16dp above the system navigation bar, with 24dp horizontal margin each side.

```
┌──┬───────────────────────────────────┬──┐
│ ⚙│  🏠     📄     ✓     📊         │ 🔵│
│  │  Dash   DailyD  Quest  History   │   │
└──┴───────────────────────────────────┴──┘
 ↑                                      ↑
 Settings end cap (64dp)            Devices end cap (64dp)
```

Active tab is indicated by an animated pill background (`primaryContainer` colour, spring animation with `DampingRatioMediumBouncy`). The pill width and centre-X both animate independently.

**Scroll behaviour:** `BottomNavScrollBehavior` auto-hides the bar when `scrollDelta < -2f` and re-shows when `scrollDelta > 2f`. A `NestedScrollConnection` is wired via `rememberBottomNavNestedScrollConnection()` and provided as `LocalBottomNavScrollBehavior`. Calling `scrollBehavior.show()` forces the bar visible (done on any navigation action).

**Devices button states:**

| State | Icon | Extra |
|---|---|---|
| Disconnected | Bluetooth (outlined) | — |
| Connected / SyncComplete | BluetoothConnected (filled) | battery % in 9sp |
| Syncing | Sync (rotating 360°/1 s) | battery % |

Long-press on Devices button triggers sync if not already syncing.

### Cross-Page Navigation with Parameters

Dashboard can navigate to other pages with parameters, passing them via `pending*` state variables in `AppNavGraph`. The target page consumes them on first composition via `LaunchedEffect(initial*)`:

| Source widget | Target | Parameters passed |
|---|---|---|
| Metric card, Weight, Activities | Daily Detail | `initialDate`, `initialSection`, `initialMetricKey` |
| Lifestyle bar (no data) | Questions | `initialDate` |
| Habits bar (no data) | Questions | `initialDate`, `initialTab = "HABITS"` |

---

## 2. Date System

### Per-Page Date Independence

There is no global date state. Each page's ViewModel owns its own `MutableStateFlow<LocalDate>`, initialized to `LocalDate.now()` at process start:

```
AppNavGraph
├── DashboardViewModel._date                  → LocalDate.now()
├── DailyDetailViewModel._localDate           → LocalDate.now()
├── QuestionsViewModel._localDate             → LocalDate.now()
└── HistoryViewModel._pageDate + _tileDate    → HistorySessionState.date
```

Pages do not read each other's date. When Dashboard navigates to Daily Detail, it passes the current date as a one-time `initialDate: String?` parameter. Daily Detail applies it once via `LaunchedEffect(initialDate)` then manages its own cursor independently.

### State Diagram

```
App launch
    │
    ▼
 Each ViewModel initialises date = LocalDate.now()
 HistoryViewModel reads from HistorySessionState.date (also now() on first run)

User navigates Dashboard → Daily Detail:
    Dashboard passes initialDate = dashboard._date.toString()
    DailyDetailViewModel.setDate(initialDate) called once on composition
    After that, DailyDetail._localDate is independent

User navigates Dashboard → Questions (via Lifestyle/Habits tap):
    Dashboard passes initialDate = dashboard._date.toString()
    QuestionsViewModel.setDate(initialDate) called once
    After that, Questions._localDate is independent

History._pageDate persists to HistorySessionState on every setPageDate() call.
History._tileDate does NOT persist (in-memory only).
HistorySessionState is a @Singleton — survives page navigation but NOT process kill.
```

### Date Navigation — All Pages

Every data page uses `DataPageTopBar` (`ui/components/TopBar.kt`) and the `horizontalDateSwipe` modifier.

**Top bar date button:** displays current date formatted as `"d MMM yyyy"` (e.g. "5 Jun 2026"). Tap opens `DataPageDatePickerDialog`.

**`DataPageDatePickerDialog`:** Material3 `DatePickerDialog`. Future dates are non-selectable (`isSelectableDate` returns false for any date after today). OK confirms selection; Cancel dismisses without state change.

**`horizontalDateSwipe` modifier:** Attached to the `Scaffold` root so the full screen is a swipe target.

| Gesture | Condition | Effect |
|---|---|---|
| Swipe left (dx < 0) | `abs(dx) > 25dp` AND `abs(dx) > abs(dy) × 5` | `onDayForward()` → date + 1 day |
| Swipe right (dx > 0) | Same thresholds | `onDayBack()` → date − 1 day |

Forward navigation is clamped to today: `it.plusDays(1).coerceAtMost(LocalDate.now())`.

**Swipe disabled when:** `isEditMode == true` (Dashboard, Daily Detail, Questions).

### History Date Cursors

History has two independent date state values:

| StateFlow | Changed by | Purpose |
|---|---|---|
| `_pageDate` | TopBar date picker | Right edge of the graph window |
| `_tileDate` | Chart scrubber drag; tile arrows | Selected period shown in tile cards |

`setPageDate()` also writes to `HistorySessionState.date`. `setTileDate()` does not persist.

---

## 3. Dashboard

**Source:** `ui/overview/DashboardScreen.kt`, `DashboardViewModel.kt`

### Layout

```
┌────────────────────────────────────────┐
│ [d MMM yyyy]   Dashboard   [Add] [✓/✎] │  ← DataPageTopBar
│────────────────────────────────────────│
│ [Demo data banner — full width]         │  ← conditional
│                                        │
│ [Metric SMALL]  [Metric SMALL]         │
│ [Metric SMALL]  [Metric SMALL]         │
│ [Metric SMALL]  [Metric SMALL]         │
│ [Weight WIDE — full width]             │
│ [Lifestyle WIDE — full width]          │
│ [Habits WIDE — full width]             │
│ [Activities WIDE — full width]         │
│                                        │
│ ←── 80dp bottom spacer ──→             │
└────────────────────────────────────────┘
                    ↑ BottomNavBar
```

Container: `LazyVerticalGrid`, `GridCells.Fixed(2)`, 12dp horizontal and vertical spacing, 16dp horizontal padding.

SMALL widgets span 1 column; WIDE widgets span 2 (`GridItemSpan(2)`).

### Seeder Data Banner

Shown when `hasSeederData == true`. `hasSeederData` is determined by `hrReadingRepo.hasSeederDataForDate(currentDate)` — true if any HR readings with `source=SEEDER` exist for the displayed date.

Text: `"Demo data — clear via Settings › Developer › Clear seeder data"`  
Container color: `tertiaryContainer`

### Metric Cards (SMALL size)

All six metric types (Hr, Hrv, Rhr, Sleep, Spo2, Steps):

```
┌──────────────────┐
│ Heart Rate        │   ← TypographyTitle, onSurfaceVariant
│ 72  bpm           │   ← value (TypographyValue) + unit (TypographyMeta), baseline-aligned
│ ↑ 5.2%           │   ← trend (TypographyMeta), always 1 line even if empty
│ ─────────────────│   ← WidgetSparkline, 36dp height
└──────────────────┘
```

WIDE size: value+trend in a left column, sparkline at 45% width on the right, 60dp height.

**Data sources per metric:**

| Widget | Value field | Unit |
|---|---|---|
| Hr | `DailySummary.avgHrBpm` | bpm |
| Hrv | `DailySummary.morningHrvMs` | ms |
| Rhr | `DailySummary.restingHrBpm` | bpm |
| Sleep | `DailySummary.sleepMinutes` formatted as `Xhr Ymin` | — |
| Spo2 | `DailySummary.avgSpo2Pct` | % |
| Steps | `DailySummary.steps` | steps |

**Sparkline:** Vico `CartesianChartHost` line series. Data range is `[date.minusDays(6) … date]` (7 days). Rendered only when `sparkline.size >= 2`; otherwise a `Spacer(36dp)` fills the slot.

**Trend indicator:** `(today - yesterday) / yesterday × 100`. Arrow logic: `> 1%` → `↑`, `< -1%` → `↓`, otherwise `→`. Displayed as `"↑ 5.2%"`. Null if either day has no data or yesterday is 0.

### Starred Lifestyle Preview Widget (WIDE)

Shows starred lifestyle questions (those with `isStarred = true` in the DB). Each item is a column: value or `--` on top, truncated name below. Responds to tap by navigating (see widget tap table below).

Empty state: `"Star lifestyle questions to show them here"` (tappable).

### Habits Preview Widget (WIDE)

Shows all custom (HABITS) questions. Display per type:

| Type | Value present | Value absent |
|---|---|---|
| SCALE | `"n/5"` | `"--"` |
| BOOLEAN | `"Yes"` or `"No"` | `"--"` |
| TEXT | truncated text (max 2 lines) | `"--"` |

TEXT type with a non-null value shows a dialog with the full text on tap (instead of navigating).

Empty state: `"No habits yet — add them in the Habits tab"` (tappable).

### Widget Tap Behaviour

| Widget tapped | Condition | Navigation |
|---|---|---|
| Hr, Hrv, Rhr, Spo2 | — | Daily Detail → CARDIOVASCULAR section |
| Sleep | — | Daily Detail → SLEEP section |
| Steps | — | Daily Detail → ACTIVITY section |
| Activities | — | Daily Detail → ACTIVITIES section |
| Weight | `weightKg != null` | Daily Detail → BODY section |
| Weight | `weightKg == null` | Weight bottom sheet |
| Lifestyle bar | any question responses exist | Daily Detail → QUESTIONS section |
| Lifestyle bar | no responses | Questions page (Lifestyle tab) |
| Habits bar | any habit responses exist | Daily Detail → QUESTIONS section |
| Habits bar | no habit responses | Questions page (Habits tab) |

### Edit Mode

Toggled by the Edit/Check icon in the top bar.

- All widgets enter a wiggle animation: −2° to +2°, 120 ms period, continuous.
- Drag-to-reorder via Calvin `rememberReorderableLazyGridState`. Order persisted to DB on `onDragStopped` via `widgetLayoutRepo.reorderWidgets()`.
- Remove button (X overlay) calls `viewModel.removeWidget(id)`.
- `+ Add` icon appears in the top bar → opens `WidgetCatalogueSheet`.
- Horizontal date swipe is disabled while edit mode is active.

### Weight Bottom Sheet

Fields: Weight (kg) — required, number keyboard; Body fat % — optional; Notes — optional, multi-line. Save button enabled only when weight field parses as a valid `Double`. Pre-fills from existing `DailyContext` for the current date.

---

## 4. Questions Page

**Source:** `ui/questions/QuestionsScreen.kt`, `QuestionsViewModel.kt`

### Layout

Top bar: `DataPageTopBar` with `PillSelector("Lifestyle", "Habits")` in the centre slot. Edit/Check icon on the right; + Add icon appears in edit mode when on the Habits tab.

Switching tabs exits edit mode automatically.

### Lifestyle Tab

`LazyColumn`, 8dp spacing, 16dp horizontal padding, 80dp bottom padding.

Shows only `isVisible == true` items in normal mode. In edit mode, shows all items; hidden items rendered at `alpha = 0.4f`.

**Item card layout:**

```
┌───────────────────────────────────────┐
│ Question Name             [👁] [⠿]   │  ← edit mode only
│                                       │
│ [1]  [2]  [3]  [4]  [5]             │  ← scale chips (normal mode only)
└───────────────────────────────────────┘
```

Scale chips are `FilterChip`. Selected chip: `primaryContainer` background, `onPrimary` text. Tapping the currently-selected chip calls `clearResponse()` (deselects). Response stored as the integer string `"1"` through `"5"`.

**Default Lifestyle questions (from seed data):**

| Order | Name | Starred |
|---|---|---|
| 1 | Fatigue | ✓ |
| 2 | Stress | ✓ |
| 3 | Motivation | ✓ |
| 4 | Sleep Quality | ✓ |
| 5 | Performance Feel | ✓ |
| 6 | Energy | — |
| 7 | Focus | — |
| 8 | Muscle Soreness | — |
| 9 | Mental Clarity | — |

Edit mode controls: eye/visibility icon (toggles `isVisible`), drag indicator (reorder within Lifestyle category only).

Hide is non-destructive: the question persists and can be made visible again. There is no delete option for Lifestyle questions.

### Habits Tab

Same `LazyColumn` structure. Shows only visible items in normal mode; all items (hidden at 40% alpha) in edit mode.

**Input types per question type:**

| Type | Control | Storage |
|---|---|---|
| SCALE | 5 FilterChips (1–5); tap selected to deselect | `"1"` – `"5"` |
| BOOLEAN | Two FilterChips: "Yes" and "No"; tap selected to deselect | `"1"` (Yes) or `"0"` (No) |
| TEXT | `OutlinedTextField`, single line, `ImeAction.Done`; saves on Done action | raw string |

Edit mode controls: delete icon (red, `deleteCustomQuestion(id)` — permanent), edit icon (opens HabitSheet to rename/retype), eye icon, drag indicator.

### Add/Edit Habit — HabitSheet

Bottom sheet (`skipPartiallyExpanded = true`).

Fields:
- Habit name — `OutlinedTextField`, required (Save disabled when blank)
- Type — three `FilterChip` options: Toggle (BOOLEAN), Scale (SCALE), Text (TEXT)

Create: opens with blank name, SCALE default. Save calls `addCustomQuestion(name, type)`.  
Edit: pre-fills existing name and type. Save calls `editCustomQuestion(id, name, type)`. Sheet closes but edit mode remains active.

---

## 5. History Page

**Source:** `ui/history/HistoryScreen.kt`, `HistoryViewModel.kt`

### Layout

```
┌────────────────────────────────────────┐
│ [d MMM yyyy]    History         [+]    │  ← DataPageTopBar; + disabled when 5 metrics
│────────────────────────────────────────│
│                                        │
│  [  Chart — 220dp height             ] │
│                                        │
│  [Duration ▼]        [Regularity ▼]   │
│                                        │
│  [Metric Tile 1 ─────────────────────]│
│  [Metric Tile 2 ─────────────────────]│
│  ...                                   │
└────────────────────────────────────────┘
```

Scrollable `Column` (vertical scroll, nested scroll for bottom nav).

### Chart

Vico `CartesianChartHost`, 220dp tall, full width. Up to 5 line series, each coloured from `SeriesColors[0..4]`.

Axes: `VerticalAxis.rememberStart` (Y), `HorizontalAxis.rememberBottom` (X). X-axis shows up to 5 labels distributed across the visible range. Label format depends on regularity (see below).

**Zoom / scroll:**
- Scroll enabled, initial scroll to `Scroll.Absolute.End` (most recent data).
- Zoom disabled; initial zoom shows `rangeToggle.days` worth of periods.
- Auto-pan: when `tileDate` falls outside the visible window, chart animates to centre the selected period.

**Scrubber:** Canvas overlay drawn on top of the chart. Detects drag gestures; shows a 1.5dp vertical line at the drag fraction. On `onDragEnd`, snaps `tileDate` to the nearest `AggregatedPoint.periodStart`.

### Duration and Regularity Controls

Two `ExposedDropdownMenuBox` read-only fields side by side.

| Control | Options |
|---|---|
| Duration | 7d (7 days), 30d (30 days), 90d (90 days), 1y (365 days) |
| Regularity | Daily, Weekly, Monthly; Hourly listed but disabled |

Changing duration updates `_rangeToggle` (affects initial zoom level). Changing regularity re-aggregates all series.

### Metric Tiles

One `SelectedValueTile` card per metric key. All tiles share the same `tileDate` cursor.

**Tile header row:**

```
● MetricName         "5 Jun"   ←   →   ∨   ✕
```

- Colour dot: 10dp circle, `SeriesColors[index]`
- Name: `TypographyTitle`, `onSurfaceVariant`, single line
- Date label: formatted per regularity (see below)
- Back arrow `←`: disabled when `canStepBack == false` (at start of data)
- Forward arrow `→`: disabled when `canStepForward == false` (at end of data)
- Expand toggle (ExpandMore rotates 180° when expanded)
- Close X: calls `removeMetric(key)`

**Large value:** `TypographyValue` style. Format:

| Unit / type | Format |
|---|---|
| bpm, ms | `"72 bpm"`, `"45 ms"` |
| % | `"98%"` |
| steps | `"12,345 steps"` |
| sleep (hm) | `"7hr 30min"` or `"8hr"` |
| BOOLEAN question | `"Yes"` / `"No"` (threshold 0.5) |
| SCALE question | `"4/5"` |
| No data | `"--"` |

**Expanded data table (AnimatedVisibility):** All `AggregatedPoint` entries listed. Selected period row has `primaryContainer` background. Each row: `point.label` left, formatted value right.

**Date label formats:**

| Regularity | Tile date label | X-axis chart label |
|---|---|---|
| Daily | `"5 Jun"` | `"5 Jun"` |
| Weekly | `"5–11 Jun"` (or `"30 Jun–6 Jul"` across months) | `"W/C 5 Jun"` |
| Monthly | `"Jun 2026"` | `"Jun 2026"` |

### Multi-Metric Overlay

`+` button in the top bar; disabled when 5 metrics are already active.

Opens `OverlayBottomSheet` with groups:

| Section header | Contents |
|---|---|
| METRICS | Wearable keys: HR, HRV, RHR, SPO2, STEPS, SLEEP, WEIGHT |
| LIFESTYLE | Visible lifestyle questions not already on chart |
| CUSTOM | Visible custom habits not already on chart |

Tapping an item calls `addMetric(key)` and closes the sheet.

Metric keys: wearables use string identifiers (`"HR"`, `"HRV"`, etc.); questions use `"q:{id}"`.

### Aggregation

| Key | Method |
|---|---|
| STEPS, SLEEP | SUM across all entries in the period |
| WEIGHT | SINGLE (last value in period) |
| All others (HR, HRV, RHR, SPO2, all questions) | AVG |

### Session State

`HistorySessionState` (`@Singleton`) holds `metricKey: String?` and `date: LocalDate`. It survives in-process navigation (e.g. leaving History and returning) but is reset on process kill.

`pageDate` is written to `sessionState.date` on every `setPageDate()` call. `tileDate` and the metric key list are in-memory only; they are not written back to session state during normal use (only `setTarget()` writes the key).

---

## 6. Daily Detail Page

**Source:** `ui/dailydetail/DailyDetailScreen.kt`, `DailyDetailViewModel.kt`

### Access Paths

| Path | How |
|---|---|
| Bottom nav "Daily Detail" tab | Direct navigation, no parameters |
| Dashboard metric card tap | Navigation with `initialDate`, `initialSection`, `initialMetricKey` |

### Date Behaviour

`DailyDetailViewModel` owns its own `_localDate: MutableStateFlow<LocalDate>`, initialized to `LocalDate.now()`. When accessed via Dashboard tap, the passed `initialDate` is applied once on first composition. After that, the date moves independently via the top bar date picker and horizontal swipe.

### Layout

```
┌────────────────────────────────────────┐
│ [d MMM yyyy]  Daily Detail    [✓/✎]   │  ← DataPageTopBar
│────────────────────────────────────────│
│                                        │
│  [Tile: Cardiovascular  ∨]             │
│  [Tile: Sleep           ∨]             │
│  [Tile: Activity        ∨]             │
│  [Tile: Body            ∨]             │
│  [Tile: Check-in        ∨]             │
│  [Tile: Activities      ∨]             │
│                                        │
└────────────────────────────────────────┘
```

`LazyColumn`, 12dp vertical spacing, 16dp horizontal padding, 88dp bottom padding.

The Activities tile is hidden in normal mode when `state.activities.isEmpty()`. It appears in edit mode regardless.

When navigated with `initialSection`, the list scrolls to that tile and expands it.

### Edit Mode

Toggled via the Edit/Check icon. In edit mode, all tiles are replaced with `EditModeTile` rows showing drag indicator + name + eye icon. Order and visibility are persisted via `settingsRepository.setDailyDetailTileConfig()`.

### Tile Structure

Each tile is a `Card` with a header row (title + animated chevron). Tap header to expand/collapse.

**Collapsed:** one-line summary text.  
**Expanded:** full detail content.

### Cardiovascular Tile

Collapsed summary: `"HR 72 bpm · RHR 52 bpm · HRV 45 ms · SpO₂ 98.0%"` (only present values joined by ` · `).

Expanded subsections:

**Heart Rate** (if `avgHrBpm` present):
- Value line: `"Avg 72 bpm"`
- Extra line: `"Resting: 52 bpm"` (if `restingHrBpm` present)
- Intra-day readings chart (if ≥ 2 readings)
- Collapsible readings table: timestamp | value bpm

**Resting Heart Rate** (only shown alone if `avgHrBpm` is null):
- Value line: `"52 bpm"`
- Readings as above

**HRV** (if `morningHrvMs` present):
- Value line: `"Morning 45 ms"`
- Extra lines: `"Avg: 42 ms"`, `"Range: 38 – 54 ms"` (when available)
- Intra-day readings chart + table

**SpO₂** (if `avgSpo2Pct` present):
- Value line: `"Avg 98.0%"`
- Extra line: `"Range: 96.0 – 99.5%"` (when available)
- Intra-day readings chart + table

### Sleep Tile

Collapsed summary: `"7h30m · D 1h20m L 3h10m R 2h00m"` (stage initials + durations when available).

Expanded:
- **Sleep Duration** subsection: formatted duration
- **Sleep Stages** section (if any stage data): `"Deep: 1h20m"`, `"Light: 3h10m"`, `"REM: 2h00m"`, `"Awake: 10m"`
- **Hypnogram** (if `hypnogramSegments` non-empty): Canvas bar chart, proportional time segments

Hypnogram colours:

| Stage | Colour |
|---|---|
| DEEP | `#1A237E` (dark navy) |
| LIGHT | `#5C6BC0` (medium blue) |
| REM | `#9C27B0` (purple) |
| AWAKE | `#B0BEC5` (grey) |

Legend row below the hypnogram.

### Activity Tile

Collapsed summary: `"12,345 steps · 320 kcal active · 45m exercise"` (available values only).

Expanded:
- **Steps** subsection: step count + `"X min exercise"` extra line; intra-day chart + table
- **Active Calories** subsection: `"320 kcal"`; intra-day chart + table

### Body Tile

Collapsed summary: `"74.5 kg · 18.2% fat · 16.0 brpm"`.

Expanded:
- **Weight** subsection: `"74.5 kg"` + `"Body fat: 18.2%"` extra line
- **Breathing Rate** subsection: `"16.0 brpm"`; intra-day chart + table
- **Total Calories** subsection: `"2850 kcal"`; intra-day chart + table

### Check-in Tile

Title in UI: **"Check-in"**

Collapsed summary: illness flag and/or `"Stress 3/5 · Motivation 4/5"`.

Expanded:
- **Illness** (if `isIll`): red `"Illness noted"` + notes text
- **Subjective Scores** (if `contextScores != null`): two-column rows for Fatigue, Stress, Motivation, Sleep Quality, Performance Feel — each `"n/5"`
- **Lifestyle** group: name | value rows for each answered lifestyle question
- **Habits** group: name | value rows for each answered habit question

### Activities Tile

Collapsed summary: `"1 activity"` / `"N activities"`.

Expanded: one `ActivityCard` per activity. Card contains:
- Device name (raw label from device) + category chip or `"Uncategorised"` text
- Duration formatted
- `"Avg HR: 142 bpm"` (if available)
- Notes (if present)

Tapping an uncategorised activity card, or the edit icon on a categorised one, opens `CategorySheet` to assign Training / Life / Race and add notes.

### Intra-Day Readings

Each `MetricSubsection` shows:
1. A Vico line chart (rendered only when `readings.size >= 2`), 120dp height
2. A collapsible readings table: `"reading(s)"` label + expand chevron → `LazyColumn` (max 200dp) of `timeLabel | value unit` rows

---

## 7. Settings Screen

**Source:** `ui/settings/SettingsScreen.kt`, `SettingsViewModel.kt`

Settings is a full-screen modal overlay (not a nav page). Access via the Settings end cap in the bottom nav bar.

Top bar: `TopAppBar` with back arrow (`←`) + "Settings" title + horizontal divider.

### Sections

```
Settings
    ├── Appearance
    ├── Backup
    └── Danger zone

Experimental
    (no tiles currently)

Developer  ← DEBUG builds only
    └── Seeder
```

All tiles use `SettingsCategoryTile` — a collapsible card with title, collapsed summary, and expanded content.

### Appearance Tile

Collapsed summary: current theme name.

Expanded: three `FilterChip` buttons in a row — Light, Dark, System. Selection applies immediately via `settingsViewModel.setTheme()` → stored in DataStore.

### Backup Tile

Collapsed summary: `"Export or import your database"`.

Expanded:
- **Export database** button → `ActivityResultContracts.CreateDocument` → filename `"athlete_data_export_YYYY-MM-DD.db"` → WAL checkpoint + file copy
- **Import database** button → `ActivityResultContracts.OpenDocument` accepts `"*/*"` → confirmation dialog → replace database

Import confirmation dialog: title `"Replace database?"`, body warns permanent replacement. Buttons: Replace (confirms) | Cancel.

Both buttons disabled when `isBusy == true`.

### Danger Zone Tile

Title rendered in `MaterialTheme.colorScheme.error`.

Collapsed summary: `"Reset all app data"`.

Expanded: single `"Reset database"` button (error-coloured outline).

**Two-step confirmation flow:**

**Step 1 — ResetStep.Confirm:**  
`AlertDialog("Reset all data?")` — warns all data will be deleted permanently.  
Buttons: Continue (→ Step 2) | Cancel (dismiss).

**Step 2 — ResetStep.TypeDelete:**  
`AlertDialog("Type DELETE to confirm")` — `OutlinedTextField` with placeholder `"Type DELETE"`.  
`"Delete everything"` button enabled only when `typedText.trim() == "DELETE"`.  
Confirming executes the reset, then emits `NavigateToDashboard` effect.

### Experimental Section

Section header present; no tiles.

### Developer Section (DEBUG builds only)

Shown only when `BuildConfig.DEBUG == true`.

**Seeder Tile** (`SeederTile` / `SeederViewModel`):

Collapsed summary: state label.

Expanded actions:
- `"Seed 30 days of data"` — fills 30 days ending today
- `"Seed today only"` — fills today only
- `"Clear seeder data"` — deletes seeder-sourced data (error-coloured)

All buttons disabled during `SeederState.Running`.

States:

| State | Summary label | Expanded indicator |
|---|---|---|
| Idle | `"Idle"` | — |
| Running | `"N% complete"` | `LinearProgressIndicator` + `"N%"` text |
| Done | `"Done"` | `"Done"` text + Dismiss button |
| PartialSuccess | `"Partial: N day(s) failed"` | message + Dismiss |
| Error | `"Error: message"` | error-coloured text + Dismiss |
