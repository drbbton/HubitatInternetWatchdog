# Hubitat Internet Watchdog

Hubitat app that tests internet connectivity on a configurable schedule, keeps a timestamped forensic event log, and can power-cycle a modem/router smart plug — with a fail threshold, boot grace period, and exponential backoff so the connection gets time to heal before (and between) restarts.

## How it works

- **Checks** `http://captive.apple.com/hotspot-detect.html` every N minutes (default 5; tightens to every 1 min during an outage). The response body must contain `Success`, so captive portals and DNS hijacks count as down.
- **A failure is cross-checked** against a secondary endpoint (default `connectivitycheck.gstatic.com/generate_204`). Only both failing counts — one CDN having a bad day won't reboot your modem.
- **Outage declared** after a configurable number of consecutive failed checks (default 2).
- **Power cycle**: turns the configured plug off (default 10 s), back on, then waits a boot grace period (default 5 min) before counting checks again.
- **Backoff**: repeat restarts within one outage wait 15 → 30 → 60 min, capped at 120 (all configurable). Counters reset on recovery.
- **Forensics**: every event (failed check with the exact HTTP error, outage declared, power cycle, recovery with outage duration) is stamped into a 200-entry log shown on the app page, mirrored to Hubitat Logs, and optionally reflected onto a virtual presence device for dashboard tiles and device event history. Push notifications on outage / restart / recovery. **Test Now** and **Clear Event Log** buttons on the app page.

## Installation

### Option A — Hubitat Package Manager (recommended)

1. Open HPM → **Install** → **Search by URL** and enter:
   ```
   https://raw.githubusercontent.com/drbbton/HubitatInternetWatchdog/main/packageManifest.json
   ```
2. Future updates arrive via HPM **Update**.

### Option B — Manual

1. **Apps Code → + New App**, paste [`internetwatchdog.groovy`](internetwatchdog.groovy), Save.

### Then

1. **Apps → + Add User App → Internet Watchdog** and configure.
2. Optional: create a virtual device with the **Virtual Presence with Switch** (or plain Virtual Presence) driver and select it as the status device — present = online, and its event history is a second forensic timeline.

## Important

- The modem/router plug must be **Z-Wave or Zigbee**. A WiFi or cloud-dependent plug cannot be commanded while the network it depends on is down.
- Do not power the Hubitat hub from the switched plug.
- This tests from the hub's LAN position — it detects ISP/modem/router outages, not WiFi client roaming issues.
