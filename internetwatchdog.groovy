/**
 *  Internet Watchdog
 *
 *  Periodically tests internet connectivity (captive.apple.com + a secondary
 *  endpoint; an outage requires BOTH to fail), keeps a timestamped forensic
 *  event log, and can power-cycle a modem/router smart plug with a
 *  fail-threshold, boot grace period, and exponential backoff between
 *  restarts so the connection gets time to heal.
 *
 *  Use a Z-Wave or Zigbee plug for the modem — a WiFi/cloud plug can't be
 *  commanded while the network it needs is the thing that's down.
 */

definition(
    name: "Internet Watchdog",
    namespace: "drbbton",
    author: "drbbton",
    description: "Test internet connectivity on a schedule, log outages forensically, and power-cycle the modem with backoff.",
    category: "Utility",
    iconUrl: "",
    iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/drbbton/HubitatInternetWatchdog/main/internetwatchdog.groovy",
    singleInstance: true
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "Internet Watchdog", install: true, uninstall: true) {
        section("<b>Status</b>") {
            paragraph statusSummary()
            input "btnTestNow", "button", title: "Test Now"
            input "btnClearLog", "button", title: "Clear Event Log"
        }
        section("<b>Connectivity tests</b>") {
            input "primaryUrl", "text", title: "Primary test URL",
                  defaultValue: "http://captive.apple.com/hotspot-detect.html", required: true
            input "secondaryUrl", "text", title: "Secondary test URL (outage requires BOTH to fail)",
                  defaultValue: "http://connectivitycheck.gstatic.com/generate_204", required: true
            input "checkMinutes", "number", title: "Check interval while online (minutes)",
                  defaultValue: 5, required: true
            input "downCheckMinutes", "number", title: "Check interval while offline (minutes)",
                  defaultValue: 1, required: true
            input "failThreshold", "number",
                  title: "Consecutive failed checks before an outage is declared",
                  defaultValue: 2, required: true
        }
        section("<b>Recovery — modem/router power cycle</b>") {
            input "restartSwitch", "capability.switch",
                  title: "Power switch to cycle (Z-Wave/Zigbee plug, NOT WiFi)", required: false
            input "offSeconds", "number", title: "Keep power off for (seconds)", defaultValue: 10
            input "bootMinutes", "number", title: "Boot grace period after power-on (minutes, no checks counted)",
                  defaultValue: 5
            input "holdoffMinutes", "number", title: "Minimum wait between restarts (minutes, doubles each retry)",
                  defaultValue: 15
            input "holdoffMaxMinutes", "number", title: "Backoff cap (minutes)", defaultValue: 120
        }
        section("<b>Reporting</b>") {
            input "statusDevice", "capability.presenceSensor",
                  title: "Virtual presence device to mirror status (present = online) — dashboard tile + event history",
                  required: false
            input "notifyDevices", "capability.notification",
                  title: "Push notification devices", multiple: true, required: false
            input "logEnable", "bool", title: "Enable debug logging", defaultValue: true
        }
        section("<b>Event log (newest first)</b>") {
            paragraph(state.eventLog ? state.eventLog.join("<br>") : "No events yet.")
        }
    }
}

def installed() {
    state.eventLog = []
    updated()
}

def updated() {
    unschedule()
    state.consecutiveFails = state.consecutiveFails ?: 0
    state.down = state.down ?: false
    state.restartCount = state.restartCount ?: 0
    addLog("Watchdog (re)started. Interval ${checkMinutes} min, threshold ${failThreshold}, " +
           (restartSwitch ? "power cycle via ${restartSwitch.displayName}." : "no restart switch configured."))
    runIn(5, "runCheck")
}

def appButtonHandler(String btn) {
    if (btn == "btnTestNow") {
        addLog("Manual test requested.")
        runIn(1, "runCheck")
    } else if (btn == "btnClearLog") {
        state.eventLog = []
    }
}

// ---------- check cycle ----------

def runCheck() {
    if (logEnable) log.debug "Testing ${primaryUrl}"
    asynchttpGet("primaryResponse", [uri: primaryUrl, timeout: 10, textParser: true])
}

def primaryResponse(resp, data) {
    if (isGoodResponse(resp, primaryUrl)) {
        markUp()
    } else {
        if (logEnable) log.debug "Primary failed (${respSummary(resp)}), testing ${secondaryUrl}"
        asynchttpGet("secondaryResponse", [uri: secondaryUrl, timeout: 10, textParser: true],
                     [primary: respSummary(resp)])
    }
}

def secondaryResponse(resp, data) {
    if (isGoodResponse(resp, secondaryUrl)) {
        addLog("Primary check failed (${data.primary}) but secondary OK — internet up, endpoint degraded.")
        markUp()
    } else {
        markFail("primary: ${data.primary}; secondary: ${respSummary(resp)}")
    }
}

private boolean isGoodResponse(resp, String url) {
    if (resp.hasError()) return false
    int status = (resp.status ?: 0) as int
    if (status < 200 || status >= 300) return false
    // captive.apple.com must actually say Success — catches captive portals / DNS hijack
    if (url?.contains("captive.apple.com")) {
        return (resp.data ?: "").contains("Success")
    }
    return true
}

private String respSummary(resp) {
    resp.hasError() ? "error: ${resp.getErrorMessage()}" : "HTTP ${resp.status}"
}

// ---------- state transitions ----------

private void markUp() {
    state.consecutiveFails = 0
    if (state.down) {
        String dur = durationSince(state.downSince)
        state.down = false
        state.restartCount = 0
        addLog("<b>RECOVERED</b> after ${dur}" +
               (state.lastRestartMs ? " (last power cycle ${durationSince(state.lastRestartMs)} ago)" : "") + ".")
        notifyAll("Internet recovered after ${dur}.")
        setPresence(true)
    } else {
        // monitor-only mode (no restart switch): log every check result for forensics
        if (!restartSwitch) addLog("Check OK.")
        else if (logEnable) log.debug "Internet OK."
        setPresence(true)  // idempotent; keeps a fresh device in sync
    }
    scheduleNext()
}

private void markFail(String detail) {
    state.consecutiveFails = (state.consecutiveFails ?: 0) + 1
    addLog("Check FAILED (${state.consecutiveFails} consecutive) — ${detail}")
    if (!state.down && state.consecutiveFails >= ((failThreshold ?: 2) as int)) {
        state.down = true
        state.downSince = now()
        addLog("<b>OUTAGE declared</b> after ${state.consecutiveFails} consecutive failures.")
        notifyAll("Internet outage declared (${state.consecutiveFails} consecutive failed checks).")
        setPresence(false)
    }
    if (state.down) {
        maybeRestart()
    }
    scheduleNext()
}

private void scheduleNext() {
    int mins = state.down ? ((downCheckMinutes ?: 1) as int) : ((checkMinutes ?: 5) as int)
    runIn(Math.max(mins, 1) * 60, "runCheck")
}

// ---------- power cycle with backoff ----------

private void maybeRestart() {
    if (!restartSwitch) return
    int count = (state.restartCount ?: 0) as int
    if (count > 0) {
        long holdoffMs = Math.min(
            ((holdoffMinutes ?: 15) as long) * (1L << (count - 1)),
            (holdoffMaxMinutes ?: 120) as long) * 60000L
        long sinceLast = now() - ((state.lastRestartMs ?: 0L) as long)
        if (sinceLast < holdoffMs) {
            if (logEnable) log.debug "In backoff: ${(holdoffMs - sinceLast) / 60000} min until next restart allowed."
            return
        }
    }
    doRestart()
}

private void doRestart() {
    state.restartCount = ((state.restartCount ?: 0) as int) + 1
    state.lastRestartMs = now()
    addLog("<b>POWER CYCLE #${state.restartCount}</b> — ${restartSwitch.displayName} off for ${offSeconds ?: 10}s, " +
           "then ${bootMinutes ?: 5} min boot grace.")
    notifyAll("Power-cycling ${restartSwitch.displayName} (attempt ${state.restartCount}).")
    restartSwitch.off()
    runIn(Math.max((offSeconds ?: 10) as int, 5), "restorePower")
}

def restorePower() {
    restartSwitch.on()
    addLog("Power restored to ${restartSwitch.displayName}. Checks resume in ${bootMinutes ?: 5} min.")
    // overwrites any pending check so nothing counts against the boot window
    runIn(Math.max((bootMinutes ?: 5) as int, 1) * 60, "runCheck")
}

// ---------- helpers ----------

private void setPresence(boolean online) {
    if (!statusDevice) return
    String current = statusDevice.currentValue("presence")
    if (online && current != "present" && statusDevice.hasCommand("arrived")) statusDevice.arrived()
    if (!online && current != "not present" && statusDevice.hasCommand("departed")) statusDevice.departed()
}

private void notifyAll(String msg) {
    notifyDevices?.each { it.deviceNotification("Internet Watchdog: ${msg}") }
}

private void addLog(String msg) {
    String stamp = new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)
    List logList = (state.eventLog ?: []) as List
    logList.add(0, "${stamp} — ${msg}")
    int maxEntries = restartSwitch ? 200 : 500  // monitor-only mode logs every check, keep more history
    if (logList.size() > maxEntries) logList = logList.subList(0, maxEntries)
    state.eventLog = logList
    log.info "Internet Watchdog: ${msg}"
}

private String durationSince(Long ms) {
    if (!ms) return "unknown"
    long secs = (now() - ms) / 1000
    if (secs < 90) return "${secs} sec"
    if (secs < 5400) return "${Math.round(secs / 60)} min"
    return "${new BigDecimal(secs / 3600.0).setScale(1, BigDecimal.ROUND_HALF_UP)} hr"
}

private String statusSummary() {
    String s = state.down == null ? "Not yet run." :
               state.down ? "<span style='color:#d32f2f;font-weight:bold'>OFFLINE</span> since ${new Date(state.downSince as long).format('MM-dd HH:mm', location.timeZone)}" :
               "<span style='color:#2e7d32;font-weight:bold'>ONLINE</span>"
    s += "<br>Consecutive failures: ${state.consecutiveFails ?: 0}"
    if (state.restartCount) s += " · Power cycles this outage: ${state.restartCount}"
    return s
}
