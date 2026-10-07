# Muse Bridge for Hubitat — v1.0.0

**Your Hubitat, talking to your AI assistant.**

Muse Bridge exposes your Hubitat devices, location modes, and Hubitat Safety
Monitor through a local + cloud REST API, and runs an alert-rule engine right
on the hub. It's designed to link with **Muse** (Meta's personal AI assistant):
open the in-app **Link with Muse** page, copy the pre-filled setup message,
paste it into chat, and your assistant verifies the connection and starts
watching your hub.

## Highlights

- **REST API** (local + cloud, OAuth-secured): devices with live attributes,
  any device command, location modes, HSM status and arm/disarm, speech, push
- **Alert rules** on the hub: "stays longer than / clears sooner than" with
  conditions on mode, HSM status, thermostat state, time windows, and weekdays
- **Per-rule notification channels**: spoken announcements, phone push with
  per-phone targeting, and Muse-chat alerts
- **URGENT flag**, announcement volume (app default, per-rule, per-call) with
  automatic restore, and a speaker picker that ranks by capability
- **Device Health**: daily push digest of low batteries and quiet devices,
  with configurable thresholds and per-device ignore lists
- **Command passcode** for security-sensitive actions (locks, garage, valves,
  sirens, modes, HSM) — fails closed until you set one
- **Link with Muse**: per-install cloud URL + token and your monitoring
  preferences, generated as a copy-paste message — no manual config

## Install

1. Paste `apps/muse-bridge.groovy` into **Apps Code** (HPM manifest included
   for package-manager installs).
2. Enable **OAuth** via the ⋮ three-dot menu (top-right of the code editor).
3. Add the app, set a **command passcode**, authorize devices with the master
   list, and open **Link with Muse** to connect your assistant.

Full details in the README.

## Links

- GitHub: https://github.com/rayzurbock/hubitat-muse-bridge
- License: MIT — Copyright (c) 2026 Rayzurbock (Brian S. Lowrance)

*Designed by Rayzurbock, coded by Muse.*

---

If Muse Bridge is useful to you, donations help cover development and the AI
service costs behind it: [Cash.me/$Lowrance](https://cash.me/$Lowrance) ·
[Venmo @BrianLowrance](https://venmo.com/code?user_id=2603208862072832399) ·
[PayPal.me/brianlowrance](https://paypal.me/brianlowrance)
