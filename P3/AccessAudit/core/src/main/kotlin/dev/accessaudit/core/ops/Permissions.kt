package dev.accessaudit.core.ops

import dev.accessaudit.core.model.AccessKind

/**
 * Permission-level knowledge used by the rules that reason about what an app
 * *could* do, as opposed to what it was observed doing.
 */
public object Permissions {

    private const val P = "android.permission."

    /**
     * Runtime ("dangerous") permissions. Granting one is a user decision, so an
     * app holding one it never exercises is over-permissioned rather than
     * malicious - which is why that rule is LOW, not HIGH.
     */
    public val DANGEROUS: Set<String> = setOf(
        "${P}READ_CALENDAR", "${P}WRITE_CALENDAR",
        "${P}CAMERA",
        "${P}READ_CONTACTS", "${P}WRITE_CONTACTS", "${P}GET_ACCOUNTS",
        "${P}ACCESS_FINE_LOCATION", "${P}ACCESS_COARSE_LOCATION", "${P}ACCESS_BACKGROUND_LOCATION",
        "${P}RECORD_AUDIO",
        "${P}READ_PHONE_STATE", "${P}READ_PHONE_NUMBERS", "${P}CALL_PHONE",
        "${P}ANSWER_PHONE_CALLS", "${P}READ_CALL_LOG", "${P}WRITE_CALL_LOG",
        "${P}ADD_VOICEMAIL", "${P}USE_SIP", "${P}ACCEPT_HANDOVER",
        "${P}BODY_SENSORS", "${P}BODY_SENSORS_BACKGROUND", "${P}ACTIVITY_RECOGNITION",
        "${P}SEND_SMS", "${P}RECEIVE_SMS", "${P}READ_SMS",
        "${P}RECEIVE_WAP_PUSH", "${P}RECEIVE_MMS",
        "${P}READ_EXTERNAL_STORAGE", "${P}WRITE_EXTERNAL_STORAGE",
        "${P}READ_MEDIA_AUDIO", "${P}READ_MEDIA_IMAGES", "${P}READ_MEDIA_VIDEO",
        "${P}ACCESS_MEDIA_LOCATION",
        "${P}BLUETOOTH_SCAN", "${P}BLUETOOTH_CONNECT", "${P}BLUETOOTH_ADVERTISE",
        "${P}NEARBY_WIFI_DEVICES",
        "${P}POST_NOTIFICATIONS",
    )

    /**
     * Permissions and special access grants that are individually alarming on an
     * app that has no business reason for them.
     */
    public val HIGH_POWER: Set<String> = setOf(
        "${P}MANAGE_EXTERNAL_STORAGE",
        "${P}SYSTEM_ALERT_WINDOW",
        "${P}REQUEST_INSTALL_PACKAGES",
        "${P}WRITE_SECURE_SETTINGS",
        "${P}PACKAGE_USAGE_STATS",
        "${P}BIND_DEVICE_ADMIN",
        "${P}QUERY_ALL_PACKAGES",
        "${P}READ_LOGS",
        "${P}DUMP",
        "${P}BIND_NOTIFICATION_LISTENER_SERVICE",
        "${P}BIND_ACCESSIBILITY_SERVICE",
        "${P}REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
        "${P}SCHEDULE_EXACT_ALARM",
        "${P}ACCESS_BACKGROUND_LOCATION",
    )

    /** Permissions that let an app move data off the device. */
    public val EXFIL_CAPABLE: Set<String> = setOf(
        "${P}INTERNET",
        "${P}CHANGE_NETWORK_STATE",
        "${P}BLUETOOTH_CONNECT",
        "${P}BLUETOOTH_ADMIN",
        "${P}NFC",
    )

    /** Permissions that read personal data worth exfiltrating. */
    public val SENSITIVE_READ: Set<String> = setOf(
        "${P}READ_CONTACTS",
        "${P}READ_SMS",
        "${P}READ_CALL_LOG",
        "${P}READ_CALENDAR",
        "${P}READ_EXTERNAL_STORAGE",
        "${P}MANAGE_EXTERNAL_STORAGE",
        "${P}READ_MEDIA_IMAGES",
        "${P}READ_MEDIA_VIDEO",
        "${P}ACCESS_FINE_LOCATION",
        "${P}GET_ACCOUNTS",
        "${P}READ_PHONE_NUMBERS",
    )

    /** Maps a permission onto an access class, via the op table where possible. */
    public fun kindOf(permission: String): AccessKind {
        OpCatalog.specForPermission(permission)?.let { return it.kind }
        val leaf = permission.substringAfterLast('.')
        return when {
            permission == "${P}INTERNET" -> AccessKind.NETWORK
            permission == "${P}QUERY_ALL_PACKAGES" -> AccessKind.READ
            permission == "${P}BIND_ACCESSIBILITY_SERVICE" -> AccessKind.SYSTEM_CONTROL
            permission == "${P}BIND_DEVICE_ADMIN" -> AccessKind.SYSTEM_CONTROL
            else -> OpCatalog.heuristicKind(leaf)
        }
    }

    /** Short human label for a permission, e.g. `READ_SMS` -> "Read Sms". */
    public fun labelOf(permission: String): String = OpCatalog.labelOf(permission.substringAfterLast('.'))
}
