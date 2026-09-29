package dev.accessaudit.core.risk

import dev.accessaudit.core.model.AccessKind
import dev.accessaudit.core.model.Finding
import dev.accessaudit.core.model.InstallSource
import dev.accessaudit.core.model.OpTally
import dev.accessaudit.core.model.Severity
import dev.accessaudit.core.ops.OpCatalog
import dev.accessaudit.core.ops.Permissions

private const val P = "android.permission."

private fun formatAge(nowMs: Long, thenMs: Long): String {
    if (thenMs <= 0) return "unknown time"
    val delta = nowMs - thenMs
    if (delta < 0) return "in the future (clock skew?)"
    val minutes = delta / 60_000
    return when {
        minutes < 1 -> "seconds ago"
        minutes < 60 -> "${minutes}m ago"
        minutes < 1440 -> "${minutes / 60}h ago"
        else -> "${minutes / 1440}d ago"
    }
}

/**
 * Accessing the camera, microphone, screen or precise location with no visible
 * UI and no foreground service.
 *
 * This is the highest-value signal the platform gives us for free, and the one
 * users most often care about: it is the difference between "the app I opened
 * used the mic" and "something recorded me while my phone was in my pocket".
 */
public object BackgroundSensitiveAccessRule : UserAppRule() {
    override val id: String = "BG_SENSITIVE_ACCESS"
    override val description: String =
        "Camera, microphone, screen capture or precise location used with no visible UI"

    private val WATCHED = setOf(AccessKind.CAPTURE, AccessKind.LOCATION)

    override fun check(input: RuleInput): List<Finding> {
        val now = input.context.nowMs
        return input.tallies
            .filter { it.kind in WATCHED && it.backgroundCount > 0 && OpCatalog.sensitivityOf(it.op) >= 7 }
            .map { tally ->
                Finding(
                    ruleId = id,
                    packageName = input.app.packageName,
                    title = "${OpCatalog.labelOf(tally.op)} used in the background",
                    // A single background hit can be legitimate (a widget refresh,
                    // a geofence). A sustained pattern is what earns HIGH.
                    severity = if (tally.backgroundCount >= 3 || tally.totalDurationMs > 60_000) {
                        Severity.HIGH
                    } else {
                        Severity.MEDIUM
                    },
                    rationale = "The app used ${OpCatalog.labelOf(tally.op)} while it had no " +
                        "activity on screen and no foreground service, so there was no on-screen " +
                        "indication this was happening.",
                    evidence = buildList {
                        add("${tally.backgroundCount} of ${tally.totalCount} accesses were in the background")
                        add("last background access ${formatAge(now, tally.lastBackgroundAccessMs)}")
                        if (tally.totalDurationMs > 0) {
                            add("held for ${tally.totalDurationMs / 1000}s in total")
                        }
                    },
                    recommendation = "Open Settings > Apps > ${input.app.label} > Permissions and " +
                        "restrict ${OpCatalog.labelOf(tally.op)} to \"while using the app\".",
                    kinds = setOf(tally.kind),
                    lastSeenMs = tally.lastBackgroundAccessMs,
                )
            }
    }
}

/** Reading data while in the background, which is how silent collection looks. */
public object BackgroundDataHarvestRule : UserAppRule() {
    override val id: String = "BG_DATA_HARVEST"
    override val description: String = "Contacts, SMS, call log or all-files access read in the background"

    private val WATCHED = setOf(AccessKind.READ, AccessKind.MESSAGING)

    override fun check(input: RuleInput): List<Finding> {
        val now = input.context.nowMs
        val offenders = input.tallies.filter {
            it.kind in WATCHED && it.backgroundCount > 0 && OpCatalog.sensitivityOf(it.op) >= 8
        }
        if (offenders.isEmpty()) return emptyList()
        return listOf(
            Finding(
                ruleId = id,
                packageName = input.app.packageName,
                title = "Personal data read in the background",
                severity = if (offenders.size >= 2) Severity.HIGH else Severity.MEDIUM,
                rationale = "The app read high-sensitivity personal data with no UI on screen. " +
                    "Background reads cannot be correlated with anything the user did, which is " +
                    "the shape of automated collection rather than a user action.",
                evidence = offenders.map { tally ->
                    "${OpCatalog.labelOf(tally.op)}: ${tally.backgroundCount} background reads, " +
                        "last ${formatAge(now, tally.lastBackgroundAccessMs)}"
                },
                recommendation = "Check whether the app has a feature that needs this offline. " +
                    "If not, revoke the permission.",
                kinds = offenders.map { it.kind }.toSet(),
                lastSeenMs = offenders.maxOf { it.lastBackgroundAccessMs },
            ),
        )
    }
}

/**
 * All-files access (`MANAGE_EXTERNAL_STORAGE`).
 *
 * This single grant defeats scoped storage and lets an app read every other
 * app's shared files. Google restricts it on Play to a short list of app
 * categories, so on a sideloaded app it is close to a red flag on its own.
 */
public object AllFilesAccessRule : UserAppRule() {
    override val id: String = "ALL_FILES_ACCESS"
    override val description: String = "Holds all-files access, bypassing scoped storage"

    override fun check(input: RuleInput): List<Finding> {
        val app = input.app
        val viaPermission = app.grants("${P}MANAGE_EXTERNAL_STORAGE")
        val viaOp = input.profile.tally("MANAGE_EXTERNAL_STORAGE")?.totalCount?.let { it > 0 } == true
        if (!viaPermission && !viaOp) return emptyList()

        val sideloaded = !app.installSource.trusted
        return listOf(
            Finding(
                ruleId = id,
                packageName = app.packageName,
                title = "All-files access granted",
                severity = if (sideloaded) Severity.CRITICAL else Severity.HIGH,
                rationale = "All-files access lets the app read and modify every file in shared " +
                    "storage, including documents, downloads and media written by other apps. " +
                    "It bypasses scoped storage entirely.",
                evidence = buildList {
                    add("${P}MANAGE_EXTERNAL_STORAGE granted=${app.grants("${P}MANAGE_EXTERNAL_STORAGE")}")
                    add("install source: ${app.installSource.label}")
                    input.profile.tally("MANAGE_EXTERNAL_STORAGE")?.let {
                        add("op exercised ${it.totalCount} times, last ${formatAge(input.context.nowMs, it.lastAccessMs)}")
                    }
                },
                recommendation = "Only file managers and backup tools need this. Revoke it in " +
                    "Settings > Apps > Special app access > All files access.",
                kinds = setOf(AccessKind.READ, AccessKind.WRITE),
            ),
        )
    }
}

/**
 * An accessibility service on an app that has no accessibility purpose.
 *
 * An enabled accessibility service can read every window's text and inject
 * taps: it is functionally a keylogger plus remote control, and it is the
 * single most abused mechanism in Android banking malware.
 */
public object AccessibilityAbuseRule : UserAppRule() {
    override val id: String = "ACCESSIBILITY_ABUSE"
    override val description: String = "Declares an accessibility service, which can read all screens and inject input"

    override fun check(input: RuleInput): List<Finding> {
        val app = input.app
        if (app.accessibilityServices.isEmpty() && !app.requests("${P}BIND_ACCESSIBILITY_SERVICE")) {
            return emptyList()
        }
        val alsoOverlays = app.grants("${P}SYSTEM_ALERT_WINDOW")
        return listOf(
            Finding(
                ruleId = id,
                packageName = app.packageName,
                title = if (alsoOverlays) {
                    "Accessibility service combined with screen overlays"
                } else {
                    "Declares an accessibility service"
                },
                // Accessibility alone is legitimate for many tools; paired with
                // overlay drawing it is the standard overlay-phishing toolkit.
                severity = if (alsoOverlays || !app.installSource.trusted) Severity.CRITICAL else Severity.HIGH,
                rationale = "An accessibility service receives the text of every window and can " +
                    "dispatch synthetic taps. Combined with drawing over other apps it can both " +
                    "observe and fake user interaction.",
                evidence = buildList {
                    app.accessibilityServices.forEach { add("service: $it") }
                    if (alsoOverlays) add("also holds $P SYSTEM_ALERT_WINDOW")
                    add("install source: ${app.installSource.label}")
                },
                recommendation = "If this app is not an accessibility tool you deliberately " +
                    "installed, disable it in Settings > Accessibility > Installed apps.",
                kinds = setOf(AccessKind.SYSTEM_CONTROL, AccessKind.READ),
            ),
        )
    }
}

/**
 * The sensitive-read + network pairing that makes exfiltration possible.
 *
 * Neither half is remarkable. Together, on an app from an untrusted source,
 * they are the minimum viable data-theft configuration - so this rule only
 * fires when the reads were actually *observed*, not merely permitted.
 */
public object ExfiltrationShapeRule : UserAppRule() {
    override val id: String = "EXFIL_SHAPE"
    override val description: String = "Reads sensitive data and has network access"

    override fun check(input: RuleInput): List<Finding> {
        val app = input.app
        if (!app.grants("${P}INTERNET")) return emptyList()

        val sensitiveGranted = app.grantedPermissions.intersect(Permissions.SENSITIVE_READ)
        if (sensitiveGranted.size < 2) return emptyList()

        val observedReads = input.tallies.filter {
            it.totalCount > 0 && OpCatalog.sensitivityOf(it.op) >= 7 &&
                (it.kind == AccessKind.READ || it.kind == AccessKind.MESSAGING || it.kind == AccessKind.LOCATION)
        }
        if (observedReads.isEmpty()) return emptyList()

        val untrusted = !app.installSource.trusted
        return listOf(
            Finding(
                ruleId = id,
                packageName = app.packageName,
                title = "Sensitive data read by an app with network access",
                severity = when {
                    untrusted && observedReads.size >= 3 -> Severity.HIGH
                    untrusted -> Severity.MEDIUM
                    else -> Severity.LOW
                },
                rationale = "The app can send data off the device and was observed reading " +
                    "${observedReads.size} kinds of sensitive data. This does not prove anything " +
                    "was uploaded - Android does not let us see an app's traffic - but it is the " +
                    "combination that makes exfiltration possible.",
                evidence = buildList {
                    add("granted sensitive reads: " + sensitiveGranted.joinToString { it.substringAfterLast('.') })
                    observedReads.take(6).forEach {
                        add("observed ${OpCatalog.labelOf(it.op)} x${it.totalCount}")
                    }
                    add("install source: ${app.installSource.label}")
                },
                recommendation = "Confirm the app has a feature that needs each of these. " +
                    "Revoke any you cannot account for.",
                kinds = observedReads.map { it.kind }.toSet() + AccessKind.NETWORK,
            ),
        )
    }
}

/** A sideloaded app holding permissions that stores gate carefully. */
public object SideloadedHighPowerRule : UserAppRule() {
    override val id: String = "SIDELOAD_HIGH_POWER"
    override val description: String = "Sideloaded app holding high-power permissions"
    override val skipSystemApps: Boolean = true

    override fun check(input: RuleInput): List<Finding> {
        val app = input.app
        if (app.installSource.trusted) return emptyList()
        val powerful = app.grantedPermissions.intersect(Permissions.HIGH_POWER)
        if (powerful.isEmpty()) return emptyList()

        return listOf(
            Finding(
                ruleId = id,
                packageName = app.packageName,
                title = "Sideloaded app with high-power permissions",
                severity = if (powerful.size >= 3) Severity.HIGH else Severity.MEDIUM,
                rationale = "This app was not installed from a store that reviews these " +
                    "permissions (${app.installSource.label}), and it holds " +
                    "${powerful.size} permission(s) that stores gate behind a policy review.",
                evidence = buildList {
                    add("installer: ${app.installerPackage ?: "none recorded (adb or pm install)"}")
                    powerful.forEach { add("granted: $it") }
                },
                recommendation = "Verify you installed this deliberately and that the APK came " +
                    "from the developer, then revoke whatever it does not need.",
                kinds = powerful.map { Permissions.kindOf(it) }.toSet(),
            ),
        )
    }
}

/**
 * Code that arrived after install: a `.so`, `.dex`, `.jar` or `.apk` in a
 * directory the app can write to.
 *
 * Reviewing an APK tells you nothing if the app downloads its real payload at
 * runtime, so this is the closest we can get to an "execute" finding without root.
 */
public object DynamicCodeLoadingRule : UserAppRule() {
    override val id: String = "DYNAMIC_CODE"
    override val description: String = "Executable code in a writable directory, or the ability to install apps"

    override fun check(input: RuleInput): List<Finding> {
        val app = input.app
        val findings = mutableListOf<Finding>()

        val writableCode = app.executableArtifacts.filter { it.inWritableDir }
        if (writableCode.isNotEmpty()) {
            findings += Finding(
                ruleId = id,
                packageName = app.packageName,
                title = "Loadable code in a writable directory",
                severity = Severity.HIGH,
                rationale = "Code in a directory the app can write to was not part of the " +
                    "installed package, so it was never reviewed by a store or by you. This is " +
                    "how an app ships behaviour that its published APK does not contain.",
                evidence = writableCode.take(8).map { artifact ->
                    "${artifact.path} (${artifact.sizeBytes} bytes" +
                        (if (artifact.executableBit) ", +x" else "") + ")"
                },
                recommendation = "Expected for app stores and plugin hosts. On anything else, " +
                    "treat the APK's contents as an incomplete picture of what the app runs.",
                kinds = setOf(AccessKind.EXECUTE),
            )
        }

        if (app.grants("${P}REQUEST_INSTALL_PACKAGES") && !app.installSource.trusted) {
            findings += Finding(
                ruleId = "$id.INSTALLER",
                packageName = app.packageName,
                title = "Sideloaded app can install other apps",
                severity = Severity.HIGH,
                rationale = "The app can prompt to install further APKs. A sideloaded app with " +
                    "this permission can bootstrap additional software onto the device.",
                evidence = listOf(
                    "granted: ${P}REQUEST_INSTALL_PACKAGES",
                    "install source: ${app.installSource.label}",
                ),
                recommendation = "Revoke in Settings > Apps > Special app access > Install unknown apps.",
                kinds = setOf(AccessKind.EXECUTE),
            )
        }
        return findings
    }
}

/** SMS access on an app that is not a messaging app: the SMS-2FA-theft shape. */
public object SmsInterceptionRule : UserAppRule() {
    override val id: String = "SMS_INTERCEPTION"
    override val description: String = "Reads or receives SMS while also having network access"

    override fun check(input: RuleInput): List<Finding> {
        val app = input.app
        val smsPermissions = app.grantedPermissions.filter {
            it in setOf("${P}READ_SMS", "${P}RECEIVE_SMS", "${P}RECEIVE_MMS", "${P}RECEIVE_WAP_PUSH")
        }
        if (smsPermissions.isEmpty()) return emptyList()

        val observed = input.tallies.filter { it.kind == AccessKind.MESSAGING && it.totalCount > 0 }
        val hasNetwork = app.grants("${P}INTERNET")
        if (!hasNetwork && observed.isEmpty()) return emptyList()

        return listOf(
            Finding(
                ruleId = id,
                packageName = app.packageName,
                title = "SMS access on a networked app",
                severity = if (observed.any { it.backgroundCount > 0 }) Severity.CRITICAL else Severity.HIGH,
                rationale = "The app can read incoming SMS and reach the network. One-time " +
                    "passcodes arrive by SMS, so this pairing is what account-takeover malware " +
                    "needs. Only your default messaging app normally requires it.",
                evidence = buildList {
                    smsPermissions.forEach { add("granted: $it") }
                    observed.forEach {
                        add(
                            "${OpCatalog.labelOf(it.op)} x${it.totalCount}" +
                                if (it.backgroundCount > 0) " (${it.backgroundCount} in background)" else "",
                        )
                    }
                },
                recommendation = "If this is not your SMS app, revoke SMS permissions now.",
                kinds = setOf(AccessKind.MESSAGING, AccessKind.NETWORK),
            ),
        )
    }
}

/**
 * A dangerous permission granted but never exercised in the observation window.
 *
 * Kept at LOW on purpose. It is a hygiene note, not an accusation: the app may
 * simply not have needed the permission yet. Scoring it any higher would bury
 * the real findings under noise, which is the usual failure mode of tools like this.
 */
public object UnusedDangerousPermissionRule : UserAppRule() {
    override val id: String = "UNUSED_DANGEROUS_PERMISSION"
    override val description: String = "Dangerous permission granted but not used in the observation window"

    override fun check(input: RuleInput): List<Finding> {
        // Needs a meaningful window to mean anything at all.
        if (input.context.windowMs < 12 * 3_600_000L) return emptyList()

        val unused = input.app.grantedPermissions
            .filter { it in Permissions.DANGEROUS }
            .filter { permission ->
                val spec = OpCatalog.specForPermission(permission) ?: return@filter false
                (input.profile.tally(spec.op)?.totalCount ?: 0) == 0
            }
        if (unused.isEmpty()) return emptyList()

        return listOf(
            Finding(
                ruleId = id,
                packageName = input.app.packageName,
                title = "${unused.size} granted permission(s) never used",
                severity = Severity.LOW,
                rationale = "These permissions were granted but not exercised in the last " +
                    "${input.context.windowMs / 3_600_000}h. That is not suspicious by itself - " +
                    "it just means the grant is currently unnecessary attack surface.",
                evidence = unused.sorted().map { "unused: $it" },
                recommendation = "Revoking these costs nothing until the app asks again.",
                kinds = unused.map { Permissions.kindOf(it) }.toSet(),
            ),
        )
    }
}

/** An app whose access rate is far above what comparable apps do for the same op. */
public object AccessFrequencyOutlierRule : UserAppRule() {
    override val id: String = "ACCESS_FREQUENCY_OUTLIER"
    override val description: String = "Uses an operation far more often than other apps on this device"

    private const val MULTIPLE = 8
    private const val MIN_ABSOLUTE = 40

    override fun check(input: RuleInput): List<Finding> {
        val outliers = input.tallies.mapNotNull { tally ->
            val median = input.context.deviceOpMedians[tally.op] ?: return@mapNotNull null
            // Both a relative and an absolute floor: without the floor, an op
            // with a device median of 1 flags every app that used it twice.
            if (median <= 0) return@mapNotNull null
            if (tally.totalCount < MIN_ABSOLUTE) return@mapNotNull null
            if (tally.totalCount < median * MULTIPLE) return@mapNotNull null
            tally to median
        }
        if (outliers.isEmpty()) return emptyList()

        return listOf(
            Finding(
                ruleId = id,
                packageName = input.app.packageName,
                title = "Unusually frequent access",
                severity = Severity.MEDIUM,
                rationale = "Compared with the other apps on this device, this app polls these " +
                    "operations far more often than typical. High-frequency polling is how apps " +
                    "build continuous profiles out of individually harmless reads.",
                evidence = outliers.map { (tally, median) ->
                    "${OpCatalog.labelOf(tally.op)}: ${tally.totalCount} accesses vs device median $median"
                },
                recommendation = "Compare against how much you actually use the app.",
                kinds = outliers.map { it.first.kind }.toSet(),
            ),
        )
    }
}

/** Access recorded while the app was never in the foreground at all. */
public object NeverForegroundRule : UserAppRule() {
    override val id: String = "ACTIVE_BUT_NEVER_OPENED"
    override val description: String = "Sensitive access by an app the user never opened"

    override fun check(input: RuleInput): List<Finding> {
        if (input.context.windowMs < 12 * 3_600_000L) return emptyList()
        // Requires usage-stats data to be meaningful; 0 foreground time with no
        // collector attached would flag everything.
        if ("usage-stats" !in input.profile.sources) return emptyList()
        if (input.profile.foregroundTimeMs > 60_000) return emptyList()

        val sensitive = input.tallies.filter { it.totalCount > 0 && OpCatalog.sensitivityOf(it.op) >= 7 }
        if (sensitive.isEmpty()) return emptyList()

        return listOf(
            Finding(
                ruleId = id,
                packageName = input.app.packageName,
                title = "Sensitive access with the app never opened",
                severity = Severity.HIGH,
                rationale = "The app had essentially no foreground time in this window " +
                    "(${input.profile.foregroundTimeMs / 1000}s) yet still performed sensitive " +
                    "operations. Nothing the user did can explain this access.",
                evidence = sensitive.map {
                    "${OpCatalog.labelOf(it.op)} x${it.totalCount}, last ${formatAge(input.context.nowMs, it.lastAccessMs)}"
                },
                recommendation = "An app you never open does not need sensitive permissions. " +
                    "Consider uninstalling it.",
                kinds = sensitive.map { it.kind }.toSet(),
            ),
        )
    }
}

/** Screen-overlay drawing, the enabling half of overlay phishing and tapjacking. */
public object OverlayRule : UserAppRule() {
    override val id: String = "SCREEN_OVERLAY"
    override val description: String = "Can draw over other apps"

    override fun check(input: RuleInput): List<Finding> {
        val app = input.app
        if (!app.grants("${P}SYSTEM_ALERT_WINDOW")) return emptyList()
        val used = input.profile.tally("SYSTEM_ALERT_WINDOW")
        return listOf(
            Finding(
                ruleId = id,
                packageName = app.packageName,
                title = "Can draw over other apps",
                severity = if (!app.installSource.trusted) Severity.HIGH else Severity.MEDIUM,
                rationale = "The app can display windows on top of any other app, which allows a " +
                    "fake login screen to be drawn over a real one, or a tap to be routed to a " +
                    "hidden control.",
                evidence = buildList {
                    add("granted: ${P}SYSTEM_ALERT_WINDOW")
                    used?.let { add("used ${it.totalCount} times, last ${formatAge(input.context.nowMs, it.lastAccessMs)}") }
                    add("install source: ${app.installSource.label}")
                },
                recommendation = "Revoke in Settings > Apps > Special app access > Display over other apps.",
                kinds = setOf(AccessKind.SYSTEM_CONTROL),
            ),
        )
    }
}

/** Notification listeners see every notification's content, including message previews. */
public object NotificationListenerRule : UserAppRule() {
    override val id: String = "NOTIFICATION_LISTENER"
    override val description: String = "Can read the content of all notifications"

    override fun check(input: RuleInput): List<Finding> {
        val app = input.app
        val hasAccess = app.requests("${P}BIND_NOTIFICATION_LISTENER_SERVICE") ||
            "ACCESS_NOTIFICATIONS" in app.specialAccess
        if (!hasAccess) return emptyList()
        return listOf(
            Finding(
                ruleId = id,
                packageName = app.packageName,
                title = "Reads all notifications",
                severity = if (app.grants("${P}INTERNET") && !app.installSource.trusted) {
                    Severity.HIGH
                } else {
                    Severity.MEDIUM
                },
                rationale = "A notification listener receives the full text of every notification, " +
                    "which in practice includes message previews and one-time passcodes.",
                evidence = buildList {
                    add("declares ${P}BIND_NOTIFICATION_LISTENER_SERVICE")
                    if (app.grants("${P}INTERNET")) add("also has network access")
                },
                recommendation = "Review in Settings > Notifications > Device & app notifications.",
                kinds = setOf(AccessKind.READ),
            ),
        )
    }
}

/** A debuggable build, or one that allows cleartext traffic: a leak, not an attack. */
public object WeakHardeningRule : UserAppRule() {
    override val id: String = "WEAK_HARDENING"
    override val description: String = "Debuggable build, cleartext traffic allowed, or shares a UID"

    override fun check(input: RuleInput): List<Finding> {
        val app = input.app
        val issues = buildList {
            if (app.isDebuggable) {
                add("debuggable=true - any app with adb access can attach to this process")
            }
            if (app.usesCleartextTraffic) {
                add("usesCleartextTraffic=true - traffic may be sent unencrypted")
            }
            if (app.allowsBackup) {
                add("allowBackup=true - app data can be extracted over adb backup")
            }
            app.sharedUserId?.let {
                add("sharedUserId=$it - shares a sandbox with every other app using that id")
            }
        }
        if (issues.isEmpty()) return emptyList()
        return listOf(
            Finding(
                ruleId = id,
                packageName = app.packageName,
                title = "Weak app hardening",
                severity = if (app.isDebuggable || app.sharedUserId != null) Severity.MEDIUM else Severity.LOW,
                rationale = "These settings do not mean the app misbehaves; they mean its data is " +
                    "easier for something else to reach.",
                evidence = issues,
                recommendation = "Worth raising with the developer. Nothing to change on the device.",
                kinds = setOf(AccessKind.READ),
            ),
        )
    }
}

/** An app targeting an old SDK opts out of the newer privacy defaults. */
public object StaleTargetSdkRule : UserAppRule() {
    override val id: String = "STALE_TARGET_SDK"
    override val description: String = "Targets an old SDK level and so opts out of newer privacy protections"

    override fun check(input: RuleInput): List<Finding> {
        val app = input.app
        val device = input.context.deviceSdkInt
        // Scoped storage became mandatory at 30; below that an app keeps broad
        // filesystem reach without asking for all-files access.
        if (app.targetSdk == 0 || app.targetSdk >= 30) return emptyList()
        if (device != 0 && device < 30) return emptyList()
        return listOf(
            Finding(
                ruleId = id,
                packageName = app.packageName,
                title = "Targets API ${app.targetSdk}, before scoped storage",
                severity = if (app.targetSdk < 26) Severity.MEDIUM else Severity.LOW,
                rationale = "Apps targeting below API 30 are exempt from scoped storage, so a " +
                    "single storage permission gives them far broader filesystem reach than the " +
                    "same permission grants a modern app.",
                evidence = listOf(
                    "targetSdk=${app.targetSdk}",
                    "device SDK=$device",
                    "storage permissions granted: " + app.grantedPermissions
                        .filter { it.contains("STORAGE") }
                        .joinToString()
                        .ifEmpty { "none" },
                ),
                recommendation = "Prefer a maintained alternative that targets a current API level.",
                kinds = setOf(AccessKind.READ, AccessKind.WRITE),
            ),
        )
    }
}

/** Denied ops: the app is repeatedly trying to do something the user refused. */
public object RepeatedDenialRule : UserAppRule() {
    override val id: String = "REPEATED_DENIAL"
    override val description: String = "Keeps attempting operations the platform denies"

    override fun check(input: RuleInput): List<Finding> {
        val persistent = input.tallies.filter { it.deniedCount >= 10 && OpCatalog.sensitivityOf(it.op) >= 6 }
        if (persistent.isEmpty()) return emptyList()
        return listOf(
            Finding(
                ruleId = id,
                packageName = input.app.packageName,
                title = "Repeatedly retrying denied access",
                severity = Severity.MEDIUM,
                rationale = "The platform refused these operations but the app keeps trying. The " +
                    "access is not happening, yet the persistence says the app wants data the " +
                    "user declined to give it.",
                evidence = persistent.map { "${OpCatalog.labelOf(it.op)}: ${it.deniedCount} denials" },
                recommendation = "Keep the permission denied. If the app is unusable without it, " +
                    "decide whether the trade is worth it.",
                kinds = persistent.map { it.kind }.toSet(),
            ),
        )
    }
}

/** The default rule set, in the order findings are presented. */
public val DEFAULT_RULES: List<Rule> = listOf(
    AccessibilityAbuseRule,
    AllFilesAccessRule,
    SmsInterceptionRule,
    BackgroundSensitiveAccessRule,
    BackgroundDataHarvestRule,
    NeverForegroundRule,
    DynamicCodeLoadingRule,
    ExfiltrationShapeRule,
    SideloadedHighPowerRule,
    OverlayRule,
    NotificationListenerRule,
    AccessFrequencyOutlierRule,
    RepeatedDenialRule,
    StaleTargetSdkRule,
    WeakHardeningRule,
    UnusedDangerousPermissionRule,
)

/** Helper for the UI's "why was this flagged" screen. */
public fun ruleById(id: String): Rule? = DEFAULT_RULES.firstOrNull { id.startsWith(it.id) }

internal fun OpTally.describe(): String = "$op x$totalCount (bg=$backgroundCount)"
