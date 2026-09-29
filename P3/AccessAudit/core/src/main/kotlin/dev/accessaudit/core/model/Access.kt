package dev.accessaudit.core.model

/**
 * The coarse class of access an operation represents.
 *
 * Android does not expose a per-syscall view of other apps' behaviour to a
 * normal app, so "read / write / execute" here is the *capability* class of the
 * platform operation an app actually invoked, not a raw file mode. See
 * [dev.accessaudit.core.ops.OpCatalog] for the mapping and the Readme for what
 * each collector tier can and cannot observe.
 */
public enum class AccessKind(public val label: String) {
    /** Reads data out of the device: storage, contacts, SMS, clipboard, sensors. */
    READ("read"),

    /** Mutates state the user would care about: storage writes, settings, calls placed. */
    WRITE("write"),

    /** Runs code or loads code that was not part of the reviewed APK. */
    EXECUTE("execute"),

    /** Moves bytes off the device. */
    NETWORK("network"),

    /** Physical position of the user. */
    LOCATION("location"),

    /** Live camera / microphone / screen capture. */
    CAPTURE("capture"),

    /** Stable identifiers that allow cross-app tracking. */
    IDENTITY("identity"),

    /** Reads or sends messages (SMS/MMS/call log). */
    MESSAGING("messaging"),

    /** Changes how the rest of the system behaves (overlays, accessibility, admin). */
    SYSTEM_CONTROL("system-control"),

    /** Recognised operation with no interesting access class, or an unknown op. */
    OTHER("other"),
    ;

    public companion object {
        public fun fromLabel(label: String): AccessKind =
            entries.firstOrNull { it.label.equals(label, ignoreCase = true) } ?: OTHER
    }
}

/** Whether the platform allowed, ignored or hard-denied the operation. */
public enum class OpMode { ALLOW, IGNORE, DENY, DEFAULT, FOREGROUND, UNKNOWN;
    public companion object {
        public fun parse(raw: String): OpMode = when (raw.trim().lowercase()) {
            "allow", "allowed" -> ALLOW
            "ignore", "ignored" -> IGNORE
            "deny", "denied", "errored" -> DENY
            "default" -> DEFAULT
            "foreground" -> FOREGROUND
            else -> UNKNOWN
        }
    }
}

/**
 * The process state an app was in when it performed an access.
 *
 * This is the single most useful signal we get for free: a photo app touching
 * the camera in [FOREGROUND_SERVICE] while its UI is visible is expected, the
 * same app doing it from [BACKGROUND] is not.
 */
public enum class UidState(public val isBackground: Boolean) {
    /** Top of the screen, user is looking at it. */
    TOP(false),

    /** Visible foreground service (a notification is showing). */
    FOREGROUND_SERVICE(false),

    /** Foreground but not top (e.g. a bound service of a visible app). */
    FOREGROUND(false),

    /** No user-visible component. The interesting case. */
    BACKGROUND(true),

    /** Cached / not running. Access from here is the most suspicious of all. */
    CACHED(true),

    UNKNOWN(false),
    ;

    public companion object {
        /**
         * Decodes the flag soup that `dumpsys appops` prints in front of each
         * access timestamp, e.g. `[fg-s]`, `[bg-tpd]`, `[tp]`.
         *
         * The leading token before the first `-` is the state; the remainder is
         * a set of attribution flags (trusted/untrusted proxy, self, etc.) that
         * we do not need here.
         */
        public fun parseFlags(flags: String): UidState {
            val cleaned = flags.trim().removePrefix("[").removeSuffix("]").lowercase()
            val state = cleaned.substringBefore('-')
            return when {
                state.startsWith("tp") || state == "top" -> TOP
                state == "fgsvc" || state == "fgs" -> FOREGROUND_SERVICE
                state == "fg" && cleaned.contains("-s") -> FOREGROUND_SERVICE
                state == "fg" -> FOREGROUND
                state == "bg" -> BACKGROUND
                state == "cch" || state == "cached" -> CACHED
                else -> UNKNOWN
            }
        }
    }
}

/**
 * One observed access: "package X used operation Y at time Z while in state S".
 *
 * A single record may stand for several accesses ([count]) because the platform
 * aggregates repeated ops inside a time bucket rather than logging each one.
 */
public data class AccessEvent(
    val packageName: String,
    val uid: Int,
    val op: String,
    val kind: AccessKind,
    val mode: OpMode,
    /** Epoch millis of the access. 0 when the source only gave us a count. */
    val timestampMs: Long,
    val state: UidState,
    /** How long the app held the op, when the platform tracks duration (mic, camera). */
    val durationMs: Long = 0,
    val count: Int = 1,
    /** True when the platform refused the op instead of performing it. */
    val denied: Boolean = false,
    /** Free-form note about where this record came from, for the evidence trail. */
    val source: String = "",
) {
    public val isBackground: Boolean get() = state.isBackground
}

/** Per-operation roll-up for one package, used by the UI and by the risk rules. */
public data class OpTally(
    val op: String,
    val kind: AccessKind,
    val mode: OpMode,
    val totalCount: Int,
    val foregroundCount: Int,
    val backgroundCount: Int,
    val deniedCount: Int,
    val lastAccessMs: Long,
    val lastBackgroundAccessMs: Long,
    val totalDurationMs: Long,
) {
    public val backgroundRatio: Double
        get() = if (totalCount == 0) 0.0 else backgroundCount.toDouble() / totalCount
}

/** Everything we know about one app's *observed* behaviour, plus its identity. */
public data class AppAccessProfile(
    val app: AppIdentity,
    val tallies: List<OpTally> = emptyList(),
    val events: List<AccessEvent> = emptyList(),
    /** Foreground milliseconds in the observation window, from UsageStatsManager. */
    val foregroundTimeMs: Long = 0,
    /** Which collectors contributed to this profile. */
    val sources: Set<String> = emptySet(),
) {
    public fun tally(op: String): OpTally? = tallies.firstOrNull { it.op.equals(op, ignoreCase = true) }

    public fun kindsAccessed(): Set<AccessKind> = tallies.filter { it.totalCount > 0 }.map { it.kind }.toSet()

    public fun backgroundTallies(): List<OpTally> = tallies.filter { it.backgroundCount > 0 }
}
