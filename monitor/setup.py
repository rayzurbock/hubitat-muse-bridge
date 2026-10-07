#!/usr/bin/env python3
"""First-time setup for the Muse Bridge hub monitor.

Asks for everything the monitor needs to operate, then writes:
  - config.json  — API base URL and alert thresholds (no secrets)
  - .token       — your Muse Bridge access token, owner-read-only (0600)

Nothing is printed, logged, or sent anywhere. Re-running this script
updates the existing configuration (it asks before overwriting).
"""
import getpass
import json
import os
import stat
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
CONFIG_PATH = os.path.join(HERE, "config.json")
TOKEN_PATH = os.path.join(HERE, ".token")

EXAMPLE_BASE = "https://cloud.hubitat.com/api/<hub-id>/apps/<app-id>"


def ask(prompt, default=None, secret=False):
    """Prompt for a value. Returns the default when the user hits enter."""
    hint = " [%s]" % default if default is not None else ""
    while True:
        if secret:
            value = getpass.getpass("%s%s: " % (prompt, hint)).strip()
        else:
            value = input("%s%s: " % (prompt, hint)).strip()
        if value:
            return value
        if default is not None:
            return default
        print("  A value is required — this is needed for the monitor to operate.")


def ask_int(prompt, default):
    while True:
        raw = ask(prompt, str(default))
        try:
            value = int(raw)
            if value > 0:
                return value
        except ValueError:
            pass
        print("  Please enter a positive whole number.")


def looks_like_base_url(value):
    v = value.rstrip("/").lower()
    return (v.startswith("https://") and "/api/" in v and "/apps/" in v
            and "<" not in v and ">" not in v)


def looks_like_token(value):
    return len(value) >= 20 and "<" not in value and ">" not in value and " " not in value


def write_private(path, text):
    """Write a file readable only by its owner (0600)."""
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    try:
        os.write(fd, text.encode("utf-8"))
    finally:
        os.close(fd)
    os.chmod(path, 0o600)  # in case the file already existed with wider perms


def main():
    print("Muse Bridge hub monitor — setup")
    print("=" * 40)
    print("Find these in your Hubitat app: Apps -> Muse Bridge -> API Access.")
    print("The URLs there already include ?access_token=... — the base URL is")
    print("everything before /health, and the token is the value after")
    print("?access_token=.\n")

    existing = os.path.exists(CONFIG_PATH) or os.path.exists(TOKEN_PATH)
    if existing:
        again = ask("Configuration already exists. Overwrite it? (yes/no)", "no")
        if again.lower() not in ("yes", "y"):
            print("Keeping existing configuration. Nothing changed.")
            return

    while True:
        base = ask("Cloud API base URL (e.g. %s)" % EXAMPLE_BASE).rstrip("/")
        if looks_like_base_url(base):
            break
        print("  That doesn't look like a Muse Bridge base URL. It should look like:")
        print("  %s" % EXAMPLE_BASE)

    while True:
        token = ask("Access token", secret=True)
        if looks_like_token(token):
            break
        print("  That doesn't look like a token — paste the full value after ?access_token=.")

    print("\nAlert thresholds (press enter to accept the defaults):")
    threshold = ask_int("Message me after this many consecutive failed polls", 3)
    window = ask_int("...within the last N minutes", 15)
    timeout = ask_int("Give up on one poll after N seconds", 25)

    config = {
        "_note": "The access token lives in .token (chmod 600) in this directory, "
                 "never in this file. Poll cadence is set by your scheduler "
                 "(e.g. cron every 5 minutes).",
        "base_url": base,
        "down_threshold": threshold,
        "down_window_minutes": window,
        "http_timeout_seconds": timeout,
    }
    with open(CONFIG_PATH, "w", encoding="utf-8") as f:
        json.dump(config, f, indent=2)
        f.write("\n")
    write_private(TOKEN_PATH, token + "\n")

    # Double-check the token file really is owner-only.
    mode = stat.S_IMODE(os.stat(TOKEN_PATH).st_mode)
    if mode != 0o600:
        print("WARNING: could not restrict .token permissions (mode %o)." % mode)

    print("\nDone. Configuration saved:")
    print("  %s" % CONFIG_PATH)
    print("  %s (owner-only)" % TOKEN_PATH)
    print("\nTest it with:  python3 %s" % os.path.join(HERE, "poll.py"))
    print("Then schedule poll.py on your preferred cadence (e.g. cron every")
    print("5 minutes) and have the scheduler act on its JSON output — see")
    print("README.md in this directory for the field reference.")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit("\nSetup cancelled. Nothing was written.")
