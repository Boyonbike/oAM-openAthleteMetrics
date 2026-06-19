# Driver Authoring Guide — Update Plan (Gap 1 & Gap 2)

## What this plan covers

Two new sections to be added to `docs/DRIVER_AUTHORING_GUIDE.md`.
No code changes. Two insertions only (plus a post-edit conflict review pass).

---

## Gap 1 — Sleep date assignment

### Where to insert

After the `**In-progress sleep sessions**` block (line 405),
before `**Activities**` (line 407), inside `### Date Attribution Rules`.

### Content to insert

```markdown
#### Sleep date assignment

The `dateIso` field in `SleepWasmDto` is **ignored by the host**. Do not rely on it.

The host always recomputes the sleep session date from `sleepEndMs` (the wake-up
moment) using the device local timezone. The date filed in the database will be the
local calendar date on which the user woke up, regardless of what `dateIso` contains.

Your driver is responsible only for providing accurate `sleepStartMs` and `sleepEndMs`
values as UTC epoch milliseconds. Use the `utcOffsetMinutes` value from the metadata
block (bytes 8–9) to convert device local-time sleep boundaries to UTC if the device
encodes them in local wall-clock time.

You may populate `dateIso` for your own debugging purposes, but it has no effect on
how the session is stored.
```

### Surrounding context (for placement verification)

**Line before insertion (In-progress sleep sessions, last sentence):**
> and the deduplication key (`driverId + dateIso`) will ensure it inserts cleanly.

**Line after insertion (Activities, first line):**
> **Activities**

---

## Gap 2 — buildSyncCommands time write rules

### Where to insert

After the last WARNING in `#### Canonical Rules for Time Writes` (line 836),
before the `---` separator and `### The 'parsing' Block` (line 838).

### Content to insert

```markdown
#### buildSyncCommands — time write rules (IMPORTANT)

If your driver writes the current time to a device characteristic, follow these rules
without exception. Getting this wrong corrupts the device clock permanently — all
readings the device takes after a wrong time write carry unrecoverable wrong timestamps.

**The metadata block for `buildSyncCommands` is different from the parse metadata block.**

For `buildSyncCommands` calls:
- Bytes 0–7 contain the current UTC epoch milliseconds at the moment the function is
  called — not `syncStartMs`. Do not assume these are the same value.
- Bytes 8–9 contain the current UTC offset in minutes, also captured at call time.

**Rules:**

1. If the device expects UTC epoch seconds:
   ```
   currentTimeS = readI64(memory, 0) / 1000
   ```
   Write `currentTimeS` as i32 or i64 little-endian as the device requires.

2. If the device expects local wall-clock time (BLE DateTime characteristic or
   vendor-packed format):
   ```
   currentUtcMs = readI64(memory, 0)
   utcOffsetMs  = readI16(memory, 8) * 60_000
   localTimeMs  = currentUtcMs + utcOffsetMs
   ```
   Decompose `localTimeMs` into year/month/day/hour/min/sec fields for the device.

3. Never hardcode `ZoneOffset.UTC` or assume UTC = local. A device in India (UTC+5:30)
   receiving a UTC time when it expects local time will have its clock set 5 hours
   30 minutes behind reality. Every reading it takes after this will be wrong.

4. Never use `syncStartMs` (bytes 0–7 of the parse metadata block) in a time write.
   Use only the values provided in the `buildSyncCommands` metadata block, which are
   captured freshly at execution time.
```

### Surrounding context (for placement verification)

**Line before insertion (last WARNING in Canonical Rules):**
> `SleepWasmDto.dateIso` is ignored by the host. Do not rely on it. The host
> recomputes the sleep date from `sleepEndMs` using the device local timezone.

**Line after insertion (section separator):**
> ---
> ### The `parsing` Block

---

## Post-edit: conflicting passages to review manually

After the two insertions are made, the following existing passages will contradict
Gap 1. They are flagged for the author to reword — no automatic edit will be made:

### Conflict A — lines 383–387 (Date Attribution Rules → Sleep sessions)

Current text:
> `dateIso` must be the **UTC calendar date of `sleepEndMs`**. The app's validator
> always normalises `date` to the UTC date of `sleepEndMs` regardless of what you
> provide — supplying any other value will produce a correction warning in the logs
> without affecting storage. To avoid the warning, set `dateIso` to `sleepEndMs`
> formatted as a UTC date string (YYYY-MM-DD in UTC).

Problem: Claims the field is read and warns if wrong; says UTC date when the actual
behaviour uses the device local timezone.

### Conflict B — parseSleep schema table (line 994–995)

Current text (dateIso row, Description column):
> The UTC calendar date of `sleepEndMs`, e.g. `"2024-01-15"`. The app always
> normalises this field to the UTC date of `sleepEndMs` — set it to that value
> to avoid a correction warning.

Problem: Same false claims — field is read, warning fires, UTC not local.

---

## Files changed

| File | Change |
|------|--------|
| `docs/DRIVER_AUTHORING_GUIDE.md` | Two new `####` sections inserted |

No code files. No build required. Verification is a visual read of the two
insertion points and the two conflict passages.