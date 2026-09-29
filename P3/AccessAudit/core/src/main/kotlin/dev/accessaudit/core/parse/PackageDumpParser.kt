package dev.accessaudit.core.parse

import dev.accessaudit.core.model.AppIdentity
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoField

/**
 * Recovers [AppIdentity] records from `adb shell dumpsys package`.
 *
 * On-device the app reads the same facts straight from `PackageManager`; this
 * parser exists for the host-side path, where a developer captures a dump with
 * `tools/adb-access-audit.sh` and analyses it with the `:cli` module without
 * installing anything on the phone.
 *
 * Like [AppOpsDumpParser] this is intentionally tolerant: the fields it wants
 * are scattered through a very chatty dump whose exact layout differs per
 * release, so it matches on `key=value` shapes and on the `... permissions:`
 * sub-section headers rather than on position or indentation.
 */
public class PackageDumpParser(private val zone: ZoneId = ZoneId.systemDefault()) {

    private companion object {
        val PACKAGE_HEADER = Regex("""^Package\s+\[([A-Za-z0-9_.]+)]\s*(?:\([^)]*\))?:?\s*$""")
        val VERSION_LINE = Regex("""versionCode=(\d+)""")
        val MIN_SDK = Regex("""minSdk=(\d+)""")
        val TARGET_SDK = Regex("""targetSdk=(\d+)""")
        val PERMISSION_SECTION = Regex("""^(requested|install|runtime|declared|granted)\s+permissions:\s*$""", RegexOption.IGNORE_CASE)
        val PERMISSION_ENTRY = Regex("""^([A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+)\s*(?::\s*(.*))?$""")
        val SHARED_USER = Regex("""sharedUser=\w*\{?[^}]*?name=([A-Za-z0-9_.]+)""")
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm:ss")
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
            .toFormatter()
    }

    private enum class PermSection { NONE, REQUESTED, GRANTABLE }

    public fun parse(dump: String): List<AppIdentity> {
        val results = mutableListOf<AppIdentity>()
        var builder: Builder? = null
        var section = PermSection.NONE

        for (raw in dump.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            val header = PACKAGE_HEADER.matchEntire(line)
            if (header != null) {
                builder?.let { results += it.build() }
                builder = Builder(header.groupValues[1])
                section = PermSection.NONE
                continue
            }

            val current = builder ?: continue

            val sectionMatch = PERMISSION_SECTION.matchEntire(line)
            if (sectionMatch != null) {
                section = when (sectionMatch.groupValues[1].lowercase()) {
                    "requested", "declared" -> PermSection.REQUESTED
                    else -> PermSection.GRANTABLE
                }
                continue
            }

            if (section != PermSection.NONE) {
                val entry = PERMISSION_ENTRY.matchEntire(line)
                if (entry != null) {
                    val permission = entry.groupValues[1]
                    val detail = entry.groupValues[2]
                    current.requested += permission
                    // A `requested permissions:` entry carries no grant state.
                    // An `install`/`runtime` entry does, and only `granted=true`
                    // means the app can actually use it.
                    if (section == PermSection.GRANTABLE && detail.contains("granted=true")) {
                        current.granted += permission
                    }
                    continue
                }
                // Anything that is not a bare permission ends the section.
                section = PermSection.NONE
            }

            applyField(current, line)
        }
        builder?.let { results += it.build() }
        return results
    }

    private fun applyField(builder: Builder, line: String) {
        when {
            line.startsWith("userId=") ->
                builder.uid = line.removePrefix("userId=").trim().toIntOrNull() ?: builder.uid
            line.startsWith("versionName=") ->
                builder.versionName = line.removePrefix("versionName=").trim()
            line.startsWith("installerPackageName=") ->
                builder.installer = line.removePrefix("installerPackageName=").trim()
                    .takeUnless { it.isEmpty() || it == "null" }
            line.startsWith("firstInstallTime=") ->
                builder.firstInstall = parseTime(line.removePrefix("firstInstallTime=").trim())
            line.startsWith("lastUpdateTime=") ->
                builder.lastUpdate = parseTime(line.removePrefix("lastUpdateTime=").trim())
            line.startsWith("codePath=") -> {
                val path = line.removePrefix("codePath=").trim()
                builder.codePath = path
                // Apps on the system image are pre-installed; `dumpsys` also
                // reports this via flags, but codePath is present on every release.
                if (path.startsWith("/system") || path.startsWith("/vendor") ||
                    path.startsWith("/product") || path.startsWith("/apex")
                ) {
                    builder.isSystem = true
                }
            }
            line.startsWith("flags=[") -> {
                val flags = line.substringAfter('[').substringBeforeLast(']')
                builder.isDebuggable = flags.contains("DEBUGGABLE")
                builder.allowsBackup = flags.contains("ALLOW_BACKUP")
                if (flags.contains("SYSTEM")) builder.isSystem = true
            }
            line.startsWith("sharedUser=") || line.startsWith("sharedUserId=") ->
                builder.sharedUserId = SHARED_USER.find(line)?.groupValues?.get(1)
                    ?: line.substringAfter('=').trim().takeUnless { it.isEmpty() || it == "null" }
            else -> {
                VERSION_LINE.find(line)?.let { builder.versionCode = it.groupValues[1].toLongOrNull() ?: 0L }
                MIN_SDK.find(line)?.let { builder.minSdk = it.groupValues[1].toIntOrNull() ?: 0 }
                TARGET_SDK.find(line)?.let { builder.targetSdk = it.groupValues[1].toIntOrNull() ?: 0 }
            }
        }
    }

    private fun parseTime(text: String): Long = try {
        LocalDateTime.parse(text, TIME_FORMAT).atZone(zone).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        0L
    }

    private class Builder(val packageName: String) {
        var uid: Int = -1
        var versionName: String? = null
        var versionCode: Long = 0
        var targetSdk: Int = 0
        var minSdk: Int = 0
        var installer: String? = null
        var firstInstall: Long = 0
        var lastUpdate: Long = 0
        var isSystem: Boolean = false
        var isDebuggable: Boolean = false
        var allowsBackup: Boolean = false
        var sharedUserId: String? = null
        var codePath: String? = null
        val requested: MutableSet<String> = linkedSetOf()
        val granted: MutableSet<String> = linkedSetOf()

        fun build(): AppIdentity = AppIdentity(
            packageName = packageName,
            label = packageName,
            uid = uid,
            versionName = versionName,
            versionCode = versionCode,
            targetSdk = targetSdk,
            minSdk = minSdk,
            installerPackage = installer,
            firstInstallMs = firstInstall,
            lastUpdateMs = lastUpdate,
            isSystem = isSystem,
            isDebuggable = isDebuggable,
            allowsBackup = allowsBackup,
            sharedUserId = sharedUserId,
            requestedPermissions = requested.toSet(),
            grantedPermissions = granted.toSet(),
        )
    }
}
