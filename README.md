# Muse Bridge for Hubitat

A Hubitat app that exposes your devices, location modes, and a spoken alert-rule
engine through a REST API — built to integrate with an AI assistant (Muse), but
usable by anything that speaks HTTP.

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
4. **Devices to expose** — select every device the API and rules may use.
5. Set a **command passcode** on the main page. Until you do, the API refuses
   security-sensitive actions (unlock, garage open, valve control, siren
   control, mode changes, HSM arm/disarm).
6. **Alert rules** — create rules (optional; they can also be added later via API).
6. **API Access** — copy the cloud/local URLs. They already include
   `?access_token=…`. Keep the token secret.

To rotate the token, disable/re-enable OAuth in Apps Code or reinstall the app.

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
  "thermostatId": "7",
  "thermostatStates": ["cooling"],
  "timeFrom": "22:00",
  "timeTo": "06:00",
  "days": ["Monday", "Tuesday"],
  "message": "The %device% has been %value% for 10 minutes.",
  "speechDeviceIds": ["42"]
}
```

- `operator`: `=`, `!=`, `>`, `<`, `>=`, `<=` (numeric when both sides are numbers,
  case-insensitive string compare otherwise).
- `alertWhen`: `staysLongerThan` (default) or `clearsSoonerThan`.
- `triggerDevices`: **device ids** (strings) — find them in `GET /devices`.
- `timeFrom`/`timeTo`: `HH:mm` (24h); overnight windows wrap correctly.
- Message tokens: `%device%` `%attribute%` `%value%` `%rule%`.
- All condition fields are optional; omitted = no restriction.

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
