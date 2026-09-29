package dev.accessaudit.app.collect

import dev.accessaudit.core.model.AccessEvent
import dev.accessaudit.core.model.AccessKind
import dev.accessaudit.core.model.OpMode
import dev.accessaudit.core.model.UidState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Tier 3 (root only): genuine file-level read/write/execute, recovered from the
 * kernel's SELinux audit records.
 *
 * This is the only collector that reports *actual filesystem access* rather than
 * platform operations. It is also the most limited: the kernel logs an `avc`
 * record when a policy decision is interesting - overwhelmingly denials - so this
 * is a record of access an app *attempted and was refused*, plus whatever the
 * device's policy happens to audit on success. It is not a complete I/O trace;
 * getting that needs a custom kernel with fanotify or eBPF.
 *
 * Presented in the UI as "denied file access (root)" for exactly that reason -
 * labelling it as a full read/write log would be a lie.
 */
public class RootFileAuditCollector(private val shell: ShellGateway) : AccessCollector {

    override val id: String = "selinux-audit"
    override val tier: CollectorTier = CollectorTier.KERNEL

    override suspend fun isAvailable(): Boolean = shell.channel() == ShellChannel.ROOT

    override suspend fun collect(windowStartMs: Long, nowMs: Long): CollectionResult =
        withContext(Dispatchers.IO) {
            if (shell.channel() != ShellChannel.ROOT) {
                return@withContext CollectionResult(
                    collectorId = id,
                    tier = tier,
                    available = false,
                    unavailableReason = "File-level auditing needs root. Android does not let an " +
                        "unprivileged app observe another app's file I/O at all.",
                )
            }

            // `-d` drains rather than follows; without it this blocks forever.
            val result = shell.run("logcat -d -b events -s auditd:* 2>/dev/null; dmesg | grep -i avc")
            if (!result.succeeded) {
                return@withContext CollectionResult(
                    collectorId = id,
                    tier = tier,
                    available = false,
                    unavailableReason = "Could not read the kernel audit log: " +
                        result.stderr.take(200).ifBlank { "no avc records present" },
                )
            }

            CollectionResult(
                collectorId = id,
                tier = tier,
                available = true,
                events = parseAvc(result.stdout, nowMs),
            )
        }

    /**
     * Parses `avc: denied { read write } for ... comm="..." path="..." ... app=com.foo`
     * lines. Field order varies by kernel, so each field is matched independently.
     */
    internal fun parseAvc(output: String, nowMs: Long): List<AccessEvent> =
        output.lineSequence()
            .filter { it.contains("avc:") }
            .mapNotNull { line -> parseAvcLine(line, nowMs) }
            .toList()

    private fun parseAvcLine(line: String, nowMs: Long): AccessEvent? {
        val permissions = PERMISSION_SET.find(line)?.groupValues?.get(1)?.trim()?.split(' ')
            ?: return null
        // `app=` is not always present; fall back to the process name.
        val packageName = APP_FIELD.find(line)?.groupValues?.get(1)
            ?: COMM_FIELD.find(line)?.groupValues?.get(1)
            ?: return null
        val path = PATH_FIELD.find(line)?.groupValues?.get(1)
        val denied = line.contains("avc: denied")

        val kind = when {
            permissions.any { it == "execute" || it == "execute_no_trans" || it == "execmod" } ->
                AccessKind.EXECUTE
            permissions.any { it == "write" || it == "append" || it == "create" || it == "unlink" } ->
                AccessKind.WRITE
            permissions.any { it == "read" || it == "open" || it == "getattr" } ->
                AccessKind.READ
            else -> AccessKind.OTHER
        }

        return AccessEvent(
            packageName = packageName,
            uid = -1,
            op = "FILE_" + kind.label.uppercase(),
            kind = kind,
            mode = if (denied) OpMode.DENY else OpMode.ALLOW,
            // The kernel stamps these with monotonic uptime, not wall-clock, and
            // reconciling the two is not reliable enough to invent a timestamp.
            timestampMs = 0,
            state = UidState.UNKNOWN,
            denied = denied,
            source = id + (path?.let { " path=$it" } ?: ""),
        )
    }

    private companion object {
        val PERMISSION_SET = Regex("""\{([^}]*)}""")
        val APP_FIELD = Regex("""app=([A-Za-z0-9_.]+)""")
        val COMM_FIELD = Regex("""comm="([^"]+)"""")
        val PATH_FIELD = Regex("""path="([^"]+)"""")
    }
}
