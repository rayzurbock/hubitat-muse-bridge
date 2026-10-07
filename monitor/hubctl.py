#!/usr/bin/env python3
"""Send a Hubitat device command and verify the result quickly.

Usage:
    hubctl.py "<device name or id>" <command> [args ...]
              [--passcode CODE] [--expect attr=value] [--timeout SECS] [--json]

Resolves the device by id or case-insensitive name substring, sends the
command, then checks the device state *immediately* and re-polls about
once per second until the expected state appears (or the timeout hits),
so confirmation lags the physical device as little as possible.

Expected states are inferred for common commands:
    on -> switch=on,  off -> switch=off,
    lock -> lock=locked,  unlock -> lock=unlocked
Override with --expect attr=value (repeatable). If no expectation applies,
the command result alone is reported.

Security-sensitive commands (unlock, garage-door open, valve open/close,
siren control) need the Muse Bridge command passcode: pass --passcode or
you'll be prompted securely.

Reads base_url from config.json and the token from .token in this
directory (run setup.py first). The token is never printed.
"""
import argparse
import getpass
import json
import os
import sys
import time
import urllib.request
import urllib.error
import urllib.parse

HERE = os.path.dirname(os.path.abspath(__file__))
CONFIG_PATH = os.path.join(HERE, "config.json")
TOKEN_PATH = os.path.join(HERE, ".token")

# command -> [(attribute, expected value)] for fast verification
EXPECTED = {
    "on": [("switch", "on")],
    "off": [("switch", "off")],
    "lock": [("lock", "locked")],
    "unlock": [("lock", "unlocked")],
}

# capability -> commands that need the command passcode (mirrors the app)
SENSITIVE = {
    "Lock": {"unlock"},
    "GarageDoorControl": {"open"},
    "Valve": {"open", "close"},
    "Alarm": {"off", "siren", "strobe", "both"},
}


def load_config():
    try:
        with open(CONFIG_PATH, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def load_token():
    try:
        with open(TOKEN_PATH, "r", encoding="utf-8") as f:
            return f.read().strip()
    except Exception:
        return ""


def api(base, token, method, path, body=None, timeout=20):
    url = "%s/%s?access_token=%s" % (base, path.lstrip("/"), token)
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method,
                                 headers={"Content-Type": "application/json",
                                          "Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.load(resp), None
    except urllib.error.HTTPError as e:
        try:
            detail = e.read().decode("utf-8", "replace")[:200]
        except Exception:
            detail = ""
        return None, "http %s %s" % (e.code, detail)
    except Exception as e:
        return None, "connection failed: %s" % e


def find_device(devices, query):
    q = query.strip()
    for d in devices:
        if str(d.get("id")) == q:
            return d, None
    ql = q.lower()
    matches = [d for d in devices
               if ql in str(d.get("label") or d.get("name") or "").lower()]
    if len(matches) == 1:
        return matches[0], None
    if not matches:
        return None, "no device matching '%s'" % query
    names = ", ".join("%s (id %s)" % (d.get("label") or d.get("name"),
                                      d.get("id")) for d in matches[:8])
    return None, "ambiguous '%s' — matches: %s" % (query, names)


def needs_passcode(device, command):
    caps = set(device.get("capabilities") or [])
    return any(command in SENSITIVE.get(c, set()) for c in caps)


def state_matches(attrs, expectations):
    return all(str(attrs.get(a)) == v for a, v in expectations)


def main():
    ap = argparse.ArgumentParser(description="Send a Hubitat command, verify fast.")
    ap.add_argument("device", help="device id or name (substring, case-insensitive)")
    ap.add_argument("command", help="command to send, e.g. on, off, lock, unlock")
    ap.add_argument("args", nargs="*", help="optional command arguments")
    ap.add_argument("--passcode", default=None, help="command passcode for sensitive actions")
    ap.add_argument("--expect", action="append", default=[],
                    help="attr=value to wait for (repeatable); overrides the default")
    ap.add_argument("--timeout", type=float, default=12,
                    help="seconds to wait for the state flip (default 12)")
    ap.add_argument("--json", action="store_true", help="machine-readable output")
    ns = ap.parse_args()

    config = load_config()
    base = (config.get("base_url") or "").rstrip("/")
    token = load_token()
    if not base or not token:
        return fail(ns, "not configured: run `python3 setup.py` first")

    devices, err = api(base, token, "GET", "devices", timeout=30)
    if err:
        return fail(ns, "could not list devices: %s" % err)
    devs = devices if isinstance(devices, list) else devices.get("devices", [])
    device, err = find_device(devs, ns.device)
    if err:
        return fail(ns, err)
    did = device["id"]
    name = device.get("label") or device.get("name")

    passcode = ns.passcode
    if needs_passcode(device, ns.command) and not passcode:
        try:
            passcode = getpass.getpass("Command passcode for '%s': " % ns.command)
        except (EOFError, KeyboardInterrupt):
            return fail(ns, "passcode required for '%s'" % ns.command)
        if not passcode:
            return fail(ns, "passcode required for '%s'" % ns.command)

    body = {"command": ns.command}
    if ns.args:
        body["args"] = ns.args
    if passcode:
        body["passcode"] = passcode

    t0 = time.time()
    result, err = api(base, token, "POST", "devices/%s/command" % did, body)
    if err or not (result or {}).get("ok"):
        detail = err or (result or {}).get("error", "unknown error")
        return fail(ns, "command rejected: %s" % detail,
                    extra={"device": name, "id": did})

    expectations = []
    for e in ns.expect:
        if "=" in e:
            a, v = e.split("=", 1)
            expectations.append((a.strip(), v.strip()))
    if not expectations:
        expectations = EXPECTED.get(ns.command, [])

    verified, elapsed = (not expectations), 0.0
    if expectations:
        # Check immediately — no fixed sleep up front — then poll ~1s.
        deadline = t0 + ns.timeout
        while True:
            dev, derr = api(base, token, "GET", "devices/%s" % did, timeout=10)
            elapsed = time.time() - t0
            if not derr:
                attrs = (dev or {}).get("attributes", {})
                if state_matches(attrs, expectations):
                    verified = True
                    break
            if elapsed >= ns.timeout:
                break
            time.sleep(1.0)

    status = "verified" if verified else ("accepted-unverified" if not expectations
                                          else "not-confirmed")
    out = {"ok": True, "status": status, "device": name, "id": did,
           "command": ns.command, "elapsed_s": round(elapsed, 1)}
    if not verified and expectations:
        out["warning"] = ("hub accepted the command but the expected state %s "
                          "did not appear within %ss" %
                          (", ".join("%s=%s" % e for e in expectations),
                           ns.timeout))
    if ns.json:
        print(json.dumps(out))
    else:
        if verified:
            print("%s: %s -> %s (confirmed in %.1fs)" %
                  (name, ns.command,
                   ", ".join("%s=%s" % e for e in expectations), elapsed))
        elif not expectations:
            print("%s: command '%s' accepted" % (name, ns.command))
        else:
            print("%s: command '%s' accepted but %s not seen within %ss — "
                  "check the device" %
                  (name, ns.command,
                   ", ".join("%s=%s" % e for e in expectations), ns.timeout))
    return 0 if verified or not expectations else 3


def fail(ns, message, extra=None):
    out = {"ok": False, "error": message}
    if extra:
        out.update(extra)
    if getattr(ns, "json", False):
        print(json.dumps(out))
    else:
        print("error: %s" % message, file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
