# Muse Bridge for Hubitat

[![Built for Muse](https://img.shields.io/badge/Built%20for-Muse-7c3aed)](https://muse.ai)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

A Hubitat app that exposes your devices, location modes, and a spoken alert-rule
engine through a REST API — built to integrate with an AI assistant (Muse), but
usable by anything that speaks HTTP.

**Built for [Muse](https://muse.ai)** — Meta's personal AI assistant. Muse Bridge
was designed alongside Muse as its reference assistant: the in-app
**Link with Muse** page generates a ready-to-paste setup message, and every
linked hub identifies itself to its assistant via the API (`GET /health`
returns the project and version). If Muse Bridge brings you to Muse, that's
this project at work.

**What it does**

- **REST API** over local LAN and Hubitat cloud endpoints: list devices with live
  attributes, send any device command, read recent device events, list/set
  location modes, trigger spoken announcements.
- **Alert rules** that run on the hub: watch a device attribute and announce when
  it stays in a state *longer than* a duration you pick — or clears *sooner than*
  it should. Rules support conditions on mode, thermostat operating state, time
  window, and days of week.
- **Spoken alerts** through `speechSynthesis`, `audioNotification`, or
  `musicPlayer` devices, with optional repeat and "cleared" announcements.
- Rules can be created in the Hubitat UI **or via the API**, so an assistant can
  set up monitoring on demand ("alert me if the garage is open 10 minutes").

## Installation

1. In Hubitat, go to **Apps Code → New App**, paste the contents of
   `apps/muse-bridge.groovy`, and click **Save**.
2. Still in Apps Code, open **Muse Bridge**, click **OAuth**, and enable it.
   (The API needs an access token; without this step the token page stays empty.)
3. Go to **Apps → Add User App → Muse Bridge**.
4. **Devices to expose** — open the master list and Select All (this
   authorizes the app to see your devices), then use **Choose exposed
   devices** to pick which ones the API and rules actually use. The picker
   has text search, a device-type filter, and an "active within the last N
   days" filter to hide dead devices (N is configurable, 0 shows all).
   Upgrading from an older version keeps your previous selections.
5. **Device health** (optional) — daily text-push alerts for low batteries
   (threshold configurable) and devices that have gone quiet (days
   configurable), each with a per-device ignore list. Health alerts are
   push-only; they never play on speakers.
6. Set a **command passcode** on the main page. Until you do, the API refuses
   security-sensitive actions (unlock, garage open, valve control, siren
   control, mode changes, HSM arm/disarm).
7. **Alert rules** — create rules (optional; they can also be added later via API).
8. **API Access** — copy the cloud/local URLs. They already include
   `?access_token=…`. Keep the token secret.

To rotate the token, disable/re-enable OAuth in Apps Code or reinstall the app.

## Linking with Muse

1. In the app, open **Link with Muse**.
2. Set your monitoring preferences: how often the assistant polls the hub,
   and after how many consecutive failed polls (within how many minutes)
   it should message you about an outage.
3. Copy the pre-filled setup message and paste it to Muse in chat.
4. Muse verifies with `GET /health` and starts monitoring.

The generated message looks like this (yours is pre-filled with your real
URLs, token, and preferences in the app):

```
Hi Muse — please link with my Hubitat hub through the Muse Bridge app:

Cloud API base URL: https://cloud.hubitat.com/api/<hub-id>/apps/<app-id>
Access token: <token>

Please:
- Poll the hub every 5 minutes: GET <base>/health?access_token=<token>
- If the hub fails to respond 3 times in a row within 15 minutes, message
  me in chat immediately (once per outage), and tell me when it recovers.
  Distinguish timeouts from HTTP 401 (revoked token).
- Each poll, also GET <base>/rules?access_token=<token> — if any rule with
  the "muse" notification channel is newly breached, message me; lead with
  a siren emoji for urgent ones. Stay silent otherwise.
- I may ask you to check device states, run commands, change modes,
  arm/disarm HSM, or create alert rules. Security-sensitive actions need my
  command passcode as the "passcode" field — ask me for it when needed and
  never store it unless I say so.
- Confirm the link by calling /health and telling me what you see.
```

## Alert rule examples

**Garage door left open while the A/C runs** — the canonical example:

- Devices: Garage Door (contact sensor)
- Attribute `contact` is `open`, alert when it persists **longer than 10 minutes**
- Condition: thermostat *Downstairs*, state **cooling**
- Message: `The %device% has been %value% for 10 minutes and the A/C is running.`
- Spoken on the kitchen speaker, repeated every 5 minutes until closed.

**Water leak** — water sensor `water` is `wet`, duration **0.1 minutes** (≈6 s),
no conditions. Immediate announcement.

**Sump pump short-cycling** — pump switch `switch` is `on`, alert when it clears
**sooner than 1 minute** (i.e. it ran less than a minute). Catches a pump that
kicks on and right back off.

**Night mode reminder** — back door `contact` is `open` longer than 5 minutes,
condition: mode is **Night**, time window 22:00–06:00.

**Intrusion while secured** — front door `contact` is `open`, duration
**0.1 minutes** (≈6 s), condition: HSM is **armedAway** (or mode **Away**),
**URGENT** priority, channels: speech + push + Muse chat. Immediate,
can't-miss announcement on every channel.

## API reference

All endpoints accept `?access_token=…`. `POST`/`PUT` bodies are JSON.
Base URLs are shown on the app's **API Access** page (cloud and local).

| Method | Path | Description |
|---|---|---|
| GET | `/health` | App version, hub, mode, device/rule counts, breached rules |
| GET | `/devices` | All exposed devices with capabilities, live attributes, commands |
| GET | `/devices/:id` | One device, same detail |
| POST | `/devices/:id/command` | Run any command: `{"command":"on"}`, `{"command":"setLevel","args":[50]}`, `{"command":"setHeatingSetpoint","args":[70]}` |
| GET | `/devices/:id/events?max=20` | Recent device events |
| GET | `/modes` | Location modes + current mode |
| GET | `/mode` | Current mode |
| POST | `/mode` | Set mode: `{"mode":"Away"}` — **passcode required** |
| GET | `/hsm` | Hubitat Safety Monitor status (`armedAway`, `armedHome`, `armedNight`, `disarmed`, …; null if HSM not installed) |
| POST | `/hsm` | Arm/disarm HSM: `{"arm":"armAway"}` (`armAway`, `armHome`, `armNight`, `disarm`) — **passcode required** |
| POST | `/speak` | Announce: `{"text":"Hello house","devices":["42"]}` (devices optional) |
| POST | `/notify` | Push text to phones: `{"text":"Water detected","devices":["43"]}` (devices optional, defaults to all notification devices) |
| GET | `/rules` | Rules with live state (pending / breached / last alert) |
| POST | `/rules` | Create a rule (see schema below) |
| GET | `/rules/:id` | One rule |
| PUT | `/rules/:id` | Update a rule (partial) |
| DELETE | `/rules/:id` | Delete a rule |
| POST | `/rules/:id/enable` | Enable a rule |
| POST | `/rules/:id/disable` | Disable a rule |
| POST | `/rules/:id/test` | Speak the rule's message immediately |

### Rule JSON schema (POST /rules)

```json
{
  "name": "Garage door left open",
  "enabled": true,
  "triggerDevices": ["12", "13"],
  "attribute": "contact",
  "operator": "=",
  "value": "open",
  "durationMin": 10,
  "alertWhen": "staysLongerThan",
  "repeatMin": 5,
  "speakOnClear": true,
  "modes": ["Home", "Night"],
  "hsmStates": ["armedAway", "armedHome", "armedNight"],
  "thermostatId": "7",
  "thermostatStates": ["cooling"],
  "timeFrom": "22:00",
  "timeTo": "06:00",
  "days": ["Monday", "Tuesday"],
  "message": "The %device% has been %value% for 10 minutes.",
  "speechDeviceIds": ["42"],
  "channels": ["speech", "push", "muse"],
  "pushDeviceIds": ["43"],
  "notifyClear": true,
  "urgent": true,
  "volume": 85
}
```

- `operator`: `=`, `!=`, `>`, `<`, `>=`, `<=` (numeric when both sides are numbers,
  case-insensitive string compare otherwise).
- `alertWhen`: `staysLongerThan` (default) or `clearsSoonerThan`.
- `triggerDevices`: **device ids** (strings) — find them in `GET /devices`.
- `timeFrom`/`timeTo`: `HH:mm` (24h); overnight windows wrap correctly.
- Message tokens: `%device%` `%attribute%` `%value%` `%rule%`.
- `channels`: any of `speech`, `push`, `muse` (default `["speech"]`).
- `pushDeviceIds`: notification-device ids for push; blank = all phones.
- `urgent`: prefix alerts so they stand out (`Urgent.` spoken, `🚨 URGENT:` on push).
- `volume`: announcement volume for this rule (0-100); blank = app default.
- All condition fields are optional; omitted = no restriction.

## Notifications

Each rule picks its announcement channels:

- **🔊 Speech** — spoken on the house speakers via `speechSynthesis`,
  `audioNotification`, or `musicPlayer` devices. Uses `speak` when available,
  otherwise `playTextAndResume` (music resumes afterwards) or `playText`.
  Pick specific speakers or leave blank for all. Announcements can play at a
  configured volume (app default, per-rule override, or per `POST /speak`
  call), with a minimum-volume floor and optional restore afterwards.
- **📱 Push** — text push via the Hubitat mobile app. Each phone/tablet with
  the app is a separate *notification device*, so picking individual devices
  targets individual people instead of the whole household.
- **💬 Muse chat** — the hub can't reach the assistant directly, so this is a
  flag: the assistant's polling loop reads breached rules from `GET /rules`
  and messages you in chat. Urgent rules are flagged prominently.

`notifyClear` sends a "cleared" notice through the same channels when the
condition resolves. `urgent` prefixes every alert (`Urgent.` spoken,
`🚨 URGENT:` on push, flagged in chat). Pair it with an HSM/mode condition —
e.g. *contact `open` while HSM is armed* — for intrusion-style alerts.

`POST /notify` sends an ad-hoc push without a rule:
`{"text":"…","devices":["43"]}`.

## Device health

A daily, push-only health digest — never spoken on speakers:

- **Low batteries** — warns at or below your threshold (default 20%).
- **Quiet devices** — warns when a device has no events for your N days
  (default 7), including devices that never reported.
- Each check has its own **per-device ignore list**.
- Runs daily at your chosen time (default 9:00 AM); one summary listing
  everything needing attention.

The page also shows a live "right now" preview so you can tune thresholds
and ignore lists before the first scheduled run.

### curl examples

```bash
BASE="https://cloud.hubitat.com/api/<hubid>/apps/<appid>"
T="<access_token>"

curl "$BASE/health?access_token=$T"
curl "$BASE/devices?access_token=$T"
curl -X POST "$BASE/devices/12/command?access_token=$T" \
  -H 'Content-Type: application/json' -d '{"command":"setLevel","args":[25]}'
curl -X POST "$BASE/mode?access_token=$T" \
  -H 'Content-Type: application/json' -d '{"mode":"Night"}'
curl -X POST "$BASE/speak?access_token=$T" \
  -H 'Content-Type: application/json' -d '{"text":"Dinner is ready"}'
```

## Muse integration

The intended setup: give the cloud base URL and access token to Muse (stored in
its secure vault). Muse then:

- **Polls** `GET /devices` and `GET /rules` every few minutes for fresh state,
  and messages you when a rule breaches or a watched device looks wrong.
- **Answers questions** — "is the garage door closed?", "what's the house temp?"
- **Acts on request** — "set the house to Night mode", "turn off the porch light",
  "close the water valve".
- **Creates rules on demand** — "alert me if any window is open more than
  15 minutes while the heat is on" becomes a `POST /rules` call.

Because the rule engine lives on the hub, spoken alerts fire even if the
assistant is unreachable.

## Security notes

- The access token is a bearer credential: anyone with the URL can read device
  state and run commands. Treat it like a password.
- **Command passcode:** security-sensitive actions need a second credential —
  the passcode you set on the app's main page — passed as `passcode` in the JSON
  body or query string. This covers:
  - `unlock` on locks, `open` on garage doors, `open`/`close` on valves,
    `off`/`siren`/`strobe`/`both` on sirens,
  - `POST /mode` (location mode changes),
  - `POST /hsm` (HSM arm/disarm).
  
  With no passcode configured, sensitive actions are refused outright (fail
  closed). Everything else — lights, polling, speech, rules — needs only the
  access token.
- Prefer the cloud endpoint only if you need remote access; use the local
  endpoint on your LAN otherwise.
- The app performs no allow-listing on non-sensitive commands —
  `POST /devices/:id/command` can run anything the device supports, exactly
  like the Hubitat UI can.
- Tokens are validated on every request (401 otherwise); a bad passcode gets
  a 403 and is logged without revealing the value.

## Development

- Single-file app: `apps/muse-bridge.groovy`. No child apps — rules are stored
  as data in `state.rules`, so the UI and the REST API share one code path.
- Hubitat Package Manager manifest: `packageManifest.json` (update the
  `location` URLs after publishing to GitHub).

## License

MIT — see [LICENSE](LICENSE).

## Support

Muse Bridge is free. If you'd like to support its development (including the
costs of the AI services it integrates with):

- [Cash.me/$Lowrance](https://cash.me/$Lowrance) — use a debit card, it's free for both of us
- [Venmo @BrianLowrance](https://venmo.com/code?user_id=2603208862072832399)
- [PayPal.me/brianlowrance](https://paypal.me/brianlowrance)
