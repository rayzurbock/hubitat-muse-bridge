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
    page(name: "linkPage", title: "Link with Muse")
    page(name: "exposePage", title: "Choose Exposed Devices")
    page(name: "healthPage", title: "Device Health")
    page(name: "speakerPage", title: "Choose Speakers")
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
            href(name: "healthHref", page: "healthPage",
                title: "Device health",
                description: "Low-battery and inactive-device push alerts")
            href(name: "apiHref", page: "apiPage",
                title: "API Access",
                description: state.accessToken ? "Endpoints and token ready" : "OAuth token missing — see page")
            href(name: "linkHref", page: "linkPage",
                title: "Link with Muse",
                description: state.accessToken ? "Copy-paste setup message for your assistant" : "Needs OAuth first — see API Access")
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
        section("Donations") {
            paragraph("Muse Bridge is provided to the community for free. " +
                "It takes a lot of time to build and support any complex app. " +
                "If you wish to support the time and effort put into development " +
                "(including the costs of the AI services it integrates with), " +
                "you may submit a donation with one of the following:<br>" +
                "<ul>" +
                "<li><b>Cash.me</b> = <a target='_blank' href='https://cash.me/\$Lowrance'>https://cash.me/\$Lowrance</a> (use a debit card, it's free for both of us)</li>" +
                "<li><b>Venmo</b> = <a target='_blank' href='https://venmo.com/code?user_id=2603208862072832399'>@BrianLowrance</a></li>" +
                "<li><b>Paypal.me</b> = <a target='_blank' href='https://paypal.me/brianlowrance'>https://paypal.me/brianlowrance</a> (they take a little since the account is set up as a business account)</li>" +
                "</ul>")
        }
    }
}

def devicesPage() {
    dynamicPage(name: "devicesPage", title: "Devices Exposed to the API", nextPage: "mainPage") {
        section() {
            paragraph("The app can only use devices you authorize below. By default every " +
                "authorized device is exposed to the API and rules — open the picker " +
                "to narrow it down.")
        }
        section("Authorize devices") {
            paragraph("<b>Step 1</b> — let the app see your devices. Open the master list, " +
                "Select All, Done.")
            input "devMaster", "capability.*", title: "All devices (master list)", multiple: true, required: false, showFilter: true
        }
        section("Exposed devices") {
            def exposed = exposedDeviceIds().size()
            def total = exposureUniverse().size()
            href(name: "exposeHref", page: "exposePage",
                title: "Choose exposed devices",
                description: "<b>Step 2 (optional)</b> — ${exposed} of ${total} authorized devices exposed; tap to uncheck ones you don't need")
        }
        section("Speech & announcements") {
            paragraph("Alert rules speak through these devices, using the <b>speak</b> command " +
                "when available and <b>playTextAndResume</b>/<b>playText</b> otherwise. " +
                "Speakers are listed most-capable first — prefer ones with full volume " +
                "support if you use announcement volumes.")
            href(name: "speakerHref", page: "speakerPage",
                title: "Choose speakers",
                description: speakerDeviceIds() ? "${speakerDeviceIds().size()} speaker${speakerDeviceIds().size() == 1 ? '' : 's'} selected" : "Tap to choose speakers")
        }
        section("Announcement volume") {
            paragraph("Control how loud announcements play, regardless of what the " +
                "speaker is currently set to.")
            input "speechVolume", "number", title: "Announcement volume (0-100, blank = leave volume alone)",
                required: false, range: "0..100"
            input "speechMinVolume", "number", title: "Minimum announcement volume (0-100, blank = none)",
                required: false, range: "0..100",
                description: "If a speaker is set lower than this, announcements play at least this loud"
            input "restoreVolume", "bool", title: "Restore speaker volume after announcements",
                defaultValue: true
            def sDevs = speechDevices()
            if (sDevs) {
                def rows = sDevs.collect { d ->
                    def sup = deviceVolumeSupport(d)
                    def note = (!sup.canSet) ? "no volume commands — announcements only" :
                               (!sup.reports) ? "volume control, but no level reporting — restore unavailable" :
                               "full volume support"
                    "• <b>${d.displayName}</b> — ${note}"
                }.join("<br>")
                paragraph("<b>Speaker volume support:</b><br>${rows}")
            } else {
                paragraph("Select speech devices above and this will show which ones " +
                    "support volume control and restore.")
            }
        }
        section("Phone / push notifications") {
            paragraph("Each Hubitat mobile-app device is one phone or tablet. " +
                "Rules can push text alerts to specific phones, so a text alert " +
                "can go to one person instead of everyone.")
            input "ntPhones", "capability.notification", title: "Notification devices", multiple: true, required: false, showFilter: true
        }
    }
}

def exposePage() {
    ensureActivityCache()
    if (state.exposureMigrated && !state.exposureSeeded) {
        // Visitor of the pre-1.5.2 picker: preserve whatever they selected (possibly nothing).
        state.exposureSeeded = true
    }
    if (!state.exposureSeeded) {
        // First visit: check everything currently authorized, so the checklist
        // matches reality from the start.
        app.updateSetting("expDevices", (exposureUniverse()*.id*.toString()) as List)
        state.exposureSeeded = true
    }
    def universeIds = (exposureUniverse()*.id*.toString()) as Set
    def exposedIds = exposedDeviceIds()
    def missing = universeIds - exposedIds
    dynamicPage(name: "exposePage", title: "Choose Exposed Devices", nextPage: "devicesPage") {
        section("Find devices") {
            paragraph("Filters only change what's <b>listed</b> — never your selection. " +
                "Check devices to expose them to the API and rules; uncheck to remove them. " +
                "Already-selected devices always appear at the top, even when a filter hides them. " +
                "Labels show when each device last reported.")
            input "expSearch", "text", title: "Search by name", required: false, submitOnChange: true
            input "expCap", "enum", title: "Device type", required: false, submitOnChange: true,
                options: exposureCapOptions(), description: "Narrow by capability"
            input "expActiveDays", "number",
                title: "Hide devices quiet for longer than N days (0 = show all)",
                defaultValue: 0, required: true, range: "0..365", submitOnChange: true,
                description: "Opt-in: hide dead devices to make big lists manageable"
            if (missing) paragraph("<b>${missing.size()} authorized device${missing.size() == 1 ? ' is' : 's are'} " +
                "not exposed.</b> Find ${missing.size() == 1 ? 'it' : 'them'} below and check " +
                "${missing.size() == 1 ? 'it' : 'them'} to include ${missing.size() == 1 ? 'it' : 'them'}.")
        }
        section("Exposed devices (${exposedIds.size()} selected of ${universeIds.size()} authorized)") {
            input "expDevices", "enum", title: "Checked devices are exposed to the API and rules",
                multiple: true, required: false, options: exposureOptions(), submitOnChange: true
        }
    }
}

def speakerPage() {
    state.speakerMigrated = true
    dynamicPage(name: "speakerPage", title: "Choose Speakers", nextPage: "devicesPage") {
        section("Speakers") {
            paragraph("Most capable first. <b>Full</b> = voice + volume + restore after " +
                "announcements. <b>Voice + volume</b> = volume works but can't be restored. " +
                "<b>Voice only</b> = announcements play, volume settings are ignored.")
            input "spkFullOnly", "bool", title: "Only show fully-capable speakers",
                defaultValue: false, submitOnChange: true,
                description: "Hides voice-only and no-restore speakers from the list below"
            input "spkDevices", "enum", title: "Speakers for announcements",
                multiple: true, required: false, options: speakerOptions(), submitOnChange: true
        }
    }
}

// 3 = full (voice + volume + restore), 2 = voice + volume, 1 = voice only
def speakerTier(d) {
    def vol = deviceVolumeSupport(d)
    if (vol.canSet && vol.reports) return 3
    if (vol.canSet) return 2
    return 1
}

def speakerTierLabel(d) {
    switch (speakerTier(d)) {
        case 3: return "full: voice + volume + restore"
        case 2: return "voice + volume (no restore)"
        default: return "voice only"
    }
}

def speakerCandidates() {
    def cands = allVisibleDevices().findAll { d ->
        try {
            d.hasCommand("speak") || d.hasCommand("playText") || d.hasCommand("playTextAndResume") ||
            d.hasCapability("SpeechSynthesis") || d.hasCapability("AudioNotification") || d.hasCapability("MusicPlayer")
        } catch (e) { false }
    }.unique { it.id.toString() }
    if (settings.spkFullOnly == true) cands = cands.findAll { speakerTier(it) == 3 }
    return cands.sort { a, b ->
        (speakerTier(b) <=> speakerTier(a)) ?: (a.displayName?.toLowerCase() <=> b.displayName?.toLowerCase())
    }
}

def speakerOptions() {
    return speakerCandidates().collectEntries { d ->
        [(d.id.toString()): "${d.displayName} — ${speakerTierLabel(d)}"]
    }
}

def speakerDeviceIds() {
    if (settings.spkDevices != null) return (settings.spkDevices*.toString()) as Set
    if (state.speakerMigrated) return [] as Set
    // Upgrade path: seed from the old per-capability speaker pickers.
    def ids = [] as Set
    ["spSpeech", "spAudio", "spMusic"].each { k ->
        settingDeviceList(k).each { d -> ids << d.id.toString() }
    }
    return ids
}

def healthPage() {
    ensureActivityCache()
    dynamicPage(name: "healthPage", title: "Device Health", nextPage: "mainPage") {
        section("Low battery alerts") {
            paragraph("Warn when a device's battery runs low. Battery-powered devices " +
                "that never report a battery level are skipped.")
            input "healthBattery", "bool", title: "Notify when batteries run low",
                defaultValue: true, submitOnChange: true
            if (settings.healthBattery != false) {
                input "healthBatteryThreshold", "number", title: "Warn when battery is at or below (%)",
                    defaultValue: 20, required: true, range: "1..100"
                input "healthBatteryIgnore", "enum", title: "Ignore these devices",
                    multiple: true, required: false, options: deviceOptions(),
                    description: "Checked devices never trigger battery alerts"
            }
        }
        section("Inactive device alerts") {
            paragraph("Warn when a device goes quiet — no events at all, not just " +
                "no state changes.")
            input "healthInactive", "bool", title: "Notify when devices go quiet",
                defaultValue: true, submitOnChange: true
            if (settings.healthInactive != false) {
                input "healthInactiveDays", "number", title: "Warn when quiet for N days",
                    defaultValue: 7, required: true, range: "1..365"
                input "healthInactiveIgnore", "enum", title: "Ignore these devices",
                    multiple: true, required: false, options: deviceOptions(),
                    description: "Checked devices never trigger inactivity alerts"
            }
        }
        section("Delivery") {
            paragraph("Health alerts are <b>text push notifications</b> to your Hubitat mobile " +
                "app devices — one summary per day listing everything needing attention. " +
                "They never play on speakers.")
            input "healthTime", "time", title: "Run the health check daily at",
                required: false, description: "Leave blank for 9:00 AM"
            def problems = healthProblems()
            paragraph(problems ?
                "<b>Right now:</b><br>• " + problems.join("<br>• ") :
                "<b>Right now:</b> all clear.")
        }
    }
}

// --- device health engine -----------------------------------------------------

def healthProblems() {
    ensureActivityCache()
    def problems = []
    if (settings.healthBattery != false) {
        def threshold = (settings.healthBatteryThreshold != null ? settings.healthBatteryThreshold as int : 20)
        def ignore = (settings.healthBatteryIgnore ?: [])*.toString()
        allExposedDevices().each { d ->
            if (ignore.contains(d.id.toString())) return
            try {
                if (!d.hasAttribute("battery")) return
                def v = d.currentValue("battery")
                if (v == null) return
                def n = v.toString().isNumber() ? (v as BigDecimal) : null
                if (n != null && n <= threshold) problems << "${d.displayName}: battery ${n.intValue()}%"
            } catch (e) {
                logDebug("battery check failed for ${d.displayName}: ${e.message}")
            }
        }
    }
    if (settings.healthInactive != false) {
        def days = (settings.healthInactiveDays != null ? settings.healthInactiveDays as int : 7)
        def ignore = (settings.healthInactiveIgnore ?: [])*.toString()
        allExposedDevices().each { d ->
            if (ignore.contains(d.id.toString())) return
            def inactive = deviceInactiveDays(d)
            if (inactive == null) problems << "${d.displayName}: never reported any activity"
            else if (inactive >= days) problems << "${d.displayName}: no activity for ${inactive} days"
        }
    }
    return problems
}

def deviceHealthCheck() {
    def problems = healthProblems()
    if (!problems) {
        logDebug("device health check: all clear")
        return
    }
    log.info "Muse Bridge device health: ${problems.size()} issue(s): ${problems.join('; ')}"
    def phones = settingDeviceList("ntPhones")
    if (!phones) {
        log.warn "Muse Bridge: health check found problems but no notification devices are configured"
        return
    }
    // Split into ~200-char chunks so long lists survive push limits.
    def chunks = []
    def current = ""
    problems.each { p ->
        def add = (current ? "; " : "") + p
        if ((current + add).length() > 200 && current) {
            chunks << current
            current = p
        } else {
            current += add
        }
    }
    if (current) chunks << current
    phones.each { ph ->
        chunks.each { c ->
            try { ph.deviceNotification("Muse Bridge health: ${c}") }
            catch (e) { log.warn "Muse Bridge: health push failed: ${e.message}" }
        }
    }
}

def scheduleHealthCheck() {
    unschedule("deviceHealthCheck")
    if (settings.healthBattery == false && settings.healthInactive == false) return
    def hour = 9
    def minute = 0
    try {
        if (settings.healthTime) {
            def cal = Calendar.getInstance()
            cal.setTime(settings.healthTime as Date)
            hour = cal.get(Calendar.HOUR_OF_DAY)
            minute = cal.get(Calendar.MINUTE)
        }
    } catch (e) {
        log.warn "Muse Bridge: bad health check time, using 09:00"
    }
    schedule("0 ${minute} ${hour} * * ?", "deviceHealthCheck")
    logDebug("device health check scheduled daily at ${String.format('%02d', hour)}:${String.format('%02d', minute)}")
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
            input "${p}volume", "number", title: "Volume for this rule's announcements (0-100, blank = app default)",
                required: false, range: "0..100",
                description: "Handy for making urgent rules louder than the rest"
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

def linkPage() {
    ensureToken()
    dynamicPage(name: "linkPage", title: "Link with Muse", nextPage: "mainPage") {
        section("Monitoring preferences") {
            paragraph("These tell your assistant how closely to watch the hub. " +
                "They're baked into the setup message below, so any assistant " +
                "can configure itself from your choices.")
            input "linkPollMinutes", "number", title: "Poll the hub every (minutes)",
                defaultValue: 5, required: true
            input "linkDownThreshold", "number",
                title: "Message me after this many consecutive failed polls",
                defaultValue: 3, required: true
            input "linkDownWindow", "number", title: "…within the last X minutes",
                defaultValue: 15, required: true
        }
        section("Setup — copy, paste, send") {
            def base = cloudBase()
            if (!state.accessToken || !base) {
                paragraph("Enable OAuth first (see the API Access page), then come back here.")
            } else {
                paragraph("<b>1.</b> Copy the whole message below.<br>" +
                    "<b>2.</b> Paste it to your assistant (Muse) in chat and send it.<br>" +
                    "<b>3.</b> The assistant verifies the connection and starts monitoring.<br><br>" +
                    "Keep the token private — anyone with it can read and control your devices.<br><br>" +
                    "<i>Muse Bridge was designed by Rayzurbock and coded by " +
                    "<a target='_blank' href='https://muse.ai'>Muse</a>, " +
                    "Meta's personal AI assistant. The API is open, so other assistants can use it too.</i>")
                paragraph("<b>Copy everything below this line:</b><br><br>" +
                    linkMessage(base).replace("\n", "<br>"))
            }
        }
    }
}

/** Pre-filled, copy-paste setup message for the user's assistant. */
def linkMessage(String base) {
    def token = state.accessToken
    def pollMin = settings.linkPollMinutes ?: 5
    def threshold = settings.linkDownThreshold ?: 3
    def windowMin = settings.linkDownWindow ?: 15
    return """Hi Muse — please link with my Hubitat hub through the Muse Bridge app:

Cloud API base URL: ${base}
Access token: ${token}

Please:
- Poll the hub every ${pollMin} minutes: GET ${base}/health?access_token=${token}
- If the hub fails to respond ${threshold} times in a row within ${windowMin} minutes, message me in chat immediately (once per outage), and tell me when it recovers. Distinguish timeouts from HTTP 401 (revoked token).
- Each poll, also GET ${base}/rules?access_token=${token} — if any rule with the "muse" notification channel is newly breached, message me; lead with a siren emoji for urgent ones. Stay silent otherwise.
- I may ask you to check device states, run commands, change modes, arm/disarm HSM, or create alert rules. Security-sensitive actions need my command passcode as the "passcode" field — ask me for it when needed and never store it unless I say so.
- Confirm the link by calling /health and telling me what you see.

— via Muse Bridge v${appVersion()} by Rayzurbock (https://github.com/Rayzurbock/hubitat-muse-bridge)""".strip()
}

/** The cloud base URL for this app's endpoints (no trailing path). */
def cloudBase() {
    try {
        def u = fullApiServerUrl("x").toString() // documented: base + "/" + path
        return u.substring(0, u.length() - 2)   // strip the trailing "/x"
    } catch (e) {
        log.warn "Muse Bridge: could not build cloud base URL: ${e.message}"
        return null
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
    scheduleHealthCheck()
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
    speakOnDevices(ids, text, rule.volume != null ? rule.volume as BigDecimal : null)
}

def speakOnDevices(List ids, String text, BigDecimal volume = null) {
    ids.each { id ->
        def d = getDeviceById(id)
        if (!d) {
            log.warn "Muse Bridge: speech device id ${id} not found"
            return
        }
        try {
            def vol = effectiveVolume(d, volume)
            def prevVol = null
            def restoreToken = null
            if (vol != null) {
                prevVol = currentVolumeOf(d)
                setDeviceVolume(d, vol)
                if (settings.restoreVolume != false && prevVol != null && prevVol != vol) {
                    // Remember the pre-announcement volume; a later announcement
                    // supersedes this restore via its own token.
                    restoreToken = now()
                    if (!state.volRestore) state.volRestore = [:]
                    state.volRestore[id.toString()] = [level: prevVol, at: restoreToken]
                }
            }
            if (d.hasCommand("speak")) {
                d.speak(text)
            } else if (d.hasCommand("playTextAndResume")) {
                d.playTextAndResume(text) // music keeps playing after the announcement
            } else if (d.hasCommand("playText")) {
                d.playText(text)
            } else {
                log.warn "Muse Bridge: ${d.displayName} has no speak/playText command"
            }
            if (restoreToken) {
                // Rough TTS estimate (~12 chars/sec) plus a buffer, minimum 15s.
                def delay = Math.max(15, ((text.length() / 12) as int) + 5)
                runIn(delay, "restoreDeviceVolume",
                    [data: [deviceId: id.toString(), at: restoreToken], overwrite: false])
            }
            logDebug("announced on ${d.displayName}${vol != null ? " at volume ${vol}" : ""}: ${text}")
        } catch (e) {
            log.warn "Muse Bridge: failed to announce on ${d.displayName}: ${e.message}"
        }
    }
}

/** Resolve the volume for an announcement: explicit override, app default, or minimum. Null = don't touch. */
def effectiveVolume(d, BigDecimal override) {
    if (override != null) return clampVolume(override)
    def v = settings.speechVolume
    if (v != null) return clampVolume(v as BigDecimal)
    def minV = settings.speechMinVolume
    if (minV != null) {
        def cur = currentVolumeOf(d)
        if (cur != null && cur < (minV as BigDecimal)) return clampVolume(minV as BigDecimal)
    }
    return null
}

def currentVolumeOf(d) {
    try {
        def v = d.currentValue("level")
        return v != null ? v as BigDecimal : null
    } catch (e) {
        return null
    }
}

def setDeviceVolume(d, BigDecimal vol) {
    def v = clampVolume(vol) as int
    if (d.hasCommand("setVolume")) {
        d.setVolume(v)
    } else if (d.hasCommand("setLevel")) {
        d.setLevel(v)
    } else {
        logDebug("Muse Bridge: ${d.displayName} has no volume command, skipping")
    }
}

def clampVolume(v) {
    def n = v as BigDecimal
    if (n < 0) return 0
    if (n > 100) return 100
    return n
}

/** What volume features a speech device supports. Restore needs both. */
def deviceVolumeSupport(d) {
    def canSet = d.hasCommand("setVolume") || d.hasCommand("setLevel")
    def reports = false
    try {
        reports = d.supportedAttributes?.any { it.name == "level" } ?: (d.currentValue("level") != null)
    } catch (e) {
        try { reports = d.currentValue("level") != null } catch (e2) { reports = false }
    }
    return [canSet: canSet, reports: reports]
}

def restoreDeviceVolume(data) {
    def devId = data?.deviceId?.toString()
    def rec = state.volRestore?.get(devId)
    if (!rec || rec.at != data?.at) return // superseded by a newer announcement
    state.volRestore.remove(devId)
    def d = getDeviceById(devId)
    if (!d) return
    try {
        setDeviceVolume(d, rec.level as BigDecimal)
        logDebug("Muse Bridge: restored ${d.displayName} volume to ${rec.level}")
    } catch (e) {
        log.warn "Muse Bridge: volume restore failed for ${d.displayName}: ${e.message}"
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
        volume         : settings["${p}volume"] == null ? null : clampVolume(settings["${p}volume"] as BigDecimal),
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
        project : "https://github.com/Rayzurbock/hubitat-muse-bridge",
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
    def volume = null
    if (body.volume != null || params.volume != null) {
        try { volume = clampVolume((body.volume ?: params.volume) as BigDecimal) }
        catch (e) { renderJson([error: "bad 'volume' (0-100)"], 400); return }
    }
    def ids = (body.devices instanceof List ? body.devices : [])*.toString()
    if (!ids) ids = defaultSpeechDeviceIds()
    if (!ids) { renderJson([error: "no speech devices configured"], 400); return }
    speakOnDevices(ids, text, volume)
    renderJson([ok: true, devices: ids.size(), text: text, volume: volume])
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
    def volume = null
    if (body.volume != null) {
        try { volume = clampVolume(body.volume as BigDecimal) }
        catch (e) { return [null, "bad 'volume': ${body.volume} (0-100)"] }
    }
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
        volume          : volume,
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
        volume        : rule.volume,
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

def appVersion() { return "1.5.2" }

def logDebug(String msg) {
    if (settings.logDebug) log.debug "Muse Bridge: ${msg}"
}

// Setting keys of the pre-1.5.0 per-capability pickers. Kept for upgrade
// migration: their selections seed the exposure picker below.
def legacyExposureKeys() {
    return ["swSwitches", "swLevels", "swColors",
            "swLocks", "swGarage", "swValves", "swShades", "swButtons",
            "seContact", "seMotion", "sePresence", "seAccel", "seTamper",
            "enTemp", "enHumidity", "enIllum", "enPower", "enEnergy", "enBattery",
            "saWater", "saSmoke", "saCO",
            "clThermostats",
            "alSirens"]
}

def settingDeviceList(String name) {
    def v = settings[name]
    return v instanceof List ? v : (v ? [v] : [])
}

/** Every device the app is allowed to see: master list + legacy picker selections. */
def exposureUniverse() {
    def seen = [] as Set
    def all = []
    (["devMaster"] + legacyExposureKeys()).each { k ->
        settingDeviceList(k).each { d ->
            if (d && seen.add(d.id.toString())) all << d
        }
    }
    return all
}

/** The device IDs exposed to the API and rules (strings).
 *  Default: everything authorized. Opening the picker snapshots the current
 *  list into settings.expDevices, after which the checklist is the truth. */
def exposedDeviceIds() {
    if (state.exposureSeeded && settings.expDevices != null) return (settings.expDevices*.toString()) as Set
    if (state.exposureSeeded) return [] as Set
    return (exposureUniverse()*.id*.toString()) as Set
}

/** Devices the app can address anywhere (exposure universe + speech/siren/phone inputs). */
def allVisibleDevices() {
    def seen = [] as Set
    def all = []
    (["devMaster"] + legacyExposureKeys() +
     ["spSpeech", "spAudio", "spMusic", "alSirens", "ntPhones"]).each { k ->
        settingDeviceList(k).each { d ->
            if (d && seen.add(d.id.toString())) all << d
        }
    }
    return all
}

// --- device activity (last-event tracking, cached) ---------------------------

def ensureActivityCache() {
    if (!(state.activityCache instanceof Map)) state.activityCache = [:]
    // Refresh at most every 6 hours; lookups below repopulate lazily.
    if (!state.activityCacheAt || now() - (state.activityCacheAt as Long) > 6 * 3600 * 1000) {
        state.activityCache = [:]
        state.activityCacheAt = now()
    }
}

/** Epoch ms of the device's most recent event (within the last year), or null. */
def deviceLastEventMs(d) {
    if (!(state.activityCache instanceof Map)) state.activityCache = [:]
    def key = d.id.toString()
    if (state.activityCache.containsKey(key)) return state.activityCache[key]
    def ts = null
    try {
        def yearAgo = new Date(now() - 365L * 86400000L)
        def evts = d.eventsSince(yearAgo, [max: 1])
        def latest = evts?.max { it?.date?.time ?: 0 }
        ts = latest?.date?.time
    } catch (e) {
        logDebug("activity lookup failed for ${d.displayName}: ${e.message}")
    }
    state.activityCache[key] = ts
    return ts
}

/** Whole days since the device last reported; null = never (within the last year). */
def deviceInactiveDays(d) {
    def ts = deviceLastEventMs(d)
    if (ts == null) return null
    return ((now() - (ts as Long)) / 86400000L) as int
}

def activityLabel(d) {
    def days = deviceInactiveDays(d)
    if (days == null) return "(never)"
    if (days == 0) return "(today)"
    if (days == 1) return "(yesterday)"
    return "(${days}d)"
}

// --- exposure picker ----------------------------------------------------------

def exposureCapOptions() {
    def caps = [] as Set
    exposureUniverse().each { d ->
        try { d.capabilities?.each { caps << it.name } } catch (e) { /* ignore */ }
    }
    return caps.sort().collectEntries { [(it): it] }
}

def exposureCandidates() {
    def q = settings.expSearch?.toString()?.trim()?.toLowerCase()
    def cap = settings.expCap?.toString()
    def days = (settings.expActiveDays != null ? settings.expActiveDays as int : 0)
    return exposureUniverse().findAll { d ->
        if (q && !(d.displayName?.toLowerCase()?.contains(q))) return false
        if (cap) {
            def has = false
            try { has = d.capabilities?.any { it.name == cap } } catch (e) { /* ignore */ }
            if (!has) return false
        }
        if (days > 0) {
            def inactive = deviceInactiveDays(d)
            if (inactive == null || inactive > days) return false
        }
        return true
    }.sort { it.displayName?.toLowerCase() }
}

def exposureOptions() {
    def opts = [:]
    // Currently-selected devices always appear (checked), so they can be
    // unchecked even when the filters hide them.
    exposedDeviceIds().each { id ->
        def d = allVisibleDevices().find { it.id.toString() == id }
        if (d) opts[id] = "${d.displayName} ${activityLabel(d)}"
    }
    exposureCandidates().each { d ->
        def id = d.id.toString()
        if (!opts.containsKey(id)) opts[id] = "${d.displayName} ${activityLabel(d)}"
    }
    return opts
}

def allExposedDevices() {
    def ids = exposedDeviceIds()
    return exposureUniverse().findAll { ids.contains(it.id.toString()) }
}

def getDeviceById(id) {
    if (!id) return null
    def s = id.toString()
    return allVisibleDevices().find { it.id.toString() == s }
}

def speechDevices() {
    def ids = speakerDeviceIds()
    return allVisibleDevices().findAll { ids.contains(it.id.toString()) }
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
    def list = allExposedDevices().findAll {
        try { it.capabilities?.any { c -> c.name == "Thermostat" } } catch (e) { false }
    }
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
