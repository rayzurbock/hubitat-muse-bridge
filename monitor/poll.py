#!/usr/bin/env python3
"""Poll the Hubitat Muse Bridge API; track hub health and rule breaches.

Reads config.json and .token from this directory, maintains state.json,
and prints a JSON summary for the calling agent to act on. Exits 0 unless
the monitor itself is misconfigured.

First-time setup: run `python3 setup.py` and answer the prompts. Your
access token is stored in `.token` (owner-only permissions) and never
printed, logged, or committed.
"""
import json
import os
import time
import urllib.request
import urllib.error

HERE = os.path.dirname(os.path.abspath(__file__))
CONFIG_PATH = os.path.join(HERE, "config.json")
TOKEN_PATH = os.path.join(HERE, ".token")
STATE_PATH = os.path.join(HERE, "state.json")


def load_json(path, default):
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return default


def save_json(path, obj):
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(obj, f, indent=2)
    os.replace(tmp, path)


def api_get(base, token, path, timeout):
    url = "%s/%s?access_token=%s" % (base, path.lstrip("/"), token)
    req = urllib.request.Request(url, headers={"Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            if resp.status != 200:
                return None, "http %s" % resp.status
            return json.load(resp), None
    except urllib.error.HTTPError as e:
        return None, "http %s" % e.code
    except Exception as e:
        return None, "connection failed: %s" % e


def main():
    config = load_json(CONFIG_PATH, {})
    base = (config.get("base_url") or "").rstrip("/")
    timeout = int(config.get("http_timeout_seconds", 25))
    threshold = int(config.get("down_threshold", 3))
    window_s = int(config.get("down_window_minutes", 15)) * 60

    token = ""
    try:
        with open(TOKEN_PATH, "r", encoding="utf-8") as f:
            token = f.read().strip()
    except Exception:
        pass

    if not base or not token or "REPLACE_" in base or "REPLACE_" in token:
        print(json.dumps({"ok": False,
                          "error": "monitor not configured: run `python3 setup.py` "
                                   "in the monitor/ directory and answer the prompts"}))
        return

    state = load_json(STATE_PATH, {})
    now = time.time()
    out = {"ok": True,
           "checked_at": time.strftime("%Y-%m-%dT%H:%M:%S%z", time.localtime(now))}

    health, err = api_get(base, token, "health", timeout)
    failures = [t for t in state.get("consecutive_failures", []) if now - t <= window_s]

    if health is None:
        failures.append(now)
        was_notified = state.get("down_notified", False)
        out["hub_ok"] = False
        out["error"] = err
        out["consecutive_failures"] = len(failures)
        out["down_alert"] = len(failures) >= threshold and not was_notified
        if out["down_alert"]:
            state["down_notified"] = True
        state["consecutive_failures"] = failures
        out["new_breaches"] = []
        out["cleared"] = []
    else:
        recovered = state.get("down_notified", False)
        state["consecutive_failures"] = []
        state["down_notified"] = False
        out["hub_ok"] = True
        out["recovered"] = recovered
        out["mode"] = health.get("mode")

        hsm, _ = api_get(base, token, "hsm", timeout)
        out["hsm"] = (hsm or {}).get("hsm")

        rules, rerr = api_get(base, token, "rules", timeout)
        new_breaches, cleared = [], []
        if rules is not None:
            known = state.get("known_breached", {})
            current = {}
            for r in rules.get("rules", []):
                rid = str(r.get("id"))
                rs = r.get("state", {}) or {}
                if rs.get("breached"):
                    current[rid] = {"name": r.get("name"),
                                    "urgent": bool(r.get("urgent")),
                                    "channels": r.get("channels") or ["speech"]}
            for rid, info in current.items():
                if rid not in known:
                    new_breaches.append({"id": rid, **info})
            for rid, info in known.items():
                if rid not in current:
                    cleared.append({"id": rid, **info})
            state["known_breached"] = current
        else:
            out["rules_error"] = rerr
        out["new_breaches"] = new_breaches
        out["cleared"] = cleared

    state["last_check"] = now
    save_json(STATE_PATH, state)
    print(json.dumps(out))


if __name__ == "__main__":
    main()
