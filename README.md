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
