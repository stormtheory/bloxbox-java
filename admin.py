#!/usr/bin/env python3
"""
admin.py — Parent/admin CLI to manage the BloxBox game whitelist.

Must be run with sudo to write to /etc/bloxbox_whitelist.json.
The child's account cannot run this without the root password.

Usage:
  sudo admin.py init            — first-time setup
  sudo admin.py list            — show approved games
  sudo admin.py add             — approve a new game
  sudo admin.py remove          — remove an approved game
  sudo admin.py requests        — view pending requests from child
  sudo admin.py clear-requests  — clear all reviewed requests
"""

import json
import os
import sys
from pathlib import Path
import importlib.util

# Load system config from /etc — keeps config out of the app directory
_spec = importlib.util.spec_from_file_location("config", "/etc/bloxbox/config.py")
if _spec is None or _spec.loader is None:
    raise FileNotFoundError("System config not found at /etc/bloxbox/config.py")
    sys.exit(1)
_config = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_config)

# Reference values directly
CONFIG_PATH = _config.CONFIG_PATH
CACHE_DIR = _config.CACHE_DIR
CHILD_USER = _config.CHILD_USER
REQUESTS_PATH  = _config.REQUESTS_PATH

# Fallback for testing without root (remove in production)
if os.geteuid() != 0:
    print("[admin] ⚠️  Not running as root")
    print("[admin] Run with sudo.\n")
    sys.exit(1)


# ── Whitelist helpers ─────────────────────────────────────────────────────────

def load_config() -> dict:
    """Load existing whitelist config, or return a fresh empty structure."""
    if os.path.exists(CONFIG_PATH):
        with open(CONFIG_PATH) as f:
            return json.load(f)
    return {"games": []}


def save_config(data: dict):
    """
    Save whitelist config with restrictive permissions.
    Root owns the file; world-readable so the launcher can read it as the child user.
    """
    with open(CONFIG_PATH, "w") as f:
        json.dump(data, f, indent=2)
    # 644 = root read/write, everyone else read-only
    os.chmod(CONFIG_PATH, 0o644)
    print(f"[admin] ✅  Whitelist saved → {CONFIG_PATH}")


def find_place_id(url_or_id: str) -> str:
    """
    Extract the Roblox place ID from a game URL or return the raw value if numeric.

    Supports:
      - https://www.roblox.com/games/1234567890/Game-Name
      - 1234567890  (bare numeric ID)
    """
    val = url_or_id.strip()

    # Already a numeric place ID
    if val.isdigit():
        return val

    # Extract from roblox.com/games/<id>/... URL pattern
    import re
    match = re.search(r"roblox\.com/games/(\d+)", val)
    if match:
        return match.group(1)

    print(f"[admin] ⚠️  Could not parse place ID from '{val}' — using as-is")
    return val


# ── Requests helpers ──────────────────────────────────────────────────────────

def load_requests() -> list:
    """Load pending game requests submitted by the child, or return empty list."""
    if os.path.exists(REQUESTS_PATH):
        try:
            with open(REQUESTS_PATH) as f:
                return json.load(f).get("requests", [])
        except (json.JSONDecodeError, PermissionError) as e:
            print(f"[admin] Could not read requests file: {e}")
    return []


def save_requests(requests: list):
    """
    Save the requests list back to disk.
    File is world-writable (0622) so the child's account can append new requests.
    """
    with open(REQUESTS_PATH, "w") as f:
        json.dump({"requests": requests}, f, indent=2)
    # 622 = root read/write, everyone else write-only (can't read others' requests)
    import shutil
    os.chmod(REQUESTS_PATH, 0o622)
    shutil.chown(REQUESTS_PATH, user='root', group='root')
    print(f"[admin] ✅  Requests file saved → {REQUESTS_PATH}")


# ── Commands ──────────────────────────────────────────────────────────────────

def cmd_list():
    """List all currently approved games."""
    games = load_config().get("games", [])

    if not games:
        print("No games approved yet. Use 'add' to approve one.")
        return

    print(f"\n{'#':<4} {'Name':<30} {'Place ID':<15} Description")
    print("─" * 72)
    for i, g in enumerate(games, 1):
        print(f"{i:<4} {g['name']:<30} {g['place_id']:<15} {g.get('description', '')}")
    print(f"\n{len(games)} game(s) approved.\n")


def cmd_add():
    """Interactively approve a new game and add it to the whitelist."""
    print("\n── Approve New Game ──────────────────────────────────────────────")
    print("Paste the Roblox game URL or just the place ID.")
    print("Example: https://www.roblox.com/games/185655149/Welcome-to-Bloxburg\n")

    raw      = input("Game URL or Place ID: ").strip()
    place_id = find_place_id(raw)

    if not place_id:
        print("[admin] ❌  No place ID found. Aborting.")
        return

    # Friendly display name shown in the launcher
    name = input("Display name (shown in launcher): ").strip()
    if not name:
        name = f"Game {place_id}"

    # Optional short description shown on the card
    desc = input("Short description (optional, Enter to skip): ").strip()

    # Confirm before writing
    print(f"\n  Name:        {name}")
    print(f"  Place ID:    {place_id}")
    print(f"  Description: {desc or '(none)'}")
    if input("\nApprove this game? [y/N]: ").strip().lower() != "y":
        print("[admin] Cancelled.")
        return

    data = load_config()

    # Guard against duplicate place IDs
    if any(g["place_id"] == place_id for g in data["games"]):
        print(f"[admin] ⚠️  Place ID {place_id} is already in the whitelist.")
        return

    data["games"].append({
        "name":        name,
        "place_id":    place_id,
        "description": desc
    })
    save_config(data)
    print(f"[admin] ✅  '{name}' added to whitelist.")


def cmd_remove():
    """Interactively remove an approved game from the whitelist."""
    data  = load_config()
    games = data.get("games", [])

    if not games:
        print("No games to remove.")
        return

    cmd_list()

    try:
        num = int(input("Enter number to remove (0 to cancel): ").strip())
    except ValueError:
        print("[admin] Invalid input.")
        return

    if num == 0:
        print("[admin] Cancelled.")
        return

    if num < 1 or num > len(games):
        print(f"[admin] ❌  Must be 1–{len(games)}.")
        return

    game = games[num - 1]
    if input(f"Remove '{game['name']}'? [y/N]: ").strip().lower() != "y":
        print("[admin] Cancelled.")
        return

    data["games"].pop(num - 1)
    save_config(data)
    print(f"[admin] ✅  '{game['name']}' removed.")


def cmd_requests():
    """
    View all pending game requests submitted by the child via the launcher.
    Optionally approve one directly from here (calls cmd_add with the URL pre-filled).
    """
    requests = load_requests()

    if not requests:
        print("\nNo pending requests. 🎉\n")
        return

    print(f"\n── Pending Game Requests ({len(requests)}) ──────────────────────────────")
    for i, r in enumerate(requests, 1):
        print(f"\n  [{i}] {r.get('timestamp', 'unknown time')}")
        print(f"      Game: {r.get('game_name', '(none)')}  (Place ID: {r.get('place_id', '?')})  (URL: {r.get('url', '?')})")
        note = r.get('note', '')
        if note:
            print(f"      Note: {note}")
    print()

    # Offer to approve one immediately
    ans = input("Enter request number to approve it now, or Enter to skip: ").strip()
    if ans.isdigit():
        idx = int(ans) - 1
        if 0 <= idx < len(requests):
            place_id  = requests[idx].get("place_id", "")
            game_name = requests[idx].get("game_name", "")
            url = requests[idx].get("url", "")
            print(f"\n[admin] Pre-filling: {game_name} (Place ID: {place_id})")

            # Re-use add flow with URL pre-populated
            name     = input(f"Display name (shown in launcher): ").strip() or f"{game_name}"
            desc     = input("Short description (optional): ").strip()

            print(f"\n  Name:     {name}")
            print(f"  Place ID: {place_id}")
            if input("Approve? [y/N]: ").strip().lower() == "y":
                data = load_config()
                if any(g["place_id"] == place_id for g in data["games"]):
                    print(f"[admin] ⚠️  Already in whitelist.")
                else:
                    data["games"].append({"name": name, "place_id": place_id, "description": desc, "url": url})
                    save_config(data)
                    print(f"[admin] ✅  '{name}' approved and added to whitelist.")


def cmd_clear_requests():
    """Clear all pending requests after you've reviewed them."""
    requests = load_requests()

    if not requests:
        print("No requests to clear.")
        return

    print(f"\n{len(requests)} request(s) will be deleted.")
    if input("Clear all requests? [y/N]: ").strip().lower() != "y":
        print("[admin] Cancelled.")
        return

    save_requests([])
    print("[admin] ✅  All requests cleared.")


def cmd_init():
    """
    First-time setup: create both config files with correct permissions.
    Run this once after copying the scripts to /opt/bloxbox-launcher/.
    """
    import shutil
    # ── Whitelist config ──────────────────────────────────────────────────
    if os.path.exists(CONFIG_PATH):
        if input(f"Whitelist already exists at {CONFIG_PATH}. Overwrite? [y/N]: ").strip().lower() != "y":
            print("[admin] Skipping whitelist init.")
        else:
            save_config({"games": []})
            print(f"[admin] ✅  Fresh whitelist created → {CONFIG_PATH}")
    else:
        save_config({"games": []})
        print(f"[admin] ✅  Whitelist created → {CONFIG_PATH}")
        shutil.chown(CONFIG_PATH, user='root', group='root')
        os.chmod(CONFIG_PATH, 0o644)

    # ── Requests file ─────────────────────────────────────────────────────
    if not os.path.exists(REQUESTS_PATH):
        save_requests([])
        print(f"[admin] ✅  Requests file created → {REQUESTS_PATH}")
        shutil.chown(REQUESTS_PATH, user='root', group='root')
        os.chmod(REQUESTS_PATH, 0o622)
    else:
        print(f"[admin] ℹ️   Requests file already exists → {REQUESTS_PATH}")
        shutil.chown(REQUESTS_PATH, user='root', group='root')
        os.chmod(REQUESTS_PATH, 0o622)
    
    print("\n[admin] Setup complete. Next steps:")
    print("  sudo python3 admin.py add      — approve your first game")
    print("  python3 bloxbox-launcher.py    — launch the kid-facing launcher")

# ── Entry point ───────────────────────────────────────────────────────────────

COMMANDS = {
    "init":           cmd_init,
    "list":           cmd_list,
    "add":            cmd_add,
    "remove":         cmd_remove,
    "requests":       cmd_requests,
    "clear-requests": cmd_clear_requests,
}

def print_usage():
    print("\nUsage: sudo python3 admin.py <command>")
    print("\nCommands:")
    for name, fn in COMMANDS.items():
        # Print first line of each function's docstring as a one-liner description
        doc = (fn.__doc__ or "").strip().splitlines()[0]
        print(f"  {name:<20} {doc}")
    print()


if __name__ == "__main__":
    if len(sys.argv) < 2 or sys.argv[1] not in COMMANDS:
        print_usage()
        sys.exit(1)

    COMMANDS[sys.argv[1]]()
