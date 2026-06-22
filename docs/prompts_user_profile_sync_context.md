# Athlete Data App — User Profile & SyncContext Implementation Prompts

Work through these prompts in order. One prompt = one Claude Code session.
Paste both the Master Plan and MVP Build Spec documents at the start of each session.

---

## Prompt 1 — User Profile entity, DAO, and repository

```
CONTEXT
I am building a local-first athlete performance tracking Android app.
Package: com.athletedata.app
Stack: Kotlin, Jetpack Compose, MVVM, Room, Hilt, Coroutines, Flow.
Paste both the Master Plan and MVP Build Spec documents before this prompt.

TASK — User profile database layer

Create the following. Do not copy this as-is — use it as a specification only.

1. UserProfileEntity (Room @Entity, tableName = "user_profile")
   Single-row table enforced by hardcoded primary key id = 1.
   Columns:
   - id: Int, @PrimaryKey, default 1
   - name: String?
   - date_of_birth: String? (ISO date YYYY-MM-DD)
   - biological_sex: String? (values: MALE, FEMALE, OTHER)
   - height_cm: Double?
   - weight_kg: Double? (auto-synced from daily weight log, do not expose for direct edit)
   - stride_length_cm: Double?
   - wrist_circumference_mm: Double?
   - resting_metabolic_rate: Double?
   - vo2_max: Double?
   - max_hr: Int?
   - hr_zones_json: String? (JSON array of HrZone objects)
   - updated_at: Long (epoch ms, default System.currentTimeMillis())

2. HrZone domain model (plain data class, not a Room entity)
   Fields: zone: Int (1–5), lowerBpm: Int, upperBpm: Int

3. UserProfile domain model (plain data class, not a Room entity)
   Mirrors UserProfileEntity but with hrZones: List<HrZone> instead of hrZonesJson: String?
   Include a toEntity() extension and a UserProfileEntity.toDomain() extension for conversion.
   Use Gson or kotlinx.serialization (whichever is already in the project) for HrZone list serialisation.

4. UserProfileDao
   Methods:
   - observe(): Flow<UserProfileEntity?> — SELECT WHERE id = 1
   - get(): suspend fun returning UserProfileEntity?
   - upsert(entity: UserProfileEntity): suspend fun, OnConflict.REPLACE
   - updateWeight(weightKg: Double, updatedAt: Long): suspend fun
     UPDATE SET weight_kg, updated_at WHERE id = 1

5. UserProfileRepository interface
   Methods:
   - observe(): Flow<UserProfile?>
   - get(): suspend fun returning UserProfile?
   - upsert(profile: UserProfile): suspend fun
   - updateWeight(weightKg: Double): suspend fun

6. RoomUserProfileRepository implementing UserProfileRepository
   Injected via Hilt. Handles entity<->domain conversion internally.

7. Add UserProfileDao to AppDatabase.kt alongside existing DAOs.

8. Add UserProfileDao provision and UserProfileRepository binding to the existing Hilt DatabaseModule.

Show all files in full. Add a one-line KDoc comment to every public function explaining what calls it.
```

---

## Prompt 2 — Auto-sync weight to user profile

```
CONTEXT
I am building a local-first athlete performance tracking Android app.
Package: com.athletedata.app
Stack: Kotlin, Jetpack Compose, MVVM, Room, Hilt, Coroutines, Flow.
Paste both the Master Plan and MVP Build Spec documents before this prompt.

The project now has a UserProfileRepository with an updateWeight(weightKg: Double) suspend function.
RoomDailyContextRepository handles all writes to the daily_context table.

TASK — Auto-sync daily weight to user profile

Modify RoomDailyContextRepository so that whenever upsert(context: DailyContext) is called
and context.weightKg is non-null, it also calls userProfileRepository.updateWeight(context.weightKg)
immediately after the Room write succeeds.

Requirements:
- Inject UserProfileRepository into RoomDailyContextRepository via Hilt constructor injection.
- The weight sync must happen in the same coroutine scope as the upsert, on Dispatchers.IO.
- If weightKg is null, do not call updateWeight — do not blank an existing profile weight.
- If updateWeight throws, log the error but do not propagate it — a failed weight sync
  must never cause the daily context save to fail.

Show the full updated RoomDailyContextRepository file and any Hilt module changes needed.
Add a comment at the sync call site explaining why it is conditional on non-null.
```

---

## Prompt 3 — SyncContext data class and assembly

```
CONTEXT
I am building a local-first athlete performance tracking Android app.
Package: com.athletedata.app
Stack: Kotlin, Jetpack Compose, MVVM, Room, Hilt, Coroutines, Flow.

The project has:
- UserProfileRepository with a suspend get(): UserProfile? method
- UserProfile domain model with fields: name, dateOfBirth, biologicalSex, heightCm,
  weightKg, strideLengthCm, wristCircumferenceMm, restingMetabolicRate, vo2Max,
  maxHr, hrZones: List<HrZone>
- HrZone data class: zone: Int, lowerBpm: Int, upperBpm: Int

TASK — SyncContext data class and factory

1. Create SyncContext data class in com.athletedata.app.ble:
   Fields:
   - epochMs: Long
   - utcOffsetMinutes: Int
   - isoDateTime: String (format: "2026-06-21T14:32:00", local time)
   - name: String?
   - dateOfBirth: String?
   - biologicalSex: String?
   - heightCm: Double?
   - weightKg: Double?
   - strideLengthCm: Double?
   - wristCircumferenceMm: Double?
   - restingMetabolicRate: Double?
   - vo2Max: Double?
   - maxHr: Int?
   - hrZones: List<HrZone>

2. Create SyncContextFactory in com.athletedata.app.ble:
   Injected via Hilt. Depends on UserProfileRepository.
   Single method: suspend fun build(): SyncContext
   - Reads UserProfile via userProfileRepository.get()
   - Captures current instant via System.currentTimeMillis()
   - Computes utcOffsetMinutes from ZoneId.systemDefault() at the current instant
   - Formats isoDateTime in local time using DateTimeFormatter.ISO_LOCAL_DATE_TIME
   - Maps all UserProfile fields into SyncContext, using empty list for hrZones if null

3. Create SyncContextSerializer in com.athletedata.app.ble:
   Single method: fun toJson(context: SyncContext): String
   Serialises SyncContext to a JSON string the WASM runtime can parse.
   HrZone list serialises as: [{"zone":1,"lowerBpm":100,"upperBpm":130}, ...]
   Use Gson or kotlinx.serialization, whichever is already in the project.

Show all three files in full.
```

---

## Prompt 4 — WasmDriverEngine updated to receive SyncContext

```
CONTEXT
I am building a local-first athlete performance tracking Android app.
Package: com.athletedata.app
Stack: Kotlin, Jetpack Compose, MVVM, Room, Hilt, Coroutines, Flow.

The project has:
- WasmDriverEngine: handles loading the WASM module from a driver manifest and calling
  WASM exports (parseMetrics, parseSleep, parseActivity)
- SyncContext data class (see Prompt 3 for fields)
- SyncContextSerializer with toJson(context: SyncContext): String
- The driver manifest has an exports block:
  { "parseMetrics": "...", "parseSleep": "...", "parseActivity": null, "buildSyncCommands": "buildSyncCommands" }
  buildSyncCommands is optional — not all drivers export it.

TASK — Add buildSyncCommands support to WasmDriverEngine

Modify WasmDriverEngine to support an optional buildSyncCommands WASM export.

1. Add method:
   suspend fun buildSyncCommands(context: SyncContext): List<SyncCommand.Write>
   - Serialises context to JSON via SyncContextSerializer.toJson()
   - Checks if the WASM module exports the function named in manifest.exports.buildSyncCommands
   - If the export is absent or null, returns emptyList() immediately
   - If present, calls the WASM export passing the JSON string
   - The WASM function returns a JSON string: array of { "characteristic": "write", "bytes": "0x01 0x02" }
   - Deserialises the return value into List<SyncCommand.Write>
   - If deserialisation fails, logs the error and returns emptyList() — never throws to caller

2. The existing parseMetrics, parseSleep, parseActivity methods are unchanged.

Show the full updated WasmDriverEngine file.
Add a comment explaining the fallback behaviour when buildSyncCommands is not exported.
```

---

## Prompt 5 — BleEngine updated to assemble and pass SyncContext

```
CONTEXT
I am building a local-first athlete performance tracking Android app.
Package: com.athletedata.app
Stack: Kotlin, Jetpack Compose, MVVM, Room, Hilt, Coroutines, Flow.

The project has:
- BleEngine: manages the full BLE lifecycle. After connection and service discovery,
  it calls buildEffectiveSyncCommands() on WasmDriverEngine to get the command list,
  then executes them via executeNextSyncCommand() (which calls gatt.writeCharacteristic()
  and waits for onCharacteristicWrite before continuing).
- SyncContextFactory with suspend fun build(): SyncContext
- WasmDriverEngine.buildSyncCommands(context: SyncContext): List<SyncCommand.Write>
- The driver manifest has an optional syncRequirements block:
  {
    "datetime": true,
    "userProfile": ["weight_kg", "height_cm", "biological_sex", "stride_length_cm", "hr_zones"]
  }
  Both fields are optional — a driver with no syncRequirements block needs no context.

TASK — Wire SyncContext into BleEngine connect flow

1. Inject SyncContextFactory into BleEngine via Hilt constructor injection.

2. In the connect flow, after service discovery completes and before sync commands are executed:
   - Read manifest.syncRequirements
   - If syncRequirements is absent or both datetime and userProfile are empty/false,
     pass an empty/default SyncContext to WasmDriverEngine (do not call SyncContextFactory)
   - If syncRequirements declares any fields, call syncContextFactory.build() to get
     the full SyncContext
   - Pass the SyncContext to WasmDriverEngine.buildSyncCommands()
   - Append the returned List<SyncCommand.Write> to the end of the static sync command list
     resolved from the manifest
   - Execute the combined list via the existing executeNextSyncCommand() loop

3. If syncContextFactory.build() throws (e.g. database unavailable), log the error,
   continue with static commands only, and do not abort the connection.

Show the full updated BleEngine file focusing on the connect flow and injection changes.
Mark every changed section with a // CHANGED comment so it is easy to diff.
```

---

## Prompt 6 — Profile tab in Settings

```
CONTEXT
I am building a local-first athlete performance tracking Android app.
Package: com.athletedata.app
Stack: Kotlin, Jetpack Compose, MVVM, Room, Hilt, Coroutines, Flow.
Global design language: data-first, minimal decoration, 8dp grid, single accent blue,
no gradients, no coaching language, no scores.

The project has:
- UserProfileRepository: observe(): Flow<UserProfile?>, upsert(profile: UserProfile)
- UserProfile domain model fields:
  name, dateOfBirth (YYYY-MM-DD), biologicalSex (MALE/FEMALE/OTHER),
  heightCm, weightKg (read-only — auto-synced, not user-editable here),
  strideLengthCm, wristCircumferenceMm, restingMetabolicRate, vo2Max, maxHr,
  hrZones: List<HrZone> where HrZone = { zone: Int, lowerBpm: Int, upperBpm: Int }
- Settings screen already exists with Theme and Danger Zone sections.
  It uses a segmented tab or section pattern consistent with the rest of the app.
- WeightEntryBottomSheet already exists and is used on the Dashboard.
  It accepts weight in kg, optional body fat %, and optional notes.
  On save it calls DailyContextRepository.upsert() for today's date, which
  auto-syncs the weight to UserProfileRepository.updateWeight().

TASK — Add Profile tab to Settings

1. Add a Profile tab to the Settings screen alongside the existing content.
   If Settings does not currently use tabs, introduce a segmented tab control:
   [ Profile | App ]
   where App contains the existing Theme, Backup, and Danger Zone sections.

2. Profile tab layout — single scrollable column, sections:

   Section: Identity
   - Name (text)
   - Date of Birth (date — opens DatePickerDialog on tap)
   - Biological Sex (inline selector: Male / Female / Other)

   Section: Body Metrics
   - Height (cm, numeric)
   - Stride Length (cm, numeric)
   - Wrist Circumference (mm, numeric)
   - Resting Metabolic Rate (kcal, numeric)
   - VO2 Max (ml/kg/min, numeric)
   - Max HR (bpm, integer)
   - Weight — shown as read-only with label "Auto-synced from daily log"
     Tapping the weight row opens WeightEntryBottomSheet (reuse as-is, no modifications).
     The row must look tappable (ripple, same affordance as other rows) with a subtitle
     making clear it opens a log sheet rather than an inline editor.
     After the sheet saves, the displayed weight updates automatically via the
     UserProfileRepository Flow — no manual refresh needed.

   Section: HR Zones
   Five rows, one per zone (Zone 1 to Zone 5).
   Each row shows: "Zone N    [lower] - [upper] bpm"
   Tap any row to edit that zone's lower and upper bpm inline.
   Zones are always user-defined — no auto-calculation.

3. Interaction model:
   - Tap any editable field -> field becomes an inline text input, keyboard opens
   - Tapping away or pressing done saves that field immediately via UserProfileRepository.upsert()
   - No save button — each field saves individually on dismiss
   - Biological sex uses a three-option inline chip selector, not a text field
   - Weight row is not an inline editor — tap opens WeightEntryBottomSheet instead

4. WeightEntryBottomSheet scoping:
   - Manage sheet visibility with a local var showWeightSheet by remember { mutableStateOf(false) }
   - Set to true on weight row tap, false on sheet dismiss
   - Scope WeightEntryViewModel to the Settings screen's ViewModelStoreOwner,
     not to the sheet itself, so it is not re-created on open/close

5. Create UserProfileViewModel:
   - Exposes profile: StateFlow<UserProfile?>
   - Has a single updateProfile(updated: UserProfile) function
   - Calls UserProfileRepository.upsert() on every field change

Show all new files and the full updated Settings screen file.
Add a comment at the WeightEntryBottomSheet call site explaining why it is used
here instead of an inline editor.
```

---

## Prompt 7 — Driver Authoring Guide updates

```
CONTEXT
I am maintaining DRIVER_AUTHORING_GUIDE.md for an Android athlete tracking app.
Drivers are JSON manifests with an embedded base64 WASM module.
The current spec version is 2. These changes introduce spec version 3.

The existing guide covers:
- Manifest structure and fields
- BLE matching, services, characteristics
- syncCommands (WRITE, ENABLE_NOTIFY, DELAY)
- Parsing via WASM exports (parseMetrics, parseSleep, parseActivity)
- The exports block

TASK — Add the following new sections to the guide.
Write in the same technical style as the existing document.
Use Markdown with code blocks and tables. No prose filler.

--- SECTION 1: specVersion 3 ---

Add a migration note at the top of the guide:
specVersion "3" adds syncRequirements and the buildSyncCommands WASM export.
Drivers at specVersion "2" continue to work unchanged.
To opt in, set "specVersion": "3" and add the relevant blocks below.

--- SECTION 2: syncRequirements ---

The syncRequirements block declares what data the app must assemble and pass
to the driver at connect time. The app reads this block before initiating the
connection and will warn the user if declared required fields are missing
from their profile.

Document the full schema:

{
  "syncRequirements": {
    "datetime": true,
    "userProfile": ["weight_kg", "height_cm", ...]
  }
}

Valid userProfile field keys and their types:
| Key                    | Type    | Description                                    |
| name                   | string  | User display name                              |
| date_of_birth          | string  | ISO date YYYY-MM-DD                            |
| biological_sex         | string  | MALE, FEMALE, or OTHER                         |
| height_cm              | number  | Height in centimetres                          |
| weight_kg              | number  | Latest synced weight in kg                     |
| stride_length_cm       | number  | Walking/running stride length                  |
| wrist_circumference_mm | number  | Wrist circumference for optical HR calibration |
| resting_metabolic_rate | number  | RMR in kcal/day                                |
| vo2_max                | number  | VO2 max in ml/kg/min                           |
| max_hr                 | integer | Maximum heart rate in bpm                      |
| hr_zones               | array   | List of HrZone: {zone, lowerBpm, upperBpm}     |

Notes:
- Fields not declared in userProfile are not passed to the WASM runtime.
- If datetime is false or omitted, epochMs, utcOffsetMinutes, and isoDateTime
  are not populated in the SyncContext.
- The syncRequirements block is optional. Omitting it is equivalent to
  { "datetime": false, "userProfile": [] }.

--- SECTION 3: buildSyncCommands WASM export ---

Document the optional buildSyncCommands export.

Function signature the WASM module must export:
  buildSyncCommands(contextJsonPtr: i32, contextJsonLen: i32): i32
  Returns a pointer to a JSON string in WASM memory.

The app passes a SyncContext JSON object. Document the full SyncContext schema:
{
  "epochMs": 1750000000000,
  "utcOffsetMinutes": 60,
  "isoDateTime": "2026-06-21T14:32:00",
  "name": "Alex",
  "dateOfBirth": "1990-03-15",
  "biologicalSex": "MALE",
  "heightCm": 178.0,
  "weightKg": 72.4,
  "strideLengthCm": 78.0,
  "wristCircumferenceMm": 165.0,
  "restingMetabolicRate": 1850.0,
  "vo2Max": 52.0,
  "maxHr": 192,
  "hrZones": [
    { "zone": 1, "lowerBpm": 100, "upperBpm": 130 },
    { "zone": 2, "lowerBpm": 131, "upperBpm": 148 },
    { "zone": 3, "lowerBpm": 149, "upperBpm": 163 },
    { "zone": 4, "lowerBpm": 164, "upperBpm": 174 },
    { "zone": 5, "lowerBpm": 175, "upperBpm": 200 }
  ]
}

The return value must be a JSON array of Write commands:
[
  { "characteristic": "write", "bytes": "0x01 0x02 0x03" }
]

Only Write commands may be returned. ENABLE_NOTIFY and DELAY are not
supported in buildSyncCommands return values.

The app appends these commands to the end of the static syncCommands list
and executes them in order via the standard write loop.

If the driver does not need dynamic commands, omit the export entirely
or set it to null. The app handles both cases identically.

Document how to declare the export in the manifest:
"exports": {
  "parseMetrics": "parseMetrics",
  "parseSleep": "parseSleep",
  "parseActivity": null,
  "buildSyncCommands": "buildSyncCommands"
}

--- SECTION 4: Full specVersion 3 manifest example ---

Show a complete minimal manifest at specVersion 3 that:
- Declares datetime and three userProfile fields in syncRequirements
- Exports buildSyncCommands alongside parseMetrics
- Has two static syncCommands (one ENABLE_NOTIFY, one WRITE)
- Has a placeholder wasmBase64 value

--- SECTION 5: Error behaviour ---

Document what the app does when things go wrong:
- Required profile field is missing: app logs a warning, field is null in SyncContext,
  sync proceeds. The app does not block connection for missing profile data.
- buildSyncCommands WASM throws or returns malformed JSON: app logs the error,
  falls back to static syncCommands only, connection continues.
- buildSyncCommands is declared in exports but not found in the WASM binary:
  treated as null — no dynamic commands, no error surfaced to user.

Write all five sections as Markdown ready to append to the existing guide.
Preserve the existing guide's heading level conventions.
```
