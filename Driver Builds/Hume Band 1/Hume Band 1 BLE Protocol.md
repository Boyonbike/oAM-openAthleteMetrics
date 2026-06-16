# Hume Band BLE Protocol - Complete Reference

This document describes the Bluetooth Low Energy (BLE) protocol used by the Hume Band J2208 fitness tracker. Information has been derived through protocol analysis, BLE traffic capture, device testing, interoperability research, and observation of application behaviour. Features are identified as wire-verified, experimentally confirmed, or unverified where applicable.

The goal of this document is to enable interoperable third-party software capable of communicating with compatible devices.

## Device Identity

- **Device name**: Hume Band 434B
- **MAC address**: 09:04:02:08:43:4B (example - yours may differ)
- **Firmware version**: 5.2, build date 2025-05-27
- **BLE transport**: LE 1M

## GATT Services

The device exposes four services. Only the first is needed for health data.

### Primary data service (USE THIS)

- **Service UUID**: 0000fff0-0000-1000-8000-00805f9b34fb
- **Write characteristic** (FFF6): 0000fff6-0000-1000-8000-00805f9b34fb - properties: R W WNR
- **Notify characteristic** (FFF7): 0000fff7-0000-1000-8000-00805f9b34fb - properties: N
    - CCCD descriptor: 0x2902

### Other services (DO NOT WRITE TO)

- 00006287-3c17-d293-8e48-14fe2e4da212 - almost certainly Nordic DFU (firmware update). Writing to it could brick the device.
- 0000d0ff-3c17-d293-8e48-14fe2e4da212 - device metadata (readable characteristics ffd1-ffd5, ffd8, fff1-fff3, ffe0). Safe to read, purpose mostly unknown.
- 0000190e-0000-1000-8000-00805f9b34fb - unknown purpose. Leave alone.

## Connection Sequence

No bonding or authentication required. The device responds to any connection freely.

### Step 1 - Connect

connectGatt(context, false, callback, TRANSPORT_LE)

### Step 2 - Discover services

gatt.discoverServices()

### Step 3 - Enable notifications on FFF7

gatt.setCharacteristicNotification(fff7_characteristic, true)  
writeDescriptor(cccd_descriptor, ENABLE_NOTIFICATION_VALUE)

### Step 4 - Wait for descriptor write callback

onDescriptorWrite fires when notifications are active. This is the signal that the connection is fully ready. Set isConnected = true here.

### Step 5 - Send SetDeviceTime (0x01)

Must be sent on every connection. See command format below.

### Step 6 - Send SetPersonalInfo (0x02)

Must be sent on every connection after SetDeviceTime.

### Device is now ready for data requests

## Command Format

All commands written to FFF6 are **16 bytes**:

- \[0\] = opcode byte
- \[1..14\] = payload (zeros if unused)
- \[15\] = CRC = sum(bytes\[0..14\]) & 0xFF

def crc(payload):  
buf = (list(payload) + \[0\] \* 16)\[:16\]  
buf\[15\] = sum(buf\[:15\]) & 0xFF  
return bytes(buf)

### Timestamp encoding (BCD)

Timestamps in commands use BCD encoding: decimal integer → BCD byte.

- getTimeValue(int n) = Integer.parseInt(str(n), 16)
- Example: year 2026 → last two digits 26 → Integer.parseInt("26", 16) = 0x26 = 38 decimal
- Python: int(str(n % 100), 16) for year, int(str(n), 16) for month/day/hour/minute/second

### Timezone byte

- Positive offset: offset_hours + 128
- Negative offset: abs(offset_hours)
- UTC: 128 (0x80)
- BST (UTC+1): 129 (0x81)

## Command Reference

### 0x01 - SetDeviceTime

\[0x01, bcd(year%100), bcd(month), bcd(day), bcd(hour), bcd(min), bcd(sec), 0x00, tz_byte, 0,0,0,0,0,0, crc\]

Response: 01-F4-00-00-... (F4 is a status byte, meaning unclear but non-fatal)

### 0x02 - SetPersonalInfo

\[0x02, sex, age, height_cm, weight_kg, stride_cm, 0,0,0,0,0,0,0,0,0, crc\]

- sex: 0=female, 1=male
- stride_cm default: 70 Response: 02-00-00-00-... (all zeros = success)

### 0x13 - GetBatteryLevel

\[0x13, 0,0,0,0,0,0,0,0,0,0,0,0,0,0, 0x13\]

Response: 13-\[battery%\]-00-\[unk\]-\[unk\]-\[charging\]-00-...

- \[1\] = battery percentage (0-100), confirmed wire-verified
- \[5\] = charging flag (0=not charging, 1=charging)

### 0x27 - GetDeviceVersion

\[0x27, 0,0,0,0,0,0,0,0,0,0,0,0,0,0, 0x27\]

Response: 27-00-00-\[v1\]-\[v2\]-\[build_yy\]-\[build_mm\]-\[build_dd\]-...

- \[3\].\[4\] = firmware version (e.g. 5.2)
- \[5..7\] = BCD build date yy-mm-dd

### 0x04 - GetDeviceInfo

\[0x04, 0,0,0,0,0,0,0,0,0,0,0,0,0,0, 0x04\]

Response fields:

- \[1\] = distance unit (0=km, 1=miles)
- \[2\] = time unit (0=24h, 1=12h)
- \[3\] = wrist-on display
- \[4\] = temperature unit (0=°C, 1=°F)
- \[5\] = night mode
- \[9\] = base heart rate
- \[11\] = screen brightness (0-5)
- \[12\] = dial face index
- \[14\] = language (0=English, 1=Chinese)

Note: returned all zeros on tested firmware - may not be implemented.

### 0x09 - RealTimeStep (live activity mode)

Enable: \[0x09, 0x01, 0x01, 0,0,0,0,0,0,0,0,0,0,0,0, crc\] Disable: \[0x09, 0x00, 0x00, 0,0,0,0,0,0,0,0,0,0,0,0, crc\]

When enabled, device pushes live updates (opcode 0x09 response) containing steps, calories, distance, HR, temperature. Disable before disconnecting.

## Data Request Format

All history requests use mode byte at \[1\]:

- 0x00 = start (fetch from given date)
- 0x02 = continue (next page, send when dataCount == 50)
- 0x99 (−103 signed) = delete data

Date filter at \[4..9\] = BCD yy-mm-dd-hh-mm-ss using getTimeValue(). If no date filter needed (fetch all), leave as zeros - CRC still valid.

### Pagination

Each data type accumulates records. When end == true (last byte of final notification = 0xFF), deliver results. If dataCount == 50 before end, send ModeContinue (0x02) command to get next page.

## Data Opcodes

### 0x52 - Intraday Activity (STEPS SOURCE)

**Command**: \[0x52, 0x00, 0,0, bcd_date×6, 0,0,0,0,0, crc\] **Response**: 25-byte records, terminated with 52-FF

Record layout (25 bytes):

\[0\] = 0x52 opcode  
\[1..2\] = index LE16  
\[3..8\] = BCD timestamp yy-mm-dd-hh-mm-ss  
\[9..10\] = total steps LE16 for this interval  
\[11..12\]= calories LE16 ÷ 100 = kcal (float)  
\[13..14\]= distance LE16 ÷ 100 = km (float)  
\[15..24\]= 10 per-minute step counts (1 byte each)

**This is the authoritative step source.** Sum steps across all records for a given date to get daily total. The app uses this, not 0x51.

Wire example confirmed:

52-00-00-26-06-05-15-49-09-37-00-C2-00-03-00-28-0F-00-00-00-00-00-00-00-00-52-FF  
→ idx=0, ts=2026-06-05 15:49:09, steps=55, cal=1.94kcal, dist=0.03km  
per-minute: \[0,40,15,0,0,0,0,0,0,0\] (sum=55 ✓)

### 0x53 - Sleep History

**Command**: \[0x53, 0x00, 0,0, bcd_date×6, 0,0,0,0,0, crc\] **Response**: 130-byte notifications, terminated with 53-FF on final packet

Record layout (130 bytes per notification = one sleep session):

\[0\] = 0x53 opcode  
\[1..2\] = index LE16  
\[3..8\] = BCD session start timestamp yy-mm-dd-hh-mm-ss  
\[9\] = stage count (number of valid stage bytes that follow)  
\[10..(10+count-1)\] = stage bytes (1 byte per minute)  
\[10+count..129\] = zero padding

Stage values:

- 1 = wake
- 2 = light sleep
- 3 = deep sleep
- 5 = REM

**Note**: SleepItemData.Status constants in the Java source are mislabelled. Wire values are ground truth: 1=wake, 2=light, 3=deep, 5=REM.

Session grouping: sessions with gap < 3600 seconds between end of one and start of next belong to the same sleep period. Sessions with gap ≥ 3600 seconds are separate sleep periods.

Wire examples confirmed across 11 sessions:

53-00-00-26-06-06-06-55-59-52-1E-02-02... (82 stages, mostly light)  
53-03-00-26-06-06-01-37-01-78-01-01-01... (120 stages, wake→deep→light)

### 0x55 - Heart Rate History (static/spot readings)

**Command**: \[0x55, 0x00, 0,0, bcd_date×6, 0,0,0,0,0, crc\] **Response**: 10-byte records packed into notifications, terminated with 55-FF

Record layout (10 bytes):

\[0\] = 0x55 opcode  
\[1..2\] = index LE16  
\[3..8\] = BCD timestamp yy-mm-dd-hh-mm-ss  
\[9\] = bpm (0-255)

Multiple records packed per notification. Terminal: 55-FF at end.

Wire examples confirmed:

55-00-00-26-06-05-16-13-30-59 → 2026-06-05 16:13:30, 89 bpm  
55-01-00-26-06-05-15-53-30-4C → 2026-06-05 15:53:30, 76 bpm

### 0x56 - HRV Sessions

**Command**: \[0x56, 0x00, 0,0, bcd_date×6, 0,0,0,0,0, crc\] **Response**: 15-byte records, terminated with 56-FF

Record layout (15 bytes):

\[0\] = 0x56 opcode  
\[1..2\] = index LE16  
\[3..8\] = BCD timestamp yy-mm-dd-hh-mm-ss  
\[9\] = HRV RMSSD (ms)  
\[10\] = vascular aging score  
\[11\] = heart rate (bpm) during session  
\[12\] = stress score  
\[13\] = systolic BP (high)  
\[14\] = diastolic BP (low)

The app averages RMSSD across all sessions for its displayed HRV figure.

Wire example confirmed:

56-00-00-26-06-06-08-17-07-2C-38-5A-38-72-40-56-FF  
→ ts=2026-06-06 08:17:07, rmssd=44, vascular_age=56, hr=90, stress=56, highBP=114, lowBP=64

### 0x65 - Temperature History

**Command**: \[0x65, 0x00, 0,0, bcd_date×6, 0,0,0,0,0, crc\] **Response**: 11-byte records, terminated with 65-FF

Record layout (11 bytes):

\[0\] = 0x65 opcode  
\[1..2\] = index LE16  
\[3..8\] = BCD timestamp yy-mm-dd-hh-mm-ss  
\[9..10\] = temperature LE16, value × 0.1 = °C

Wire examples confirmed:

65-00-00-26-06-05-10-00-00-6A-01-65-FF → \[9..10\]=0x6A,0x01=362 → 36.2°C  
65-00-00-26-06-06-08-00-00-6B-01-65-FF → \[9..10\]=0x6B,0x01=363 → 36.3°C

The TemperatureData model has tempBody and tempSkin fields but both are set to the same value - the device sends only one temperature reading.

### 0x66 - SpO2 History (manual measurements)

**Command**: \[0x66, 0x00, 0,0, bcd_date×6, 0,0,0,0,0, crc\] **Response**: 10-byte records, terminated with 66-FF

Record layout (10 bytes):

\[0\] = 0x66 opcode  
\[1..2\] = index LE16  
\[3..8\] = BCD timestamp yy-mm-dd-hh-mm-ss  
\[9\] = SpO2 percentage (direct value, 0-100)

Wire examples confirmed:

66-00-00-26-06-05-15-50-28-61-66-FF → ts=2026-06-05 15:50:28, SpO2=97%  
66-00-00-26-06-06-08-10-24-62-66-FF → ts=2026-06-06 08:10:24, SpO2=98%

## Response Parsing

### BCD timestamp reading

def parse_bcd_ts(data, off=3):  
return f"20{data\[off\]:02X}-{data\[off+1\]:02X}-{data\[off+2\]:02X} {data\[off+3\]:02X}:{data\[off+4\]:02X}:{data\[off+5\]:02X}"

Reads bytes directly as hex strings - each byte stores its value in BCD (e.g. 0x26 = "26" = year 2026).

### LE16 reading

import struct  
def le16(data, off): return struct.unpack_from('<H', data, off)\[0\]  
def le32(data, off): return struct.unpack_from('<I', data, off)\[0\]

### End-of-stream detection

Final notification of any data type ends with \[opcode, 0xFF\]. E.g. 55-FF, 56-FF, 53-FF.

### getValue() - Java's manual LE reconstruction

getValue(byte b, int position) = (b & 0xFF) \* Math.pow(256, position)

This is just little-endian byte reconstruction. getValue(b0,0) + getValue(b1,1) + getValue(b2,2) + getValue(b3,3) = LE32.

## Unsolicited Device Messages

The device pushes opcode 0x16 spontaneously approximately 20 seconds after sync completes:

16-08-00-00-00-00-00-00-00-00-00-00-00-00-00-1E  
16-08-01-00-00-00-00-00-00-00-00-00-00-00-00-1F

- \[1\]=0x08 = device button/interaction event (subtype 8 = unknown, possibly keep-alive)
- \[2\] increments each packet
- \[15\] = valid CRC

**Your notification handler must swallow these gracefully** and not treat them as data responses. Filter by opcode: if data\[0\] == 0x16 ignore or log separately.

## Data Models

### HeartRateItem

data class HeartRateItem(  
val heartRate: Int, // bpm  
val time: Long // unix timestamp seconds  
)

### StepData (from 0x52 intraday records)

data class StepData(  
val calories: Float, // kcal  
val distance: Float, // km  
val steps: Int, // sum of per-minute array  
val time: Long // unix timestamp seconds  
)

### HRVData

data class HRVData(  
val hrv: Int, // RMSSD ms  
val stress: Int, // stress score  
val time: Long, // unix timestamp seconds  
val systolicBP: Int?, // high BP (nullable)  
val diastolicBP: Int? // low BP (nullable)  
)  
// Note: heart rate at wire \[11\] is parsed but not stored in this model  
// Store it yourself if you want it

### OxygenData

data class OxygenData(  
val oxygen: Int, // SpO2 %  
val time: Long // unix timestamp seconds  
)

### TemperatureData

data class TemperatureData(  
val tempBody: Float, // °C (same value as tempSkin)  
val tempSkin: Float, // °C (same value as tempBody)  
val time: Long // unix timestamp seconds  
)

### SleepItemData

data class SleepItemData(  
val startTime: Long, // unix timestamp seconds  
val endTime: Long, // unix timestamp seconds  
val status: Int // 1=wake, 2=light, 3=deep, 4=REM  
)  
// Note: Java constants SLEEP_STATUS_DEEP=1 and SLEEP_STATUS_SOBER=3 are mislabelled  
// Wire truth: 1=wake, 2=light, 3=deep, 5=REM → mapped to model 1/2/3/4

### SleepData (aggregated from SleepItemData)

data class SleepData(  
val wakeDurationSeconds: Int,  
val lightDurationSeconds: Int,  
val deepDurationSeconds: Int,  
val remDurationSeconds: Int,  
val items: List&lt;SleepItemData&gt;,  
val startTime: Long  
)

Sleep session grouping: collect all SleepItemData sorted by startTime, split into sessions wherever gap between consecutive items ≥ 3600 seconds.

## SetPersonalInfo Values

Send on every connect with actual user values:

sex: 0=female, 1=male  
age: integer years  
height: integer cm  
weight: integer kg  
stride: integer cm (default 70, affects distance calculation)

The device uses these for calorie and distance calculations in real-time mode.

## Notification Types - Full Opcode Dispatch

When a notification arrives on FFF7, dispatch on data\[0\]:

| **data\[0\]** | **Source**                | **Action**                                   |
| ------------- | ------------------------- | -------------------------------------------- |
| 0x01          | SetDeviceTime ack         | Log, ignore                                  |
| 0x02          | SetPersonalInfo ack       | Log, ignore                                  |
| 0x04          | GetDeviceInfo response    | Parse device settings                        |
| 0x09          | Real-time activity update | Parse live steps/HR/temp                     |
| 0x13          | Battery response          | \[1\]=%, \[5\]=charging                      |
| 0x16          | Device push event         | **Ignore/swallow**                           |
| 0x27          | Firmware version          | \[3\].\[4\]=version, \[5..7\]=build date BCD |
| 0x52          | Intraday activity record  | Accumulate, deliver on end                   |
| 0x53          | Sleep session             | Accumulate, deliver on end                   |
| 0x55          | HR spot reading           | Accumulate, deliver on end                   |
| 0x56          | HRV session               | Accumulate, deliver on end                   |
| 0x65          | Temperature reading       | Accumulate, deliver on end                   |
| 0x66          | SpO2 reading              | Accumulate, deliver on end                   |
| anything else | Unknown                   | Log raw bytes                                |

## Additional Opcodes (Confirmed Exist, Not Fully Mapped)

From DeviceConst.java - these opcodes exist on the device but have not been wire-verified for this app:

| **Opcode** | **Name**            | **Purpose**                                                      |
| ---------- | ------------------- | ---------------------------------------------------------------- |
| 0x0B       | SetStepGoal         | Write daily step goal                                            |
| 0x13       | GetBatteryLevel     | ✓ confirmed above                                                |
| 0x15       | SetWeather          | Push weather to watch face                                       |
| 0x19       | StartExercise       | Begin workout session                                            |
| 0x28       | MeasurementWithType | Trigger on-demand HR/SpO2/HRV measurement                        |
| 0x34       | GPSControlCommand   | GPS on/off                                                       |
| 0x43       | GetAutomatic        | Read auto-monitoring schedule                                    |
| 0x44       | GetSportMode        | Read sport mode settings                                         |
| 0x47       | StartOTA            | **DO NOT USE** - triggers firmware update                        |
| 0x49       | Get3D               | Raw accelerometer streaming                                      |
| 0x4B       | GetStepGoal         | Read configured step goal                                        |
| 0x4D       | SetNotify           | Push phone notification to watch                                 |
| 0x54       | GetDynamicHR        | Continuous HR during activity (24-byte records, 15 samples each) |
| 0x57       | GetAlarmClock       | Read alarm clock settings                                        |
| 0x5C       | GetSportData        | Exercise session history                                         |
| 0x60       | GetBloodOxygen      | Auto background SpO2 history                                     |
| 0x61       | ClearBraceletData   | **Wipes all stored data**                                        |
| 0x62       | GetTempHistory      | Alternative temperature history stream                           |
| 0x71       | GetECGWaveform      | ECG waveform history                                             |

## Exercise Mode Constants

From ExerciseMode.java:

0=Run, 1=Cycling, 2=Badminton, 3=Football, 4=Tennis,  
5=Yoga, 6=Breath, 7=Dance, 8=Basketball, 9=Walk,  
10=Workout, 11=Cricket, 12=Hiking, 13=Aerobics,  
14=PingPong, 15=RopeJump, 16=Situps, 17=Volleyball

Exercise status: 1=Start, 2=Pause, 3=Continue, 4=Finish

## Notification Push Types

From Notifier.java - notification types for SetNotify (0x4D):

0=Phone call, 1=SMS, 2=WeChat, 3=Facebook, 4=Instagram,  
5=Skype, 6=Telegram, 7=Twitter, 8=VK, 9=WhatsApp,  
10=QQ, 11=LinkedIn, 255=Stop call

## Connection State Machine

DISCONNECTED  
→ connectGatt()  
CONNECTING  
→ onConnectionStateChange(CONNECTED)  
→ discoverServices()  
→ onServicesDiscovered()  
→ setCharacteristicNotification(FFF7, true)  
→ writeDescriptor(CCCD, ENABLE_NOTIFICATION_VALUE)  
→ onDescriptorWrite() ← isConnected = true  
CONNECTED_READY  
→ send SetDeviceTime (0x01)  
→ send SetPersonalInfo (0x02)  
→ enqueue data requests  
SYNCING  
→ send each data request (0x52, 0x53, 0x55, 0x56, 0x65, 0x66)  
→ accumulate records until end==true or dataCount==50  
→ if dataCount==50 and not end: send ModeContinue (0x02)  
→ deliver results via callback  
SYNC_COMPLETE  
→ disconnect or stay connected for real-time mode  
DISCONNECTED (error 133 or state 0)  
→ clear state  
→ if NeedReconnect: wait 1s, restart scan

## Error Handling

**GATT error 133**: Common Android BLE error. Clear gatt, set isConnected=false, retry after 1 second delay.

**Write timeout (5000ms)**: If writeCharacteristic takes longer than 5 seconds, force disconnect and reconnect. Clear pending write queue.

**onCharacteristicWrite failure**: writeCharacteristic() returned false. Force disconnect and reconnect. Save commands for retry.

**Connection interval changes**: Device negotiates power-saving intervals (300ms) after initial burst. This is normal - do not treat as disconnect.

## Important Notes

- **0x51 (GetTotalActivityData) is not used by the app** for its displayed step count. The app sums 0x52 intraday records per date. 0x51 exists and responds but the values don't match the app's displayed totals. Use 0x52 exclusively for steps.
- **Sync order matters**: The Java app sends requests sequentially, one at a time, waiting for end==true before starting the next. Do not send multiple requests concurrently.
- **Date filtering**: Commands accept a start date filter at \[4..9\] in BCD. If left as zeros, device returns all stored history. The history depth varies by data type - typically 7-30 days.
- **Record packing**: Multiple records can arrive in a single BLE notification (e.g. 0x55 HR packs multiple 10-byte records). Parse by dividing notification length by record size.
- **SetDeviceTime response F4**: The 0x01 ack returns F4 at \[1\]. This is non-fatal - data requests work correctly regardless.
- **Real-time mode must be disabled**: If you enable RealTimeStep (0x09), always send the disable command before disconnecting or the watch may continue trying to push data.
- **stepLength affects device calculations**: The stride length sent in SetPersonalInfo affects distance and calorie estimates calculated on-device. Default is 70cm.

## Complete Sync Sequence (Pseudocode)

fun fullSync(startDateUnixTs: Long) {  
val startDateFormatted = formatUnixTimestamp(startDateUnixTs)  
// formatUnixTimestamp returns "yyyy.MM.dd HH:mm:ss" converted to BCD  
<br/>// Requests are sequential - each waits for end==true before next begins  
fetchHeartRate(startDate) // sends GetStaticHRWithMode(0x00, date) → 0x55  
fetchSteps(startDate) // sends GetDetailActivityDataWithMode(0x00, date) → 0x52  
fetchSleep(startDate) // sends GetDetailSleepDataWithMode(0x00, date) → 0x53  
fetchSpO2(startDate) // sends Obtain_manual_spo2(0x00, date) → 0x66  
fetchHRV(startDate) // sends GetHRVDataWithMode(0x00, date) → 0x56  
fetchTemperature(startDate) // sends GetAxillaryTemp(0x00, date) → 0x65  
}  
<br/>fun buildSetTimeCommand(): ByteArray {  
val now = Calendar.getInstance()  
val year = now.get(Calendar.YEAR) % 100 // last 2 digits  
val tz = (TimeZone.default.rawOffset / 3600000) + 128  
return crc(byteArrayOf(  
0x01,  
bcd(year), bcd(now.month+1), bcd(now.day),  
bcd(now.hour), bcd(now.minute), bcd(now.second),  
0x00, tz.toByte()  
))  
}  
<br/>fun buildSetPersonalInfoCommand(sex: Int, age: Int, height: Int, weight: Int, stride: Int = 70): ByteArray {  
return crc(byteArrayOf(0x02, sex.toByte(), age.toByte(), height.toByte(), weight.toByte(), stride.toByte()))  
}

## Source Provenance

All information derived from:

- Decompiled APK: BleService.java, BleManager.java, HumeBandAdapter.java, BleSDK.java, ResolveUtil.java, DeviceConst.java, BleConst.java, DeviceKey.java, and all model classes
- Live nRF Connect wire captures: two full sync sessions (2026-06-05 and 2026-06-06)
- Manual probe scripts: battery (0x13) confirmed, steps (0x52) confirmed, firmware version (0x27) confirmed

Wire-confirmed opcodes: 0x01, 0x02, 0x13, 0x27, 0x52, 0x53, 0x55, 0x56, 0x65, 0x66 Java-source-only (not wire verified for this app): 0x04, 0x09, 0x51, 0x54, 0x5C, 0x60, 0x65-alt