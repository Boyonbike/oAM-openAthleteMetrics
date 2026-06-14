# OAM — Open Athlete Metrics

**Your body. Your data. Your understanding.**

OAM is an open source Android app for athletes who want to understand their physiology, not be scored by it. Connect any wearable, keep your data on your device, and use transparent algorithms to spot the patterns that actually explain how you already feel.

---

## Why OAM exists

Most health tracking apps hand you a proprietary score and tell you how recovered you are. OAM doesn't do that.

Athletes don't want to be told how to feel. They want to use metrics to understand *why* they feel as they do, and use thay recognition to identify patterns over time. OAM gives you the raw signals, your own context, and open algorithms so you can draw your own conclusions.

---

## Core principles

**Control over your data** — Everything is stored locally on your device. No account required. No cloud sync. Your biometric data never leaves your phone.

**No proprietary scores** — No readiness score, no recovery number, no arbitrary algorithm built on average population data. OAM surfaces metrics and observed patterns. You decide what they mean.

**Open algorithms** — Every calculation is documented and explained. You can read exactly how OAM derives resting HR, HRV baselines, or any other metric. 

**Bring your own band** — OAM's driver system lets you connect almost any BLE wearable, not just officially supported ones. If your device isn't supported yet, you or anyone in the community can add it.

**No subscriptions. Ever.** — Free to use, free to build on, free to fork.

---

## Supported devices

OAM uses a modular driver system — any BLE wearable can be supported without changes to the app itself.

| Status | Device |
|--------|--------|
| 🔧 In development | Hume Band (reference implementation) |
| 📋 Planned | Whoop Bands 4 & 5 and more! Suggestions welcome|

**Don't see your device?** See [Writing a Driver](#writing-a-driver) below — if your device communicates over BLE, it can work with OAM.

---

## How the driver system works

Drivers are small json files that tell OAM how to talk to a specific device. The app handles all Bluetooth communication — a driver just provides the knowledge the app needs.

1. Download or write a `.json` file for your device
2. Open **Devices →Driver Tab → Add Driver**
3. Then go to the **Devices Tab** and sync your device

Drivers use embedded WASM for data parsing, which means:
- They can be written in any language that compiles to WASM (Rust, Go, C, AssemblyScript, and more)
- They run safely sandboxed inside the app
- Anyone can write one — no changes to OAM itself required

If your device returns raw sensor data, OAM runs it through its own open algorithms. If it returns processed values, OAM uses those directly and documents the difference. We always do our utmost to keep our algorithms in line with the most accurate open source, peer reviewed research. Once algorithms have been implemented you will be able to find suitable documentation and sources in the [`/docs`](/docs) folder.

---

## OAM Metric Algorithms VS Device Algorithms

There are many different devices we aim to support and they all work in slightly different and slightly annoying ways. For example the Hume bands Ble protocol doesn't allow access to the raw sensor data so we are forced to use there proprietary algorithms. Alternatively Whoop bands provide a continuous stream of raw sensor data we have to parse into metrics. 

So what can we do to mitigate the impact of this? 
* All drivers show a popup explaining which metrics are derived by the app and which by the device. 
* Baselines are calculated from the metric rather than the raw data meaning the baseline calculations will be consistent across devices.
* Baseline calculations will use a rolling delta offset to create a smooth transition between devices.
* Device changes will be marked in the database and in the UI to ensure users are aware of the changes to there data.

---

## Getting started

> **Status:** OAM is in active development. Currently backend development is the focus and the UI is just a functional mockup. To complete Phase 1 the UI will be fully redesigned and the ble architecture and database architecture will be fully implemented with complete driver support for devices not requiring metric algorithms. Phase 2 will implement upgraded driver support for devices requiring metrics algorithms as well as implementation of driver algorithms. Phase 3 and onwards will be refinements and quality of life features, analytics and pattern recognition and when necessary system upgrades.

### Build from source

```bash
git clone https://github.com/boyonabike/oam.git
cd oam
./gradlew assembleDebug
```
**Requirements:**
- Android Studio Hedgehog or later
- Android SDK 26+
- Kotlin 1.9+

Install the debug APK on any Android device running API 26 (Android 8.0) or higher.

### Try it with test data

Once installed, go to **Settings → Developer → Seed 90 days of data** to populate the app with realistic athlete data and explore every screen without a connected device.

---

## Writing a driver

The driver authoring guide is in [`/docs`](/docs). It covers:

- Driver file format and required fields
- BLE service/characteristic discovery
- Writing the WASM parsing module
- Testing against a real or simulated device
- Submitting a driver to the community library

If you've reverse-engineered a device and want to contribute a driver, open a pull request against the [`/drivers`](/drivers) directory.

---

## Contributing

OAM is early stage and welcomes contributions across all areas:

- **Drivers** — support for new devices
- **Algorithms** — improvements to metric calculations (documented and cited)
- **Android** — UI, architecture, performance
- **Docs** — clarity, accuracy, translations
- **IOS** — any developers interested in an ios version should get in touch directly. I have considered moving to flutter or Kotlin multiplatform but this is my first app and I dont know how :(

---

## Project status

| Area | Status |
|------|--------|
| Database schema | ✅ Complete |
| Core UI (Dashboard, History, Questions) | 🔧 In progress |
| BLE engine + driver system | 🔧 In Progress — Phase 1&2 |
| Analytics / pattern engine | 📋 Planned — Phase 3 |
| Community driver library | 🔧 Always in development |

---

## Privacy

- All data is stored locally in a SQLite database on your device
- No account, no sign-in, no network requests
- Export your full database at any time from **Settings → Backup**
- Delete everything with a single action in **Settings → Danger Zone**

---

## License

[MIT](/LICENSE) — use it, fork it, build on it.
