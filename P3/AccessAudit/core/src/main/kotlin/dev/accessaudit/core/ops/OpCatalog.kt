package dev.accessaudit.core.ops

import dev.accessaudit.core.model.AccessKind

/**
 * One row of the operation table.
 *
 * @param op canonical appop name as printed by `dumpsys appops` (no `android:` prefix)
 * @param kind which access class the op belongs to
 * @param sensitivity 0..10; how much a single use of this op should worry a reviewer
 * @param label wording shown in the UI
 * @param permission the runtime permission that gates the op, when there is one
 */
public data class OpSpec(
    val op: String,
    val kind: AccessKind,
    val sensitivity: Int,
    val label: String,
    val permission: String? = null,
)

/**
 * Maps Android's appop and permission names onto the [AccessKind] taxonomy.
 *
 * Two things keep this table honest:
 *  - it is keyed on the *canonical* op name, and [normalizeOp] strips the
 *    `android:`/`OP_` decorations that different Android releases print;
 *  - anything unknown falls back to [heuristicKind], which reads the op name
 *    itself, so a new op added in the next Android release still lands in a
 *    sensible bucket instead of vanishing from the report.
 */
public object OpCatalog {

    private const val P = "android.permission."

    private val SPECS: List<OpSpec> = listOf(
        // ---- location -------------------------------------------------------
        OpSpec("COARSE_LOCATION", AccessKind.LOCATION, 6, "Approximate location", "${P}ACCESS_COARSE_LOCATION"),
        OpSpec("FINE_LOCATION", AccessKind.LOCATION, 8, "Precise location", "${P}ACCESS_FINE_LOCATION"),
        OpSpec("MONITOR_LOCATION", AccessKind.LOCATION, 8, "Location updates"),
        OpSpec("MONITOR_HIGH_POWER_LOCATION", AccessKind.LOCATION, 9, "Continuous GPS"),
        OpSpec("COARSE_LOCATION_SOURCE", AccessKind.LOCATION, 6, "Approximate location source"),
        OpSpec("FINE_LOCATION_SOURCE", AccessKind.LOCATION, 8, "Precise location source"),
        OpSpec("ACCESS_MEDIA_LOCATION", AccessKind.LOCATION, 6, "Location tags in photos", "${P}ACCESS_MEDIA_LOCATION"),
        OpSpec("NEARBY_WIFI_DEVICES", AccessKind.LOCATION, 5, "Nearby Wi-Fi devices", "${P}NEARBY_WIFI_DEVICES"),
        OpSpec("BLUETOOTH_SCAN", AccessKind.LOCATION, 5, "Bluetooth scanning", "${P}BLUETOOTH_SCAN"),

        // ---- capture --------------------------------------------------------
        OpSpec("CAMERA", AccessKind.CAPTURE, 9, "Camera", "${P}CAMERA"),
        OpSpec("CAMERA_SANDBOXED", AccessKind.CAPTURE, 7, "Camera (sandboxed)"),
        OpSpec("RECORD_AUDIO", AccessKind.CAPTURE, 9, "Microphone", "${P}RECORD_AUDIO"),
        OpSpec("RECORD_AUDIO_SANDBOXED", AccessKind.CAPTURE, 7, "Microphone (sandboxed)"),
        OpSpec("RECORD_AUDIO_HOTWORD", AccessKind.CAPTURE, 7, "Hotword microphone"),
        OpSpec("RECEIVE_AMBIENT_TRIGGER_AUDIO", AccessKind.CAPTURE, 7, "Ambient audio trigger"),
        OpSpec("PROJECT_MEDIA", AccessKind.CAPTURE, 9, "Screen capture"),
        OpSpec("ASSIST_SCREENSHOT", AccessKind.CAPTURE, 8, "Screenshot of other apps"),
        OpSpec("ASSIST_STRUCTURE", AccessKind.CAPTURE, 8, "View hierarchy of other apps"),

        // ---- read -----------------------------------------------------------
        OpSpec("READ_EXTERNAL_STORAGE", AccessKind.READ, 6, "Read shared storage", "${P}READ_EXTERNAL_STORAGE"),
        OpSpec("READ_MEDIA_AUDIO", AccessKind.READ, 5, "Read audio files", "${P}READ_MEDIA_AUDIO"),
        OpSpec("READ_MEDIA_IMAGES", AccessKind.READ, 6, "Read image files", "${P}READ_MEDIA_IMAGES"),
        OpSpec("READ_MEDIA_VIDEO", AccessKind.READ, 6, "Read video files", "${P}READ_MEDIA_VIDEO"),
        OpSpec("READ_MEDIA_VISUAL_USER_SELECTED", AccessKind.READ, 3, "Read user-picked media"),
        OpSpec("MANAGE_EXTERNAL_STORAGE", AccessKind.READ, 10, "All-files access", "${P}MANAGE_EXTERNAL_STORAGE"),
        OpSpec("LEGACY_STORAGE", AccessKind.READ, 7, "Legacy unscoped storage"),
        OpSpec("READ_CONTACTS", AccessKind.READ, 8, "Read contacts", "${P}READ_CONTACTS"),
        OpSpec("READ_CALENDAR", AccessKind.READ, 6, "Read calendar", "${P}READ_CALENDAR"),
        OpSpec("READ_CLIPBOARD", AccessKind.READ, 8, "Read clipboard"),
        OpSpec("GET_USAGE_STATS", AccessKind.READ, 8, "Read app usage history", "${P}PACKAGE_USAGE_STATS"),
        OpSpec("LOADER_USAGE_STATS", AccessKind.READ, 6, "Read loader usage stats"),
        OpSpec("BODY_SENSORS", AccessKind.READ, 7, "Body sensors", "${P}BODY_SENSORS"),
        OpSpec("READ_WRITE_HEALTH_DATA", AccessKind.READ, 8, "Health data"),
        OpSpec("ACTIVITY_RECOGNITION", AccessKind.READ, 5, "Physical activity", "${P}ACTIVITY_RECOGNITION"),
        OpSpec("READ_DEVICE_IDENTIFIERS", AccessKind.IDENTITY, 9, "Hardware identifiers"),

        // ---- write ----------------------------------------------------------
        OpSpec("WRITE_EXTERNAL_STORAGE", AccessKind.WRITE, 6, "Write shared storage", "${P}WRITE_EXTERNAL_STORAGE"),
        OpSpec("WRITE_CONTACTS", AccessKind.WRITE, 7, "Modify contacts", "${P}WRITE_CONTACTS"),
        OpSpec("WRITE_CALENDAR", AccessKind.WRITE, 5, "Modify calendar", "${P}WRITE_CALENDAR"),
        OpSpec("WRITE_CLIPBOARD", AccessKind.WRITE, 4, "Write clipboard"),
        OpSpec("WRITE_SETTINGS", AccessKind.WRITE, 7, "Change system settings", "${P}WRITE_SETTINGS"),
        OpSpec("WRITE_WALLPAPER", AccessKind.WRITE, 2, "Change wallpaper"),
        OpSpec("WRITE_CALL_LOG", AccessKind.WRITE, 8, "Modify call log", "${P}WRITE_CALL_LOG"),
        OpSpec("MANAGE_MEDIA", AccessKind.WRITE, 7, "Delete media without asking"),

        // ---- messaging ------------------------------------------------------
        OpSpec("READ_SMS", AccessKind.MESSAGING, 10, "Read SMS", "${P}READ_SMS"),
        OpSpec("RECEIVE_SMS", AccessKind.MESSAGING, 9, "Receive SMS", "${P}RECEIVE_SMS"),
        OpSpec("RECEIVE_MMS", AccessKind.MESSAGING, 8, "Receive MMS", "${P}RECEIVE_MMS"),
        OpSpec("RECEIVE_WAP_PUSH", AccessKind.MESSAGING, 8, "Receive WAP push", "${P}RECEIVE_WAP_PUSH"),
        OpSpec("SEND_SMS", AccessKind.MESSAGING, 10, "Send SMS", "${P}SEND_SMS"),
        OpSpec("WRITE_SMS", AccessKind.MESSAGING, 9, "Write SMS"),
        OpSpec("READ_CALL_LOG", AccessKind.MESSAGING, 9, "Read call log", "${P}READ_CALL_LOG"),
        OpSpec("ANSWER_PHONE_CALLS", AccessKind.MESSAGING, 8, "Answer calls", "${P}ANSWER_PHONE_CALLS"),
        OpSpec("CALL_PHONE", AccessKind.MESSAGING, 8, "Place calls", "${P}CALL_PHONE"),
        OpSpec("PROCESS_OUTGOING_CALLS", AccessKind.MESSAGING, 9, "Intercept outgoing calls"),
        OpSpec("ADD_VOICEMAIL", AccessKind.MESSAGING, 5, "Add voicemail", "com.android.voicemail.permission.ADD_VOICEMAIL"),
        OpSpec("MANAGE_ONGOING_CALLS", AccessKind.MESSAGING, 7, "Manage ongoing calls"),

        // ---- identity -------------------------------------------------------
        OpSpec("READ_PHONE_STATE", AccessKind.IDENTITY, 6, "Phone state", "${P}READ_PHONE_STATE"),
        OpSpec("READ_PHONE_NUMBERS", AccessKind.IDENTITY, 7, "Phone number", "${P}READ_PHONE_NUMBERS"),
        OpSpec("GET_ACCOUNTS", AccessKind.IDENTITY, 6, "Device accounts", "${P}GET_ACCOUNTS"),
        OpSpec("USE_FINGERPRINT", AccessKind.IDENTITY, 4, "Biometrics"),
        OpSpec("USE_BIOMETRIC", AccessKind.IDENTITY, 4, "Biometrics", "${P}USE_BIOMETRIC"),

        // ---- network --------------------------------------------------------
        OpSpec("ACTIVATE_VPN", AccessKind.NETWORK, 10, "Route all traffic through a VPN"),
        OpSpec("ACTIVATE_PLATFORM_VPN", AccessKind.NETWORK, 9, "Platform VPN"),
        OpSpec("CHANGE_WIFI_STATE", AccessKind.NETWORK, 4, "Change Wi-Fi state", "${P}CHANGE_WIFI_STATE"),
        OpSpec("BLUETOOTH_CONNECT", AccessKind.NETWORK, 4, "Bluetooth connect", "${P}BLUETOOTH_CONNECT"),
        OpSpec("BLUETOOTH_ADVERTISE", AccessKind.NETWORK, 3, "Bluetooth advertise", "${P}BLUETOOTH_ADVERTISE"),
        OpSpec("MANAGE_IPSEC_TUNNELS", AccessKind.NETWORK, 8, "IPsec tunnels"),

        // ---- execute / code loading ----------------------------------------
        OpSpec("REQUEST_INSTALL_PACKAGES", AccessKind.EXECUTE, 9, "Install other apps", "${P}REQUEST_INSTALL_PACKAGES"),
        OpSpec("REQUEST_DELETE_PACKAGES", AccessKind.EXECUTE, 6, "Uninstall other apps", "${P}REQUEST_DELETE_PACKAGES"),
        OpSpec("INSTANT_APP_START_FOREGROUND", AccessKind.EXECUTE, 5, "Start instant apps"),
        OpSpec("START_FOREGROUND", AccessKind.EXECUTE, 3, "Run a foreground service"),
        OpSpec("RUN_IN_BACKGROUND", AccessKind.EXECUTE, 5, "Run in background"),
        OpSpec("RUN_ANY_IN_BACKGROUND", AccessKind.EXECUTE, 5, "Run in background (any)"),
        OpSpec("SCHEDULE_EXACT_ALARM", AccessKind.EXECUTE, 4, "Exact alarms", "${P}SCHEDULE_EXACT_ALARM"),

        // ---- system control -------------------------------------------------
        OpSpec("SYSTEM_ALERT_WINDOW", AccessKind.SYSTEM_CONTROL, 9, "Draw over other apps", "${P}SYSTEM_ALERT_WINDOW"),
        OpSpec("TOAST_WINDOW", AccessKind.SYSTEM_CONTROL, 4, "Custom toasts"),
        OpSpec("BIND_ACCESSIBILITY_SERVICE", AccessKind.SYSTEM_CONTROL, 10, "Accessibility service"),
        OpSpec("ACCESS_NOTIFICATIONS", AccessKind.SYSTEM_CONTROL, 8, "Read all notifications"),
        OpSpec("POST_NOTIFICATION", AccessKind.SYSTEM_CONTROL, 2, "Post notifications", "${P}POST_NOTIFICATIONS"),
        OpSpec("ACCESS_RESTRICTED_SETTINGS", AccessKind.SYSTEM_CONTROL, 9, "Restricted settings"),
        OpSpec("USE_FULL_SCREEN_INTENT", AccessKind.SYSTEM_CONTROL, 5, "Full-screen intents"),
        OpSpec("TURN_SCREEN_ON", AccessKind.SYSTEM_CONTROL, 3, "Turn the screen on"),
        OpSpec("MUTE_MICROPHONE", AccessKind.SYSTEM_CONTROL, 3, "Mute the microphone"),
        OpSpec("TAKE_AUDIO_FOCUS", AccessKind.OTHER, 1, "Take audio focus"),
        OpSpec("PLAY_AUDIO", AccessKind.OTHER, 1, "Play audio"),
        OpSpec("VIBRATE", AccessKind.OTHER, 1, "Vibrate"),
        OpSpec("WAKE_LOCK", AccessKind.OTHER, 2, "Keep the device awake", "${P}WAKE_LOCK"),
    )

    private val byOp: Map<String, OpSpec> = SPECS.associateBy { it.op }

    private val byPermission: Map<String, OpSpec> =
        SPECS.mapNotNull { spec -> spec.permission?.let { it to spec } }.toMap()

    /**
     * Strips the decorations Android puts around op names so that
     * `android:read_sms`, `OP_READ_SMS` and `READ_SMS` all collapse to one key.
     */
    public fun normalizeOp(raw: String): String =
        raw.trim()
            .removePrefix("android:")
            .removePrefix("ANDROID:")
            .let { if (it.startsWith("OP_")) it.removePrefix("OP_") else it }
            .uppercase()
            .replace('-', '_')

    public fun spec(rawOp: String): OpSpec? = byOp[normalizeOp(rawOp)]

    public fun specForPermission(permission: String): OpSpec? = byPermission[permission]

    /** [AccessKind] for an op, falling back to [heuristicKind] for unknown ops. */
    public fun kindOf(rawOp: String): AccessKind {
        val normalized = normalizeOp(rawOp)
        return byOp[normalized]?.kind ?: heuristicKind(normalized)
    }

    public fun sensitivityOf(rawOp: String): Int =
        byOp[normalizeOp(rawOp)]?.sensitivity ?: when (heuristicKind(normalizeOp(rawOp))) {
            AccessKind.OTHER -> 1
            else -> 5
        }

    public fun labelOf(rawOp: String): String {
        val normalized = normalizeOp(rawOp)
        return byOp[normalized]?.label
            ?: normalized.split('_').joinToString(" ") { word ->
                word.lowercase().replaceFirstChar { it.uppercase() }
            }
    }

    /**
     * Best-effort classification for an op we have no row for.
     *
     * Android adds ops every release; guessing from the name keeps a new op
     * visible in the report (as a read, a write, ...) instead of silently
     * dropping it into [AccessKind.OTHER].
     */
    public fun heuristicKind(normalizedOp: String): AccessKind = when {
        normalizedOp.contains("LOCATION") -> AccessKind.LOCATION
        normalizedOp.contains("CAMERA") || normalizedOp.contains("AUDIO_RECORD") ||
            normalizedOp.contains("RECORD_AUDIO") || normalizedOp.contains("PROJECT") -> AccessKind.CAPTURE
        normalizedOp.contains("SMS") || normalizedOp.contains("MMS") ||
            normalizedOp.contains("CALL") || normalizedOp.contains("PHONE_CALL") -> AccessKind.MESSAGING
        normalizedOp.contains("INSTALL") || normalizedOp.contains("EXEC") -> AccessKind.EXECUTE
        normalizedOp.contains("VPN") || normalizedOp.contains("WIFI") ||
            normalizedOp.contains("BLUETOOTH") || normalizedOp.contains("NETWORK") -> AccessKind.NETWORK
        normalizedOp.startsWith("READ_") || normalizedOp.startsWith("GET_") ||
            normalizedOp.startsWith("ACCESS_") -> AccessKind.READ
        normalizedOp.startsWith("WRITE_") || normalizedOp.startsWith("MANAGE_") ||
            normalizedOp.startsWith("CHANGE_") -> AccessKind.WRITE
        else -> AccessKind.OTHER
    }

    /** Every op we know about, for building the UI's filter chips. */
    public fun allSpecs(): List<OpSpec> = SPECS
}
