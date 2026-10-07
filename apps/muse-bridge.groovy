/**
 *  Muse Bridge for Hubitat
 *
 *  Copyright (c) 2026 Rayzurbock (Brian S. Lowrance)
 *  Licensed under the MIT License. See LICENSE file for details.
 *
 *  Exposes selected devices, location modes, and a spoken alert-rule engine
 *  to Muse (or any REST client) through local and Hubitat-cloud endpoints.
 *
 *  Features
 *   - REST API: list devices + live attributes, send any device command,
 *     read recent device events, list/set location modes, trigger speech.
 *   - Alert rules: watch a device attribute, alert when it stays in a state
 *     longer than (or clears sooner than) a duration you choose, with
 *     conditions on mode, thermostat operating state, time window, and days.
 *   - Spoken alerts via speechSynthesis, audioNotification, or musicPlayer
 *     devices, with optional repeat and clear announcements.
 *   - Rules can be managed from the Hubitat UI or through the REST API,
 *     so an assistant (or anything else) can create them on demand.
 */

import groovy.json.JsonOutput

definition(
    name: "Muse Bridge",
    namespace: "musebridge",
    author: "Rayzurbock",
    description: "REST API plus spoken alert-rule engine for Muse and other integrations.",
    category: "Integrations",
    iconUrl: "",
    iconX2Url: "",
    singleInstance: true
)

preferences {
    page(name: "mainPage", title: "Muse Bridge", install: true, uninstall: true)
    page(name: "devicesPage", title: "Devices")
    page(name: "rulesPage", title: "Alert Rules")
    page(name: "rulePage", title: "Alert Rule")
    page(name: "apiPage", title: "API Access")
}

mappings {
    path("/health") {
        action: [GET: "apiHealth"]
    }
    path("/devices") {
        action: [GET: "apiListDevices"]
    }
    path("/devices/:id") {
        action: [GET: "apiGetDevice"]
    }
    path("/devices/:id/command") {
        action: [POST: "apiDeviceCommand"]
    }
    path("/devices/:id/events") {
        action: [GET: "apiDeviceEvents"]
    }
    path("/modes") {
        action: [GET: "apiListModes"]
    }
    path("/mode") {
        action: [GET: "apiGetMode", POST: "apiSetMode"]
    }
    path("/hsm") {
        action: [GET: "apiGetHsm", POST: "apiSetHsm"]
    }
    path("/speak") {
        action: [POST: "apiSpeak"]
    }
    path("/notify") {
        action: [POST: "apiNotify"]
    }
    path("/rules") {
        action: [GET: "apiListRules", POST: "apiCreateRule"]
    }
    path("/rules/:id") {
        action: [GET: "apiGetRule", PUT: "apiUpdateRule", DELETE: "apiDeleteRule"]
    }
    path("/rules/:id/enable") {
        action: [POST: "apiEnableRule"]
    }
    path("/rules/:id/disable") {
        action: [POST: "apiDisableRule"]
    }
    path("/rules/:id/test") {
        action: [POST: "apiTestRule"]
    }
}

// ============================================================================
// Pages
// ============================================================================

def mainPage() {
    ensureToken()
    def devCount = allExposedDevices().size()
    def rules = getRules()
    def breached = rules.count { ruleStateFor(it.id).breached }
    dynamicPage(name: "mainPage", title: "Muse Bridge <span style='font-size:70%'>v${appVersion()}</span>", install: true, uninstall: true) {
        section() {
            paragraph("Muse Bridge exposes your Hubitat to Muse (or any REST client) and runs " +
                "spoken alert rules locally on the hub. Pick the devices to expose, define alert " +
                "rules, then grab the API URLs from <b>API Access</b>.")
        }
        section("Setup") {
            href(name: "devicesHref", page: "devicesPage",
                title: "Devices to expose",
                description: devCount ? "${devCount} device${devCount == 1 ? '' : 's'} selected" : "Tap to choose devices")
            href(name: "rulesHref", page: "rulesPage",
                title: "Alert rules",
                description: rules ? "${rules.size()} rule${rules.size() == 1 ? '' : 's'} (${breached} breached)" : "Tap to create alert rules")
            href(name: "apiHref", page: "apiPage",
                title: "API Access",
                description: state.accessToken ? "Endpoints and token ready" : "OAuth token missing — see page")
        }
        section("Security") {
            paragraph("A command passcode is required by the API before security-sensitive " +
                "actions: unlocking locks, opening garage doors, opening/closing valves, " +
                "siren control, location mode changes, and HSM arm/disarm.")
            input "securityPasscode", "text", title: "Command passcode",
                description: "Anyone with hub admin access can see this — that is expected"
            input "requirePasscode", "bool", title: "Require the passcode for security-sensitive actions",
                defaultValue: true
        }
        section("Options") {
            input "logDebug", "bool", title: "Enable debug logging", defaultValue: false
        }
    }
}

def devicesPage() {
    dynamicPage(name: "devicesPage", title: "Devices Exposed to the API", nextPage: "mainPage") {
        section() {
            paragraph("These devices are visible to the REST API and available as alert-rule triggers. " +
                "A device selected under more than one capability is only exposed once.")
        }
        section("Bulk select") {
            paragraph("Hubitat's per-capability pickers don't always offer Select All. " +
                "Use this master list instead: open it, Select All, Done. " +
                "The capability sections below are optional fine-tuning.")
            input "devMaster", "capability.*", title: "All devices (master list)", multiple: true, required: false
        }
        section("Switches, dimmers & lights") {
            input "swSwitches", "capability.switch", title: "Switches", multiple: true, required: false
            input "swLevels", "capability.switchLevel", title: "Dimmers / level controls", multiple: true, required: false
            input "swColors", "capability.colorControl", title: "Color lights", multiple: true, required: false
        }
        section("Locks, doors, valves, shades & buttons") {
            input "swLocks", "capability.lock", title: "Locks", multiple: true, required: false
            input "swGarage", "capability.garageDoorControl", title: "Garage doors", multiple: true, required: false
            input "swValves", "capability.valve", title: "Valves", multiple: true, required: false
            input "swShades", "capability.windowShade", title: "Shades / blinds", multiple: true, required: false
            input "swButtons", "capability.button", title: "Buttons", multiple: true, required: false
        }
        section("Contact, motion & presence") {
            input "seContact", "capability.contactSensor", title: "Contact sensors", multiple: true, required: false
            input "seMotion", "capability.motionSensor", title: "Motion sensors", multiple: true, required: false
            input "sePresence", "capability.presenceSensor", title: "Presence sensors", multiple: true, required: false
            input "seAccel", "capability.accelerationSensor", title: "Acceleration sensors", multiple: true, required: false
            input "seTamper", "capability.tamperAlert", title: "Tamper sensors", multiple: true, required: false
        }
        section("Environmental") {
            input "enTemp", "capability.temperatureMeasurement", title: "Temperature sensors", multiple: true, required: false
            input "enHumidity", "capability.relativeHumidityMeasurement", title: "Humidity sensors", multiple: true, required: false
            input "enIllum", "capability.illuminanceMeasurement", title: "Illuminance sensors", multiple: true, required: false
            input "enPower", "capability.powerMeter", title: "Power meters", multiple: true, required: false
            input "enEnergy", "capability.energyMeter", title: "Energy meters", multiple: true, required: false
            input "enBattery", "capability.battery", title: "Battery devices", multiple: true, required: false
        }
        section("Safety") {
            input "saWater", "capability.waterSensor", title: "Water sensors", multiple: true, required: false
            input "saSmoke", "capability.smokeDetector", title: "Smoke detectors", multiple: true, required: false
            input "saCO", "capability.carbonMonoxideDetector", title: "Carbon monoxide detectors", multiple: true, required: false
        }
        section("Climate") {
            input "clThermostats", "capability.thermostat", title: "Thermostats", multiple: true, required: false
        }
        section("Speech & announcements") {
            paragraph("Alert rules speak through these devices, using the <b>speak</b> command " +
                "when available and <b>playText</b> otherwise.")
            input "spSpeech", "capability.speechSynthesis", title: "Speech synthesis devices", multiple: true, required: false
            input "spAudio", "capability.audioNotification", title: "Audio notification devices", multiple: true, required: false
            input "spMusic", "capability.musicPlayer", title: "Music players", multiple: true, required: false
        }
        section("Sirens") {
            input "alSirens", "capability.alarm", title: "Sirens / alarms", multiple: true, required: false
        }
        section("Phone / push notifications") {
            paragraph("Each Hubitat mobile-app device is one phone or tablet. " +
                "Rules can push text alerts to specific phones, so a text alert " +
                "can go to one person instead of everyone.")
            input "ntPhones", "capability.notification", title: "Notification devices", multiple: true, required: false
        }
    }
}

def rulesPage(params) {
    if (params?.deleteId) {
        if (params.deleteId == state.editingRuleId) state.editingRuleId = null
        state.rules = getRules().findAll { it.id != params.deleteId }
        if (state.ruleState) state.ruleState.remove(params.deleteId)
        // drop the rule's orphaned settings
        settings.keySet().findAll { it.startsWith("r_${params.deleteId}_") }.each { k ->
            try { app.removeSetting(k) } catch (e) { /* older platforms: leave it, harmless */ }
        }
        initialize()
    } else if (state.editingRuleId) {
        syncRuleFromSettings(state.editingRuleId)
        initialize()
    }
    def rules = getRules()
    dynamicPage(name: "rulesPage", title: "Alert Rules", nextPage: "mainPage") {
        section() {
            paragraph("A rule watches a device attribute and announces when it stays in a state " +
                "longer than your duration — or clears sooner than it. Rules also support " +
                "conditions on mode, thermostat state, time window, and days.")
        }
        section("Rules") {
            if (!rules) {
                paragraph("No rules yet. Add your first one below — e.g. “Garage door open 10 minutes”.")
            }
            rules.each { r ->
                def st = ruleStateFor(r.id)
                href(name: "rule_${r.id}", page: "rulePage", params: [ruleId: r.id],
                    title: "${r.enabled ? '' : '⏸ '}${r.name}",
                    description: ruleDescription(r, st))
            }
        }
        section() {
            href(name: "ruleNew", page: "rulePage", params: [ruleId: "new"],
                title: "Add new rule", description: "Create an alert rule")
        }
    }
}

def rulePage(params) {
    def rid = params?.ruleId ?: state.editingRuleId
    if (!rid || rid == "new") rid = "r${now()}"
    state.editingRuleId = rid
    def p = "r_${rid}_"
    def st = ruleStateFor(rid)
    dynamicPage(name: "rulePage", title: "Alert Rule", nextPage: "rulesPage") {
        section("Rule") {
            input "${p}enabled", "bool", title: "Enabled", defaultValue: true
            input "${p}name", "text", title: "Rule name", required: true,
                description: "e.g. Garage door left open"
        }
        section("Trigger — the device state to watch") {
            input "${p}trigDevs", "enum", title: "Devices to watch", multiple: true, required: true,
                options: deviceOptions(),
                description: "Alert when ANY of these matches below"
            input "${p}attr", "enum", title: "Attribute", required: true,
                options: attributeOptions(settings["${p}trigDevs"]),
                description: "Pick devices first to narrow this list"
            input "${p}op", "enum", title: "Comparison", required: true, defaultValue: "=",
                options: ["=": "is (=)", "!=": "is not (!=)",
                          ">": "greater than (>)", "<": "less than (<)",
                          ">=": "at least (>=)", "<=": "at most (<=)"]
            input "${p}value", "text", title: "Value to compare against", required: true,
                description: "e.g. open, active, on, locked, wet, 78"
        }
        section("Timing") {
            input "${p}alertWhen", "enum", title: "Alert when the state…", required: true,
                defaultValue: "staysLongerThan",
                options: ["staysLongerThan": "…persists LONGER than the duration",
                          "clearsSoonerThan": "…clears SOONER than the duration"]
            input "${p}dur", "decimal", title: "Duration (minutes)", required: true, defaultValue: 10,
                description: "Decimals allowed, e.g. 0.5 for 30 seconds"
            input "${p}repeat", "decimal", title: "Repeat the announcement every (minutes, 0 = once)", defaultValue: 0
        }
        section("Conditions — the rule only arms when ALL of these hold (blank = always)") {
            input "${p}modes", "enum", title: "Location modes", multiple: true, required: false,
                options: modeOptions()
            input "${p}hsmStates", "enum", title: "Security monitor (HSM) is…", multiple: true, required: false,
                options: ["armedAway": "Armed (away)", "armedHome": "Armed (home)",
                          "armedNight": "Armed (night)", "disarmed": "Disarmed"],
                description: "Requires Hubitat Safety Monitor; blank = any state"
            input "${p}tstat", "enum", title: "Thermostat", required: false,
                options: thermostatOptions(),
                description: "e.g. only alert about open windows while the A/C runs"
            input "${p}tstatStates", "enum", title: "Thermostat is…", multiple: true, required: false,
                options: ["heating": "heating", "cooling": "cooling", "idle": "idle",
                          "fan only": "fan only", "pending heat": "pending heat", "pending cool": "pending cool"]
            input "${p}timeFrom", "time", title: "Only between (from)", required: false
            input "${p}timeTo", "time", title: "…and (to)", required: false
            input "${p}days", "enum", title: "Days of the week", multiple: true, required: false,
                options: ["Monday": "Monday", "Tuesday": "Tuesday", "Wednesday": "Wednesday",
                          "Thursday": "Thursday", "Friday": "Friday", "Saturday": "Saturday", "Sunday": "Sunday"]
        }
        section("Notifications") {
            paragraph("Choose how this rule announces. Text alerts (push) can target " +
                "specific phones; spoken alerts use the house speakers; Muse chat " +
                "messages arrive via the assistant's polling.")
            input "${p}msg", "text", title: "Message (blank = automatic)", required: false,
                description: "Tokens: %device% %attribute% %value% %rule%. Used for every channel."
            input "${p}channels", "enum", title: "Send alerts via", multiple: true, required: true,
                defaultValue: ["speech"],
                options: ["speech": "🔊 Spoken on house speakers",
                          "push"  : "📱 Push to phones (Hubitat app)",
                          "muse"  : "💬 Message in Muse chat"]
            input "${p}speech", "enum", title: "Speak on (blank = all speech devices)",
                multiple: true, required: false, options: speechOptions()
            input "${p}pushDevs", "enum", title: "Push to (blank = all phones)",
                multiple: true, required: false, options: pushOptions(),
                description: "Each phone is a separate device — pick who gets the text"
            input "${p}notifyClear", "bool", title: "Also notify when the condition clears", defaultValue: false
            input "${p}urgent", "bool", title: "URGENT priority", defaultValue: false,
                description: "Alerts are prefixed so they stand out on every channel"
        }
        section("Status") {
            paragraph(ruleStatusText(rid, st))
        }
        section("Danger zone") {
            href(name: "del_${rid}", page: "rulesPage", params: [deleteId: rid],
                title: "Delete this rule", description: "Removes the rule permanently")
        }
    }
}

def apiPage() {
    ensureToken()
    dynamicPage(name: "apiPage", title: "API Access", nextPage: "mainPage") {
        section() {
            if (!state.accessToken) {
                paragraph("<b>OAuth is not enabled for this app.</b><br>" +
                    "In <b>Apps Code</b>, open <b>Muse Bridge</b>, click <b>OAuth</b>, " +
                    "enable it, then come back here. The token and URLs will appear below.")
            } else {
                paragraph("Use these URLs with <b>?access_token=${state.accessToken}</b> appended. " +
                    "Keep the token secret — anyone with it can read and control your devices.")
                paragraph("<b>Cloud health check:</b><br>${fullApiServerUrl('health')}?access_token=${state.accessToken}")
                paragraph("<b>Cloud devices:</b><br>${fullApiServerUrl('devices')}?access_token=${state.accessToken}")
                paragraph("<b>Local health check:</b><br>${fullLocalApiServerUrl('health')}?access_token=${state.accessToken}")
                paragraph("<b>Local devices:</b><br>${fullLocalApiServerUrl('devices')}?access_token=${state.accessToken}")
                paragraph("Security-sensitive actions (unlock, garage open, valve open/close, " +
                    "siren control, mode changes, HSM arm/disarm) additionally require your " +
                    "command passcode as <b>passcode</b> in the JSON body or query string. " +
                    "Set it on the app's main page.")
                paragraph("Full endpoint reference is in the README. To revoke access, disable OAuth " +
                    "in Apps Code or reinstall the app to rotate the token.")
            }
        }
    }
}

// ============================================================================
// Lifecycle
// ============================================================================

def installed() {
    log.info "Muse Bridge v${appVersion()} installed"
    state.rules = []
    state.ruleState = [:]
    ensureToken()
    initialize()
}

def updated() {
    log.info "Muse Bridge v${appVersion()} updated"
    if (state.editingRuleId) syncRuleFromSettings(state.editingRuleId)
    ensureToken()
    initialize()
}

def uninstalled() {
    log.info "Muse Bridge uninstalled"
}

def initialize() {
    unsubscribe()
    unschedule()
    state.ruleState = state.ruleState ?: [:]
    def rules = getRules()
    rules.each { rule ->
        if (rule.enabled) subscribeRule(rule)
    }
    if (rules) {
        subscribe(location, "mode", "ruleEventHandler")
        runEvery5Minutes("periodicRuleEval")
    }
    // unschedule() above wiped all pending timers: drop stale "pending" flags so
    // evaluateAllRules() re-arms them cleanly instead of relying on recovery.
    rules.each { rule ->
        if (!rule.enabled) return
        def st = ruleStateFor(rule.id)
        if (st.pending) {
            st.pending = false
            st.pendingSince = null
            saveRuleState(rule.id, st)
        }
    }
    evaluateAllRules("init")
    // Re-schedule repeat announcements for rules that are still breached.
    rules.each { rule ->
        if (!rule.enabled) return
        def st = ruleStateFor(rule.id)
        if (st.breached && (rule.repeatMin as BigDecimal) > 0 &&
            triggerConditionTrue(rule) && conditionsTrue(rule)) {
            def secs = Math.max(30, ((rule.repeatMin as BigDecimal) * 60) as long)
            runIn(secs, "ruleRepeat", [data: [ruleId: rule.id], overwrite: false])
        }
    }
    logDebug("initialized with ${allExposedDevices().size()} devices and ${rules.count { it.enabled }} enabled rules")
}

def ensureToken() {
    if (!state.accessToken) {
        try {
            createAccessToken()
            log.info "Muse Bridge: OAuth access token created"
        } catch (e) {
            log.warn "Muse Bridge: could not create access token — is OAuth enabled in Apps Code? ${e.message}"
        }
    }
}

// ============================================================================
// Rule engine
// ============================================================================

def subscribeRule(rule) {
    if (!rule.attribute) {
        log.warn "Muse Bridge: rule '${rule.name}' has no attribute, skipping subscriptions"
        return
    }
    def devs = triggerDevices(rule)
    if (!devs) {
        log.warn "Muse Bridge: rule '${rule.name}' has no matching trigger devices, skipping subscriptions"
        return
    }
    devs.each { d ->
        try {
            subscribe(d, rule.attribute, "ruleEventHandler")
        } catch (e) {
            log.warn "Muse Bridge: could not subscribe to ${d.displayName}.${rule.attribute}: ${e.message}"
        }
    }
    if (rule.thermostatId) {
        def t = getDeviceById(rule.thermostatId)
        if (t) {
            try { subscribe(t, "thermostatOperatingState", "ruleEventHandler") }
            catch (e) { log.warn "Muse Bridge: could not subscribe to thermostat: ${e.message}" }
        }
    }
}

def ruleEventHandler(evt) {
    def ids
    if (evt.name == "mode") {
        logDebug("mode changed to ${evt.value}, re-evaluating all rules")
        ids = getRules().findAll { it.enabled }.collect { it.id }
    } else {
        def devId = evt.deviceId?.toString()
        logDebug("event: device ${devId} ${evt.name} = ${evt.value}")
        ids = getRules().findAll { rule ->
            rule.enabled && (triggerDeviceIds(rule).contains(devId) || rule.thermostatId?.toString() == devId)
        }.collect { it.id }
    }
    ids.each { id ->
        def rule = getRule(id)
        if (rule) evaluateRule(rule, "event")
    }
}

def periodicRuleEval() {
    // Catches time-window/day edges, missed events, and timers lost to a hub reboot.
    getRules().each { rule ->
        if (!rule.enabled) return
        evaluateRule(rule, "periodic")
        def st = ruleStateFor(rule.id)
        if (st.pending && rule.alertWhen == "staysLongerThan") {
            def durMs = (rule.durationMin as BigDecimal) * 60000
            if (now() - (st.pendingSince ?: now()) > durMs + 300000) {
                logDebug("rule '${rule.name}': pending timer appears lost, checking now")
                ruleCheck([ruleId: rule.id])
            }
        }
    }
}

def evaluateAllRules(String reason) {
    getRules().each { rule ->
        if (rule.enabled) evaluateRule(rule, reason)
    }
}

def evaluateRule(rule, String reason) {
    def st = ruleStateFor(rule.id)
    def trig = triggerConditionTrue(rule)
    def cond = conditionsTrue(rule)
    logDebug("evaluate '${rule.name}' (${reason}): trigger=${trig} conditions=${cond} pending=${st.pending} breached=${st.breached}")

    if (trig && cond) {
        if (rule.alertWhen == "clearsSoonerThan") {
            if (!st.activeSince) {
                st.activeSince = now()
                saveRuleState(rule.id, st)
                logDebug("rule '${rule.name}': timing active period")
            }
        } else { // staysLongerThan
            if (!st.pending && !st.breached) {
                st.pending = true
                st.pendingSince = now()
                saveRuleState(rule.id, st)
                def secs = Math.max(5, ((rule.durationMin as BigDecimal) * 60) as long)
                runIn(secs, "ruleCheck", [data: [ruleId: rule.id], overwrite: false])
                logDebug("rule '${rule.name}': condition met, checking again in ${secs}s")
            }
        }
    } else {
        if (rule.alertWhen == "clearsSoonerThan" && st.activeSince) {
            def elapsedMin = (now() - st.activeSince) / 60000.0
            if (elapsedMin < (rule.durationMin as BigDecimal)) {
                fireAlert(rule, "cleared after ${elapsedMin.round(1)} min (minimum ${rule.durationMin})")
            } else {
                logDebug("rule '${rule.name}': active period satisfied (${elapsedMin.round(1)} min)")
            }
            st.activeSince = null
        }
        if (st.pending) {
            st.pending = false
            st.pendingSince = null
            logDebug("rule '${rule.name}': no longer pending")
        }
        if (st.breached) {
            st.breached = false
            log.info "Muse Bridge: rule '${rule.name}' cleared"
            if (rule.notifyClear) notifyRule(rule, "Cleared: ${rule.name}.")
        }
        saveRuleState(rule.id, st)
    }
}

def ruleCheck(data) {
    def rule = getRule(data?.ruleId as String)
    if (!rule || !rule.enabled) return
    def st = ruleStateFor(rule.id)
    if (!st.pending) return // superseded — condition already cleared
    if (triggerConditionTrue(rule) && conditionsTrue(rule)) {
        st.pending = false
        st.pendingSince = null
        st.breached = true
        st.lastBreach = now()
        saveRuleState(rule.id, st)
        fireAlert(rule, "active for ${rule.durationMin} min")
        if ((rule.repeatMin as BigDecimal) > 0) {
            def secs = Math.max(30, ((rule.repeatMin as BigDecimal) * 60) as long)
            runIn(secs, "ruleRepeat", [data: [ruleId: rule.id], overwrite: false])
        }
    } else {
        st.pending = false
        st.pendingSince = null
        saveRuleState(rule.id, st)
    }
}

def ruleRepeat(data) {
    def rule = getRule(data?.ruleId as String)
    if (!rule || !rule.enabled) return
    def st = ruleStateFor(rule.id)
    if (st.breached && triggerConditionTrue(rule) && conditionsTrue(rule)) {
        fireAlert(rule, "still active")
        if ((rule.repeatMin as BigDecimal) > 0) {
            def secs = Math.max(30, ((rule.repeatMin as BigDecimal) * 60) as long)
            runIn(secs, "ruleRepeat", [data: [ruleId: rule.id], overwrite: false])
        }
    } else {
        st.breached = false
        saveRuleState(rule.id, st)
    }
}

def fireAlert(rule, String reasonDetail) {
    def dev = triggerDevices(rule)?.find { d -> compareValues(d.currentValue(rule.attribute), rule.operator, rule.value) }
    def msg = rule.message ?: "Alert: ${rule.name}."
    msg = msg.replace("%rule%", rule.name ?: "")
        .replace("%device%", dev?.displayName ?: "device")
        .replace("%attribute%", rule.attribute ?: "")
        .replace("%value%", dev ? "${dev.currentValue(rule.attribute)}" : "")
    log.warn "Muse Bridge ALERT [${rule.name}]: ${msg} (${reasonDetail})"
    notifyRule(rule, msg)
}

/**
 * Send a rule notification through every channel the rule selected:
 *  speech — spoken on the house speakers (local, immediate)
 *  push   — text push to the selected phones via the Hubitat mobile app
 *  muse   — nothing the hub can do directly; the assistant's polling loop
 *           sees the breach in GET /rules and messages the user in chat.
 */
def notifyRule(rule, String text) {
    def channels = rule.channels ?: ["speech"]
    def urgent = rule.urgent == true
    if ("speech" in channels) speakForRule(rule, urgent ? "Urgent. ${text}" : text)
    if ("push" in channels) pushForRule(rule, urgent ? "🚨 URGENT: ${text}" : text)
    if ("muse" in channels) log.info "Muse Bridge [muse channel${urgent ? ' URGENT' : ''}] ${rule.name}: ${text}"
}

def pushForRule(rule, String text) {
    def ids = rule.pushDeviceIds ?: defaultPushDeviceIds()
    if (!ids) {
        log.warn "Muse Bridge: no notification devices configured, cannot push: ${text}"
        return
    }
    ids.each { id ->
        def d = getDeviceById(id)
        if (!d) {
            log.warn "Muse Bridge: notification device id ${id} not found"
            return
        }
        try {
            if (d.hasCommand("deviceNotification")) {
                d.deviceNotification(text)
                logDebug("pushed to ${d.displayName}: ${text}")
            } else {
                log.warn "Muse Bridge: ${d.displayName} has no deviceNotification command"
            }
        } catch (e) {
            log.warn "Muse Bridge: push to ${d.displayName} failed: ${e.message}"
        }
    }
}

def speakForRule(rule, String text) {
    def ids = rule.speechDeviceIds ?: defaultSpeechDeviceIds()
    if (!ids) {
        log.warn "Muse Bridge: no speech devices configured, cannot announce: ${text}"
        return
    }
    speakOnDevices(ids, text)
}

def speakOnDevices(List ids, String text) {
    ids.each { id ->
        def d = getDeviceById(id)
        if (!d) {
            log.warn "Muse Bridge: speech device id ${id} not found"
            return
        }
        try {
            if (d.hasCommand("speak")) {
                d.speak(text)
            } else if (d.hasCommand("playText")) {
                d.playText(text)
            } else {
                log.warn "Muse Bridge: ${d.displayName} has no speak/playText command"
            }
            logDebug("announced on ${d.displayName}: ${text}")
        } catch (e) {
            log.warn "Muse Bridge: failed to announce on ${d.displayName}: ${e.message}"
        }
    }
}

// --- rule condition helpers -------------------------------------------------

def triggerConditionTrue(rule) {
    if (!rule.attribute) return false
    def devs = triggerDevices(rule)
    if (!devs) return false
    return devs.any { d -> compareValues(d.currentValue(rule.attribute), rule.operator, rule.value) }
}

def conditionsTrue(rule) {
    if (rule.modes && !(location.mode in rule.modes)) return false
    if (rule.hsmStates) {
        def hs = hsmStatus()
        if (!(hs in rule.hsmStates)) return false
    }
    if (rule.thermostatId) {
        def t = getDeviceById(rule.thermostatId)
        if (!t) {
            log.warn "Muse Bridge: rule '${rule.name}' references missing thermostat ${rule.thermostatId}"
            return false
        }
        def opState = t.currentValue("thermostatOperatingState")?.toString()
        if (rule.thermostatStates && !(opState in rule.thermostatStates)) return false
    }
    if (rule.timeFrom && rule.timeTo && !inTimeWindow(rule.timeFrom, rule.timeTo)) return false
    if (rule.days && !(new Date().format("EEEE") in rule.days)) return false
    return true
}

def compareValues(actual, String operator, expected) {
    def a = actual?.toString()?.trim()
    def e = expected?.toString()?.trim()
    if (a == null || e == null) return false
    def aNum = isNumeric(a)
    def eNum = isNumeric(e)
    if (aNum && eNum) {
        def an = a as BigDecimal
        def en = e as BigDecimal
        switch (operator) {
            case "=": return an == en
            case "!=": return an != en
            case ">": return an > en
            case "<": return an < en
            case ">=": return an >= en
            case "<=": return an <= en
            default: return false
        }
    }
    switch (operator) {
        case "=": return a.equalsIgnoreCase(e)
        case "!=": return !a.equalsIgnoreCase(e)
        default:
            log.warn "Muse Bridge: operator '${operator}' needs numeric values (got '${a}' vs '${e}')"
            return false
    }
}

def isNumeric(String s) {
    return s ==~ /-?\d+(\.\d+)?/
}

def inTimeWindow(String from, String to) {
    def f = parseHourMinute(from)
    def t = parseHourMinute(to)
    if (f == null || t == null) return true
    def n = minutesSinceMidnight(new Date())
    if (f <= t) return n >= f && n <= t
    return n >= f || n <= t // overnight window
}

def parseHourMinute(String s) {
    try {
        def hm = s
        if (s.contains("T")) hm = s.substring(11, 16) // "yyyy-MM-ddTHH:mm:ss..."
        def parts = hm.split(":")
        return (parts[0] as int) * 60 + (parts[1] as int)
    } catch (e) {
        log.warn "Muse Bridge: could not parse time '${s}'"
        return null
    }
}

def minutesSinceMidnight(Date d) {
    def cal = Calendar.getInstance()
    cal.setTime(d)
    return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
}

// --- rule storage -----------------------------------------------------------

def getRules() {
    return state.rules ?: []
}

def getRule(String id) {
    return getRules().find { it.id == id }
}

def ruleStateFor(String rid) {
    if (!state.ruleState) state.ruleState = [:]
    def st = state.ruleState[rid] ?: [:]
    if (!st.containsKey("pending")) st.pending = false
    if (!st.containsKey("breached")) st.breached = false
    state.ruleState[rid] = st
    return st
}

def saveRuleState(String rid, Map st) {
    if (!state.ruleState) state.ruleState = [:]
    state.ruleState[rid] = st
}

def triggerDeviceIds(rule) {
    return (rule.triggerDevices ?: []).collect { it.toString() }
}

def triggerDevices(rule) {
    return triggerDeviceIds(rule).collect { getDeviceById(it) }.findAll { it != null }
}

def defaultSpeechDeviceIds() {
    return speechDevices().collect { it.id.toString() }
}

/** Copy the rule editor's settings into state.rules (single source of truth). */
def syncRuleFromSettings(String rid) {
    if (!rid) return
    def p = "r_${rid}_"
    def name = settings["${p}name"]?.toString()?.trim()
    if (!name) return // editor opened but never filled in — nothing to save
    def rule = [
        id             : rid,
        name           : name,
        enabled        : settings["${p}enabled"] != false,
        triggerDevices : (settings["${p}trigDevs"] ?: []).collect { it.toString() },
        attribute      : settings["${p}attr"]?.toString(),
        operator       : settings["${p}op"]?.toString() ?: "=",
        value          : settings["${p}value"]?.toString(),
        durationMin    : (settings["${p}dur"] ?: 10) as BigDecimal,
        alertWhen      : settings["${p}alertWhen"]?.toString() ?: "staysLongerThan",
        repeatMin      : (settings["${p}repeat"] ?: 0) as BigDecimal,
        modes          : (settings["${p}modes"] ?: []).collect { it.toString() },
        hsmStates      : (settings["${p}hsmStates"] ?: []).collect { it.toString() },
        thermostatId   : settings["${p}tstat"]?.toString(),
        thermostatStates: (settings["${p}tstatStates"] ?: []).collect { it.toString() },
        timeFrom       : settings["${p}timeFrom"]?.toString(),
        timeTo         : settings["${p}timeTo"]?.toString(),
        days           : (settings["${p}days"] ?: []).collect { it.toString() },
        message        : settings["${p}msg"]?.toString()?.trim() ?: null,
        speechDeviceIds: (settings["${p}speech"] ?: []).collect { it.toString() },
        channels       : (settings["${p}channels"] ?: ["speech"]).collect { it.toString() },
        pushDeviceIds  : (settings["${p}pushDevs"] ?: []).collect { it.toString() },
        notifyClear    : settings["${p}notifyClear"] == true,
        urgent         : settings["${p}urgent"] == true,
        updatedAt      : now()
    ]
    def rules = getRules()
    def existing = rules.find { it.id == rid }
    if (existing) {
        rule.createdAt = existing.createdAt
        rules[rules.indexOf(existing)] = rule
    } else {
        rule.createdAt = now()
        rules << rule
    }
    state.rules = rules
    logDebug("synced rule '${name}' (${rid})")
}

def ruleDescription(rule, st) {
    def when = rule.alertWhen == "clearsSoonerThan" ? "clears sooner than" : "stays longer than"
    def s = "${rule.attribute} ${rule.operator} ${rule.value} — alerts if it ${when} ${rule.durationMin} min"
    if (st.breached) s += " — ⚠ BREACHED"
    else if (st.pending) s += " — ⏳ pending"
    return s
}

def ruleStatusText(String rid, Map st) {
    def rule = getRule(rid)
    if (!rule) return "New rule — fill in the sections above, then go back to save it."
    def last = st.lastBreach ? new Date(st.lastBreach as long).format("yyyy-MM-dd HH:mm") : "never"
    return "State: ${st.breached ? '⚠ BREACHED' : st.pending ? '⏳ pending confirmation' : 'ok — watching'}\n" +
        "Last alert: ${last}"
}

// ============================================================================
// REST API
// ============================================================================

def authorized() {
    return state.accessToken && params.access_token == state.accessToken
}

def requireAuth() {
    if (!authorized()) {
        render status: 401, contentType: "application/json",
            data: JsonOutput.toJson([error: "unauthorized", hint: "pass ?access_token=..."])
        return false
    }
    return true
}

def renderJson(obj, int status = 200) {
    render status: status, contentType: "application/json", data: JsonOutput.toJson(obj)
}

// --- command passcode --------------------------------------------------------
// Security-sensitive actions (anything that grants access or changes the
// security posture) require the command passcode configured in the app,
// in addition to the API access token. Fail closed: with no passcode set,
// sensitive actions are refused.

def sensitiveCommandMap() {
    return ["lock"             : ["unlock"],
            "garageDoorControl": ["open"],
            "valve"            : ["open", "close"],
            "alarm"            : ["off", "siren", "strobe", "both"]]
}

def isSensitiveCommand(d, String cmd) {
    def caps = d.capabilities*.name
    return sensitiveCommandMap().any { cap, cmds -> cap in caps && cmd in cmds }
}

def passcodeRequired() {
    return settings.requirePasscode != false
}

/** Returns [ok: true] or [ok: false, error: "..."]. Never logs the passcode. */
def checkPasscode() {
    if (!passcodeRequired()) return [ok: true]
    def configured = settings.securityPasscode?.toString()?.trim()
    if (!configured) {
        return [ok: false,
                error: "refused: set a command passcode in the Muse Bridge app before security-sensitive actions are allowed"]
    }
    def body = request.JSON ?: [:]
    def supplied = (body.passcode ?: params.passcode)?.toString()
    if (supplied == configured) return [ok: true]
    log.warn "Muse Bridge: rejected a security-sensitive action (missing or wrong passcode)"
    return [ok: false, error: "invalid or missing passcode"]
}

def apiHealth() {
    if (!requireAuth()) return
    def rules = getRules()
    renderJson([
        ok      : true,
        app     : "Muse Bridge",
        version : appVersion(),
        hub     : location.name,
        mode    : location.mode,
        devices : allExposedDevices().size(),
        rules   : rules.size(),
        breached: rules.findAll { ruleStateFor(it.id).breached }.collect { it.name },
        time    : new Date().format("yyyy-MM-dd'T'HH:mm:ssZ")
    ])
}

def apiListDevices() {
    if (!requireAuth()) return
    renderJson([devices: allExposedDevices().collect { deviceSummary(it) }])
}

def apiGetDevice() {
    if (!requireAuth()) return
    def d = getDeviceById(params.id)
    if (!d) { renderJson([error: "device not found: ${params.id}"], 404); return }
    renderJson(deviceSummary(d))
}

def apiDeviceCommand() {
    if (!requireAuth()) return
    def d = getDeviceById(params.id)
    if (!d) { renderJson([error: "device not found: ${params.id}"], 404); return }
    def body = request.JSON ?: [:]
    def cmd = (body.command ?: params.command)?.toString()
    if (!cmd) { renderJson([error: "missing 'command'"], 400); return }
    if (!d.hasCommand(cmd)) {
        renderJson([error: "device '${d.displayName}' has no command '${cmd}'",
                    supported: d.supportedCommands*.name], 400)
        return
    }
    if (isSensitiveCommand(d, cmd)) {
        def pc = checkPasscode()
        if (!pc.ok) { renderJson([error: pc.error], 403); return }
    }
    def args = (body.args instanceof List) ? body.args : []
    try {
        def coerced = args.collect { coerceArg(it) }
        log.info "Muse Bridge API: ${d.displayName}.${cmd}(${coerced})"
        def result = d."$cmd"(*coerced)
        renderJson([ok: true, device: d.displayName, command: cmd, args: coerced,
                    result: result?.toString()])
    } catch (e) {
        log.warn "Muse Bridge API: command failed: ${e.message}"
        renderJson([error: "command failed: ${e.message}"], 500)
    }
}

def apiDeviceEvents() {
    if (!requireAuth()) return
    def d = getDeviceById(params.id)
    if (!d) { renderJson([error: "device not found: ${params.id}"], 404); return }
    def max = Math.min(100, Math.max(1, (params.max ?: 20) as int))
    def evts = []
    try {
        evts = d.events(max: max).collect { e ->
            [date           : e.date?.format("yyyy-MM-dd'T'HH:mm:ssZ"),
             name           : e.name,
             value          : e.value,
             unit           : e.unit,
             displayName    : e.displayName,
             descriptionText: e.descriptionText]
        }
    } catch (e) {
        log.warn "Muse Bridge API: could not read events: ${e.message}"
    }
    renderJson([device: d.displayName, events: evts])
}

def apiListModes() {
    if (!requireAuth()) return
    renderJson([current: location.mode, modes: location.modes*.name])
}

def apiGetMode() {
    if (!requireAuth()) return
    renderJson([mode: location.mode])
}

def apiSetMode() {
    if (!requireAuth()) return
    def pc = checkPasscode()
    if (!pc.ok) { renderJson([error: pc.error], 403); return }
    def body = request.JSON ?: [:]
    def mode = (body.mode ?: params.mode)?.toString()
    if (!mode) { renderJson([error: "missing 'mode'"], 400); return }
    if (!(mode in location.modes*.name)) {
        renderJson([error: "unknown mode '${mode}'", modes: location.modes*.name], 400)
        return
    }
    log.info "Muse Bridge API: setting mode to ${mode}"
    setLocationMode(mode)
    renderJson([ok: true, mode: mode])
}

def apiGetHsm() {
    if (!requireAuth()) return
    renderJson([hsm: hsmStatus()])
}

def apiSetHsm() {
    if (!requireAuth()) return
    def pc = checkPasscode()
    if (!pc.ok) { renderJson([error: pc.error], 403); return }
    def body = request.JSON ?: [:]
    def arm = (body.arm ?: params.arm)?.toString()
    if (!(arm in ["armAway", "armHome", "armNight", "disarm"])) {
        renderJson([error: "arm must be one of: armAway, armHome, armNight, disarm"], 400)
        return
    }
    log.info "Muse Bridge API: setting HSM to ${arm}"
    sendLocationEvent(name: "hsmSetArm", value: arm)
    renderJson([ok: true, hsm: arm])
}

def hsmStatus() {
    try {
        return location.hsmStatus
    } catch (e) {
        return null
    }
}

def apiSpeak() {
    if (!requireAuth()) return
    def body = request.JSON ?: [:]
    def text = (body.text ?: params.text)?.toString()?.trim()
    if (!text) { renderJson([error: "missing 'text'"], 400); return }
    def ids = (body.devices instanceof List ? body.devices : [])*.toString()
    if (!ids) ids = defaultSpeechDeviceIds()
    if (!ids) { renderJson([error: "no speech devices configured"], 400); return }
    speakOnDevices(ids, text)
    renderJson([ok: true, devices: ids.size(), text: text])
}

def apiNotify() {
    if (!requireAuth()) return
    def body = request.JSON ?: [:]
    def text = (body.text ?: params.text)?.toString()?.trim()
    if (!text) { renderJson([error: "missing 'text'"], 400); return }
    def ids = (body.devices instanceof List ? body.devices : [])*.toString()
    if (!ids) ids = defaultPushDeviceIds()
    if (!ids) { renderJson([error: "no notification devices configured"], 400); return }
    def sent = 0
    ids.each { id ->
        def d = getDeviceById(id)
        if (d?.hasCommand("deviceNotification")) {
            try { d.deviceNotification(text); sent++ }
            catch (e) { log.warn "Muse Bridge API: push to ${d.displayName} failed: ${e.message}" }
        }
    }
    renderJson([ok: true, devices: sent, text: text])
}

def apiListRules() {
    if (!requireAuth()) return
    renderJson([rules: getRules().collect { ruleSummary(it) }])
}

def apiGetRule() {
    if (!requireAuth()) return
    def rule = getRule(params.id as String)
    if (!rule) { renderJson([error: "rule not found: ${params.id}"], 404); return }
    renderJson(ruleSummary(rule))
}

def apiCreateRule() {
    if (!requireAuth()) return
    def body = request.JSON ?: [:]
    def (rule, error) = buildRuleFromApi("r${now()}", body)
    if (error) { renderJson([error: error], 400); return }
    state.rules = getRules() + [rule]
    initialize()
    log.info "Muse Bridge API: created rule '${rule.name}'"
    renderJson(ruleSummary(rule), 201)
}

def apiUpdateRule() {
    if (!requireAuth()) return
    def existing = getRule(params.id as String)
    if (!existing) { renderJson([error: "rule not found: ${params.id}"], 404); return }
    def body = request.JSON ?: [:]
    // merge: start from the stored rule, overlay supplied fields
    def merged = existing + body
    def (rule, error) = buildRuleFromApi(existing.id, merged)
    if (error) { renderJson([error: error], 400); return }
    rule.createdAt = existing.createdAt
    def rules = getRules()
    rules[rules.indexOf(existing)] = rule
    state.rules = rules
    initialize()
    log.info "Muse Bridge API: updated rule '${rule.name}'"
    renderJson(ruleSummary(rule))
}

def apiDeleteRule() {
    if (!requireAuth()) return
    def rule = getRule(params.id as String)
    if (!rule) { renderJson([error: "rule not found: ${params.id}"], 404); return }
    state.rules = getRules().findAll { it.id != rule.id }
    if (state.ruleState) state.ruleState.remove(rule.id)
    initialize()
    renderJson([ok: true, deleted: rule.id])
}

def apiEnableRule() {
    if (!requireAuth()) return
    setRuleEnabled(params.id as String, true)
}

def apiDisableRule() {
    if (!requireAuth()) return
    setRuleEnabled(params.id as String, false)
}

def setRuleEnabled(String id, boolean enabled) {
    def rule = getRule(id)
    if (!rule) { renderJson([error: "rule not found: ${id}"], 404); return }
    rule.enabled = enabled
    def rules = getRules()
    rules[rules.indexOf(getRule(id))] = rule
    state.rules = rules
    // keep the UI editor in sync if this rule was edited there
    try { app.updateSetting("r_${id}_enabled", [type: "bool", value: enabled]) } catch (e) {}
    initialize()
    renderJson([ok: true, id: id, enabled: enabled])
}

def apiTestRule() {
    if (!requireAuth()) return
    def rule = getRule(params.id as String)
    if (!rule) { renderJson([error: "rule not found: ${params.id}"], 404); return }
    def msg = rule.message?.replace("%rule%", rule.name ?: "")?.replace("%device%", "test device")
        ?.replace("%attribute%", rule.attribute ?: "")?.replace("%value%", rule.value ?: "") ?: "Test announcement for rule ${rule.name}."
    notifyRule(rule, msg)
    renderJson([ok: true, id: rule.id, text: msg])
}

/** Validate + normalize a rule map coming from the API. Returns [rule, error]. */
def buildRuleFromApi(String rid, Map body) {
    def name = body.name?.toString()?.trim()
    if (!name) return [null, "missing 'name'"]
    def trigIds = (body.triggerDevices instanceof List ? body.triggerDevices : [])*.toString()
    if (!trigIds) return [null, "'triggerDevices' must be a non-empty list of device ids"]
    def unknown = trigIds.findAll { !getDeviceById(it) }
    if (unknown) return [null, "unknown device ids: ${unknown}"]
    def attribute = body.attribute?.toString()
    if (!attribute) return [null, "missing 'attribute'"]
    def operator = (body.operator ?: "=").toString()
    if (!(operator in ["=", "!=", ">", "<", ">=", "<="])) return [null, "bad 'operator': ${operator}"]
    def alertWhen = (body.alertWhen ?: "staysLongerThan").toString()
    if (!(alertWhen in ["staysLongerThan", "clearsSoonerThan"])) return [null, "bad 'alertWhen': ${alertWhen}"]
    def tstatId = body.thermostatId?.toString()
    if (tstatId && !getDeviceById(tstatId)) return [null, "unknown thermostatId: ${tstatId}"]
    def modes = (body.modes instanceof List ? body.modes : [])*.toString()
    def badModes = modes.findAll { !(it in location.modes*.name) }
    if (badModes) return [null, "unknown modes: ${badModes}"]
    def hsmStates = (body.hsmStates instanceof List ? body.hsmStates : [])*.toString()
    def badHsm = hsmStates.findAll { !(it in ["armedAway", "armedHome", "armedNight", "disarmed"]) }
    if (badHsm) return [null, "bad hsmStates: ${badHsm} (use armedAway, armedHome, armedNight, disarmed)"]
    def channels = (body.channels instanceof List ? body.channels : ["speech"])*.toString()
    def badChannels = channels.findAll { !(it in ["speech", "push", "muse"]) }
    if (badChannels) return [null, "bad channels: ${badChannels} (use speech, push, muse)"]
    def rule = [
        id              : rid,
        name            : name,
        enabled         : body.enabled != false,
        triggerDevices  : trigIds,
        attribute       : attribute,
        operator        : operator,
        value           : body.value?.toString(),
        durationMin     : (body.durationMin ?: 10) as BigDecimal,
        alertWhen       : alertWhen,
        repeatMin       : (body.repeatMin ?: 0) as BigDecimal,
        modes           : modes,
        hsmStates       : hsmStates,
        thermostatId    : tstatId,
        thermostatStates: (body.thermostatStates instanceof List ? body.thermostatStates : [])*.toString(),
        timeFrom        : body.timeFrom?.toString(),
        timeTo          : body.timeTo?.toString(),
        days            : (body.days instanceof List ? body.days : [])*.toString(),
        message         : body.message?.toString()?.trim() ?: null,
        speechDeviceIds : (body.speechDeviceIds instanceof List ? body.speechDeviceIds : [])*.toString(),
        channels        : channels,
        pushDeviceIds   : (body.pushDeviceIds instanceof List ? body.pushDeviceIds : [])*.toString(),
        notifyClear     : body.notifyClear == true,
        urgent          : body.urgent == true,
        createdAt       : now(),
        updatedAt       : now()
    ]
    return [rule, null]
}

def ruleSummary(rule) {
    def st = ruleStateFor(rule.id)
    return [
        id            : rule.id,
        name          : rule.name,
        enabled       : rule.enabled,
        triggerDevices: triggerDeviceIds(rule).collect { id ->
            def d = getDeviceById(id)
            [id: id, name: d?.displayName]
        },
        attribute     : rule.attribute,
        operator      : rule.operator,
        value         : rule.value,
        durationMin   : rule.durationMin,
        alertWhen     : rule.alertWhen,
        modes         : rule.modes,
        hsmStates     : rule.hsmStates,
        thermostatId  : rule.thermostatId,
        thermostatStates: rule.thermostatStates,
        timeFrom      : rule.timeFrom,
        timeTo        : rule.timeTo,
        days          : rule.days,
        message       : rule.message,
        repeatMin     : rule.repeatMin,
        channels      : rule.channels ?: ["speech"],
        pushDevices   : (rule.pushDeviceIds ?: []).collect { pid ->
            def pd = getDeviceById(pid)
            [id: pid, name: pd?.displayName]
        },
        notifyClear   : rule.notifyClear == true,
        urgent        : rule.urgent == true,
        state         : [
            pending    : st.pending,
            breached   : st.breached,
            lastBreach : st.lastBreach ? new Date(st.lastBreach as long).format("yyyy-MM-dd'T'HH:mm:ssZ") : null
        ]
    ]
}

// ============================================================================
// Device helpers
// ============================================================================

def appVersion() { return "1.2.0" }

def logDebug(String msg) {
    if (settings.logDebug) log.debug "Muse Bridge: ${msg}"
}

def deviceSettingNames() {
    return ["devMaster",
            "swSwitches", "swLevels", "swColors",
            "swLocks", "swGarage", "swValves", "swShades", "swButtons",
            "seContact", "seMotion", "sePresence", "seAccel", "seTamper",
            "enTemp", "enHumidity", "enIllum", "enPower", "enEnergy", "enBattery",
            "saWater", "saSmoke", "saCO",
            "clThermostats",
            "spSpeech", "spAudio", "spMusic",
            "alSirens",
            "ntPhones"]
}

def allExposedDevices() {
    def all = []
    deviceSettingNames().each { n ->
        def v = settings[n]
        if (v) all.addAll(v instanceof List ? v : [v])
    }
    return all.unique { it.id }
}

def getDeviceById(id) {
    if (!id) return null
    def s = id.toString()
    return allExposedDevices().find { it.id.toString() == s }
}

def speechDevices() {
    def all = []
    ["spSpeech", "spAudio", "spMusic"].each { n ->
        def v = settings[n]
        if (v) all.addAll(v instanceof List ? v : [v])
    }
    return all.unique { it.id }
}

def deviceSummary(d) {
    return [
        id          : d.id.toString(),
        name        : d.name,
        label       : d.label,
        displayName : d.displayName,
        capabilities: d.capabilities*.name,
        attributes  : currentAttributeMap(d),
        commands    : d.supportedCommands*.name
    ]
}

/** Coerce a JSON-decoded argument to the most useful Groovy type for device commands. */
def coerceArg(arg) {
    if (arg instanceof Map || arg instanceof List) return arg
    if (arg instanceof Boolean || arg instanceof Number) return arg
    def s = arg?.toString()?.trim()
    if (s == null) return null
    if (s.equalsIgnoreCase("true")) return true
    if (s.equalsIgnoreCase("false")) return false
    if (s ==~ /-?\d+/) {
        try { return s as Integer } catch (e) { return s as Long }
    }
    if (s ==~ /-?\d+\.\d+/) {
        try { return s as BigDecimal } catch (e) { /* fall through */ }
    }
    return s
}

def currentAttributeMap(d) {
    def m = [:]
    supportedAttrNames(d).each { n ->
        try { m[n] = d.currentValue(n) } catch (e) { /* ignore */ }
    }
    return m
}

def supportedAttrNames(d) {
    try {
        def names = d.supportedAttributes?.collect { it.name }
        if (names) return names.unique()
    } catch (e) { /* fall through to probe list */ }
    // Fallback: probe common attributes (older/alternate firmwares).
    return ["switch", "level", "hue", "saturation", "colorTemperature", "colorMode", "colorName",
            "contact", "motion", "presence", "acceleration", "tamper", "temperature", "humidity",
            "illuminance", "power", "energy", "battery", "voltage",
            "water", "smoke", "carbonMonoxide",
            "lock", "door", "valve", "windowShade", "position", "buttonNumber", "pushed", "held", "doubleTapped",
            "thermostatMode", "thermostatOperatingState", "thermostatFanMode",
            "heatingSetpoint", "coolingSetpoint", "thermostatSetpoint", "thermostatThreshold",
            "alarm", "mute", "volume", "trackDescription", "status"]
        .findAll { n -> try { d.hasAttribute(n) } catch (e) { false } }
}

// --- option builders for the UI ---------------------------------------------

def deviceOptions() {
    return allExposedDevices().sort { it.displayName }.collectEntries { [(it.id.toString()): it.displayName] }
}

def attributeOptions(trigIds) {
    def attrs = [] as LinkedHashSet
    (trigIds ?: []).each { id ->
        def d = getDeviceById(id?.toString())
        if (d) attrs.addAll(supportedAttrNames(d))
    }
    if (!attrs) {
        attrs.addAll(["switch", "level", "contact", "motion", "presence", "acceleration", "tamper",
                      "temperature", "humidity", "illuminance", "power", "energy", "battery",
                      "water", "smoke", "carbonMonoxide", "lock", "door", "valve", "windowShade",
                      "thermostatMode", "thermostatOperatingState", "heatingSetpoint", "coolingSetpoint", "alarm"])
    }
    return attrs.sort().collectEntries { [(it): it] }
}

def modeOptions() {
    return (location.modes*.name ?: []).sort().collectEntries { [(it): it] }
}

def thermostatOptions() {
    def t = settings.clThermostats
    def list = t ? (t instanceof List ? t : [t]) : []
    return list.sort { it.displayName }.collectEntries { [(it.id.toString()): it.displayName] }
}

def speechOptions() {
    return speechDevices().sort { it.displayName }.collectEntries { [(it.id.toString()): it.displayName] }
}

def pushDevices() {
    def v = settings.ntPhones
    def all = v ? (v instanceof List ? v : [v]) : []
    return all.unique { it.id }
}

def defaultPushDeviceIds() {
    return pushDevices().collect { it.id.toString() }
}

def pushOptions() {
    return pushDevices().sort { it.displayName }.collectEntries { [(it.id.toString()): it.displayName] }
}
