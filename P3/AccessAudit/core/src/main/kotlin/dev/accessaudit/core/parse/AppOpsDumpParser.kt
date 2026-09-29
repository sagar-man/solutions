package dev.accessaudit.core.parse

import dev.accessaudit.core.model.AccessEvent
import dev.accessaudit.core.model.AccessKind
import dev.accessaudit.core.model.OpMode
import dev.accessaudit.core.model.OpTally
import dev.accessaudit.core.model.UidState
import dev.accessaudit.core.ops.OpCatalog
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoField

/** One `(uid, package, op)` row recovered from an appops dump. */
public data class PackageOpEntry(
    val uid: Int,
    val packageName: String,
    val op: String,
    val kind: AccessKind,
    val mode: OpMode,
    val events: List<AccessEvent>,
) {
    /** True when the op is permitted but we saw no access in the dump's window. */
    public val grantedButUnused: Boolean
        get() = (mode == OpMode.ALLOW || mode == OpMode.FOREGROUND) && events.none { !it.denied }
}

/** Everything one dump told us. */
public data class AppOpsDump(
    val entries: List<PackageOpEntry>,
    /** Lines the parser could not make sense of, kept so format drift is visible. */
    val unparsedLines: List<String> = emptyList(),
) {
    public fun events(): List<AccessEvent> = entries.flatMap { it.events }

    public fun packages(): Set<String> = entries.map { it.packageName }.toSet()

    public fun entriesFor(packageName: String): List<PackageOpEntry> =
        entries.filter { it.packageName == packageName }

    /** Collapses this dump into per-op roll-ups for one package. */
    public fun talliesFor(packageName: String): List<OpTally> =
        entriesFor(packageName).map { entry ->
            val allowed = entry.events.filter { !it.denied }
            OpTally(
                op = entry.op,
                kind = entry.kind,
                mode = entry.mode,
                totalCount = allowed.sumOf { it.count },
                foregroundCount = allowed.filter { !it.isBackground }.sumOf { it.count },
                backgroundCount = allowed.filter { it.isBackground }.sumOf { it.count },
                deniedCount = entry.events.filter { it.denied }.sumOf { it.count },
                lastAccessMs = allowed.maxOfOrNull { it.timestampMs } ?: 0L,
                lastBackgroundAccessMs = allowed.filter { it.isBackground }
                    .maxOfOrNull { it.timestampMs } ?: 0L,
                totalDurationMs = allowed.sumOf { it.durationMs },
            )
        }
}

/**
 * Parses the output of `dumpsys appops` (and the narrower `cmd appops get <pkg>`).
 *
 * The format is not a stable API and has shifted noticeably across releases -
 * attribution tags appeared in Android 11, the flag vocabulary in the `[..]`
 * prefix has grown, and some builds print only a relative age while others
 * print an absolute timestamp too. The parser is therefore written to be
 * *lenient and stateful* rather than strict:
 *
 *  - it keys off the handful of line shapes that have stayed put (`Uid <n>:`,
 *    `Package <name>:`, `<OP> (<mode>)`, `Access:`/`Reject:`);
 *  - it ignores indentation, since that has changed between releases;
 *  - unknown lines go to [AppOpsDump.unparsedLines] instead of aborting the
 *    parse, so one new field cannot blank out a whole report.
 *
 * Timestamps prefer the relative age in parentheses, which is timezone-proof,
 * and fall back to the absolute `yyyy-MM-dd HH:mm:ss.SSS` stamp interpreted in
 * [zone]. Both [nowMs] and [zone] are injected so the behaviour is pinned in tests.
 */
public class AppOpsDumpParser(
    private val nowMs: Long,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    private companion object {
        val UID_LINE = Regex("""^Uid\s+(\d+):?\s*$""")
        val PACKAGE_LINE = Regex("""^Package\s+([A-Za-z0-9_.]+):?\s*$""")
        // `COARSE_LOCATION (allow)`, `READ_SMS (ignore / switch READ_SMS)`,
        // `android:camera (foreground):`
        val OP_LINE = Regex("""^([A-Za-z0-9_:]+)\s+\(([^)]*)\):?\s*$""")
        // `Access: [fg-s]2024-05-01 10:22:33.123 (-2h3m) duration=+1m30s`
        val ACCESS_LINE = Regex("""^(Access|Reject|Deny)\s*:\s*(.*)$""", RegexOption.IGNORE_CASE)
        val FLAGS = Regex("""^\[([^]]*)]""")
        val ABSOLUTE = Regex("""(\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?)""")
        val RELATIVE = Regex("""\(([+-][^)]*)\)""")
        val DURATION = Regex("""duration=(\S+)""")
        val COUNT = Regex("""(?:count|x)=(\d+)""", RegexOption.IGNORE_CASE)
        // Built rather than pattern-parsed so that a missing, 1-, 2- or
        // 3-digit fractional second all parse with one formatter.
        // The `Uid mode state:` preamble lists uid-level overrides as
        // `com.example: READ_SMS=ignore`. That is a mode, not an access, and the
        // authoritative per-package modes come from the `Package ...:` blocks
        // below it, so these lines are recognised and skipped.
        val UID_MODE_OVERRIDE = Regex("""^[A-Za-z0-9_.]+:\s*[A-Za-z0-9_:]+=\S+$""")
        val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm:ss")
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
            .toFormatter()
    }

    public fun parse(dump: String): AppOpsDump = parse(dump.lineSequence())

    public fun parse(lines: Sequence<String>): AppOpsDump {
        val entries = mutableListOf<PackageOpEntry>()
        val unparsed = mutableListOf<String>()

        var uid = -1
        var packageName: String? = null
        var currentOp: String? = null
        var currentMode = OpMode.UNKNOWN
        var currentEvents = mutableListOf<AccessEvent>()

        fun flushOp() {
            val op = currentOp ?: return
            val pkg = packageName ?: return
            entries += PackageOpEntry(
                uid = uid,
                packageName = pkg,
                op = op,
                kind = OpCatalog.kindOf(op),
                mode = currentMode,
                events = currentEvents.toList(),
            )
            currentOp = null
            currentMode = OpMode.UNKNOWN
            currentEvents = mutableListOf()
        }

        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            val uidMatch = UID_LINE.matchEntire(line)
            if (uidMatch != null) {
                flushOp()
                packageName = null
                uid = uidMatch.groupValues[1].toIntOrNull() ?: -1
                continue
            }

            val packageMatch = PACKAGE_LINE.matchEntire(line)
            if (packageMatch != null) {
                flushOp()
                packageName = packageMatch.groupValues[1]
                continue
            }

            val accessMatch = ACCESS_LINE.matchEntire(line)
            if (accessMatch != null) {
                val op = currentOp
                val pkg = packageName
                if (op == null || pkg == null) {
                    unparsed += raw
                } else {
                    currentEvents += buildEvent(
                        packageName = pkg,
                        uid = uid,
                        op = op,
                        mode = currentMode,
                        denied = !accessMatch.groupValues[1].equals("Access", ignoreCase = true),
                        payload = accessMatch.groupValues[2],
                    )
                }
                continue
            }

            // An op header only counts when we already know whose op it is;
            // otherwise `(allow)`-shaped noise from other dumpsys sections would
            // be misread as an op.
            val opMatch = OP_LINE.matchEntire(line)
            if (opMatch != null && packageName != null) {
                flushOp()
                currentOp = OpCatalog.normalizeOp(opMatch.groupValues[1])
                currentMode = OpMode.parse(opMatch.groupValues[2].split('/', ';').first())
                continue
            }

            // Structural noise we knowingly skip: section headers and the
            // per-uid state lines that carry no per-op information.
            if (isKnownNoise(line)) continue

            unparsed += raw
        }
        flushOp()

        return AppOpsDump(entries = entries, unparsedLines = unparsed)
    }

    private fun isKnownNoise(line: String): Boolean =
        line.startsWith("Current AppOps") ||
            line.startsWith("Uid mode") ||
            line.startsWith("Op mode") ||
            line.startsWith("attributionTag") ||
            line.startsWith("Attribution") ||
            line.startsWith("state=") ||
            line.startsWith("capability=") ||
            line.startsWith("appWidgetVisible=") ||
            line.startsWith("pendingState=") ||
            line.startsWith("foregroundOps") ||
            line.startsWith("Round ") ||
            line.startsWith("Special ") ||
            line == "Uid mode state:" ||
            line.endsWith("mode state:") ||
            UID_MODE_OVERRIDE.matches(line)

    private fun buildEvent(
        packageName: String,
        uid: Int,
        op: String,
        mode: OpMode,
        denied: Boolean,
        payload: String,
    ): AccessEvent {
        val state = FLAGS.find(payload)?.groupValues?.get(1)
            ?.let { UidState.parseFlags(it) }
            ?: UidState.UNKNOWN

        val relative = RELATIVE.find(payload)?.groupValues?.get(1)
            ?.let { RelativeTime.parseSignedMillis(it) }
        val absolute = ABSOLUTE.find(payload)?.groupValues?.get(1)?.let { parseAbsolute(it) }

        val timestamp = when {
            relative != null -> nowMs + relative
            absolute != null -> absolute
            else -> 0L
        }

        return AccessEvent(
            packageName = packageName,
            uid = uid,
            op = op,
            kind = OpCatalog.kindOf(op),
            mode = mode,
            timestampMs = timestamp,
            state = state,
            durationMs = DURATION.find(payload)?.groupValues?.get(1)
                ?.let { RelativeTime.parseDurationMillis(it) } ?: 0L,
            count = COUNT.find(payload)?.groupValues?.get(1)?.toIntOrNull() ?: 1,
            denied = denied,
            source = "appops-dump",
        )
    }

    private fun parseAbsolute(text: String): Long? = try {
        LocalDateTime.parse(text.replace('T', ' '), TIMESTAMP_FORMAT)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }
}
