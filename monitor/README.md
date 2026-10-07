# Hub monitor

A small Python poller for the Muse Bridge API. It checks hub health on a
schedule and reports a JSON summary your assistant (or any scheduler) can
act on: hub outages, recoveries, and newly breached alert rules.

## Setup

```bash
cd monitor
python3 setup.py
```

The script asks for everything the monitor needs — no editing files by hand:

1. **Cloud API base URL** — in Hubitat: Apps → Muse Bridge → API Access.
   It's the URL *before* `/health` (e.g.
   `https://cloud.hubitat.com/api/<hub-id>/apps/<app-id>`).
2. **Access token** — the value after `?access_token=` in those URLs.
   Typed invisibly; stored in `.token` with owner-only permissions (0600).
3. **Alert thresholds** — consecutive failed polls and the window they must
   fall in before you're notified (defaults: 3 failures within 15 minutes),
   plus the per-poll timeout (default: 25 seconds).

To reconfigure later, just run `setup.py` again. Your secrets are never
printed, logged, or written anywhere except `.token`.

Prefer files over prompts? Copy `config.example.json` to `config.json`,
fill in `base_url`, and put the token alone in `.token` (then
`chmod 600 .token`).

## Running it

```bash
python3 poll.py
```

Schedule it with cron (every 5 minutes is a good cadence):

```cron
*/5 * * * * /usr/bin/python3 /path/to/monitor/poll.py
```

Each run prints one JSON object on stdout:

| Field | Meaning |
|---|---|
| `ok` | `false` when the monitor itself isn't configured — run `setup.py`. |
| `hub_ok` | Whether `/health` answered. |
| `error` | Why a poll failed (`http 401` means the token was revoked; anything else is a connectivity problem). |
| `consecutive_failures` | Failed polls inside the configured window. |
| `down_alert` | `true` once per outage, when failures reach the threshold. Message the user, then stay quiet until recovery. |
| `recovered` | `true` on the first successful poll after an outage was reported. |
| `mode` / `hsm` | Current location mode and HSM status from the healthy poll. |
| `new_breaches` | Rules that became breached since the last run: `id`, `name`, `urgent`, `channels`. Only relay rules whose `channels` include `muse`; lead with 🚨 for urgent ones. |
| `cleared` | Previously breached rules that are no longer breached. |
| `rules_error` | Present when `/rules` failed but `/health` succeeded. |

Stay silent when there's nothing to report — no "all clear" noise.

## Files

- `poll.py` — the poller. No secrets inside; reads `config.json` + `.token`.
- `setup.py` — interactive first-time setup.
- `config.example.json` — documented example (copy, don't edit in place).
- `config.json`, `.token`, `state.json` — created locally by setup/poller;
  ignored by git, never committed.
