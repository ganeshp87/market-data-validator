#!/usr/bin/env python3
"""
Feed Manager — Check, reconnect, or reset the Binance WebSocket feed.

Usage:
    python3 feed-manager.py              # Check status, reconnect if stale
    python3 feed-manager.py --status     # Just show current feed status
    python3 feed-manager.py --reconnect  # Force delete + recreate feed
    python3 feed-manager.py --stop       # Stop all feeds
"""

import urllib.request
import json
import sys
import time
from datetime import datetime, timezone

BASE = "http://localhost:8082/api"
FEEDS_URL = f"{BASE}/feeds"
STALE_THRESHOLD_SECONDS = 15  # consider feed stale if no tick for 15s

# Default feed config
DEFAULT_FEED = {
    "name": "Binance BTC",
    "url": "wss://stream.binance.com:9443/ws",
    "adapterType": "BINANCE",
    "symbols": ["BTCUSDT", "ETHUSDT"],
}


def api_get(url):
    try:
        resp = urllib.request.urlopen(url, timeout=5)
        return json.loads(resp.read().decode())
    except Exception as e:
        return None


def api_post(url, data=None):
    body = json.dumps(data).encode() if data else b""
    req = urllib.request.Request(url, data=body, method="POST")
    req.add_header("Content-Type", "application/json")
    resp = urllib.request.urlopen(req, timeout=5)
    return json.loads(resp.read().decode()) if resp.status == 200 else None


def api_delete(url):
    req = urllib.request.Request(url, method="DELETE")
    urllib.request.urlopen(req, timeout=5)


def check_backend():
    """Verify backend is reachable."""
    result = api_get(f"{BASE}/metrics")
    if result is None:
        print("✗ Backend not reachable on port 8082")
        print("  Start it with: ./dev-server-start.sh")
        sys.exit(1)
    print("✓ Backend is running")
    return result


def get_feeds():
    feeds = api_get(FEEDS_URL)
    if feeds is None:
        print("✗ Could not fetch feeds")
        sys.exit(1)
    return feeds


def is_feed_healthy(feed):
    """Check if feed is connected and receiving recent ticks."""
    if feed["status"] != "CONNECTED":
        return False, f"status={feed['status']}"

    last_tick = feed.get("lastTickAt")
    if not last_tick:
        return False, "no ticks received"

    # Parse ISO timestamp
    last_ts = datetime.fromisoformat(last_tick.replace("Z", "+00:00"))
    age = (datetime.now(timezone.utc) - last_ts).total_seconds()

    if age > STALE_THRESHOLD_SECONDS:
        return False, f"last tick {age:.0f}s ago (stale)"

    return True, f"last tick {age:.1f}s ago"


def show_status():
    """Display current feed status."""
    metrics = check_backend()
    feeds = get_feeds()

    if not feeds:
        print("  No feeds configured")
        return False

    all_healthy = True
    for f in feeds:
        healthy, reason = is_feed_healthy(f)
        icon = "✓" if healthy else "✗"
        print(f"  {icon} {f['name']} [{f['status']}] — {reason}, ticks={f['tickCount']}")
        if not healthy:
            all_healthy = False

    # Show validation summary
    validators = metrics.get("validator_statuses", {})
    failed = [k for k, v in validators.items() if v != "PASS"]
    if failed:
        print(f"  ⚠ Failing validators: {', '.join(failed)}")
    else:
        print(f"  ✓ All {len(validators)} validators PASS")

    return all_healthy


def delete_all_feeds():
    """Remove all existing feeds."""
    feeds = get_feeds()
    for f in feeds:
        try:
            api_delete(f"{FEEDS_URL}/{f['id']}")
            print(f"  Deleted: {f['name']} ({f['id'][:8]})")
        except Exception as e:
            print(f"  Delete failed: {e}")
    if feeds:
        time.sleep(1)


def create_and_start_feed():
    """Create a fresh Binance feed and start it."""
    print("  Creating Binance feed...")
    data = api_post(FEEDS_URL, DEFAULT_FEED)
    if not data:
        print("  ✗ Failed to create feed")
        return False

    feed_id = data["id"]
    print(f"  Created: {feed_id[:8]}")

    time.sleep(1)
    api_post(f"{FEEDS_URL}/{feed_id}/start")
    print("  Starting feed...")

    # Wait for connection and first ticks
    for i in range(10):
        time.sleep(1)
        feeds = get_feeds()
        for f in feeds:
            if f["id"] == feed_id:
                if f["status"] == "CONNECTED" and f["tickCount"] > 0:
                    print(f"  ✓ Connected — {f['tickCount']} ticks received")
                    return True
                print(f"  Waiting... status={f['status']} ticks={f['tickCount']}")

    print("  ⚠ Feed started but may take a moment to connect")
    return True


def reconnect():
    """Delete all feeds and create a fresh one."""
    print("\n↻ Reconnecting feed...")
    delete_all_feeds()
    return create_and_start_feed()


def stop_all():
    """Stop all feeds."""
    feeds = get_feeds()
    for f in feeds:
        try:
            api_post(f"{FEEDS_URL}/{f['id']}/stop")
            print(f"  Stopped: {f['name']}")
        except Exception:
            pass


def auto_fix():
    """Check status and auto-reconnect if unhealthy."""
    print()
    healthy = show_status()
    if healthy:
        print("\n✓ Feed is healthy — nothing to do")
        return

    feeds = get_feeds()
    if not feeds:
        print("\n  No feeds found — creating one...")
        create_and_start_feed()
        print()
        show_status()
        return

    # Try stopping and restarting first
    feed = feeds[0]
    if feed["status"] in ("ERROR", "DISCONNECTED"):
        print(f"\n  Feed in {feed['status']} state — forcing full reconnect...")
        reconnect()
    else:
        # Stale but "connected" — full reconnect
        print("\n  Feed stale — forcing full reconnect...")
        reconnect()

    print()
    show_status()


def main():
    args = sys.argv[1:]

    if "--help" in args or "-h" in args:
        print(__doc__)
        return

    if "--status" in args:
        print()
        show_status()
    elif "--reconnect" in args:
        check_backend()
        reconnect()
        print()
        show_status()
    elif "--stop" in args:
        check_backend()
        stop_all()
    else:
        # Default: auto-detect and fix
        check_backend()
        auto_fix()


if __name__ == "__main__":
    main()
