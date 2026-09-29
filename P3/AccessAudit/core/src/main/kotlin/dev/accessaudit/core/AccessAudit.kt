package dev.accessaudit.core

import dev.accessaudit.core.model.AppAccessProfile
import dev.accessaudit.core.model.AppIdentity
import dev.accessaudit.core.model.AuditReport
import dev.accessaudit.core.parse.AppOpsDumpParser
import dev.accessaudit.core.parse.PackageDumpParser
import dev.accessaudit.core.risk.AuditContext
import dev.accessaudit.core.risk.DEFAULT_RULES
import dev.accessaudit.core.risk.RiskEngine
import dev.accessaudit.core.risk.Rule
import java.time.ZoneId

/**
 * Entry point for both consumers of the engine: the Android app, which feeds it
 * profiles built from `PackageManager`/`AppOpsManager`, and the `:cli`, which
 * feeds it profiles parsed out of captured `adb shell dumpsys` output.
 *
 * Keeping one facade means the on-device report and the host-side report come
 * from identical logic - a rule cannot behave differently in the two paths.
 */
public object AccessAudit {

    public const val VERSION: String = "1.0.0"

    public fun engine(rules: List<Rule> = DEFAULT_RULES): RiskEngine = RiskEngine(rules)

    /**
     * Assesses pre-built profiles. The device-wide op medians are derived here
     * rather than passed in, so callers cannot forget to populate the baseline
     * that [dev.accessaudit.core.risk.AccessFrequencyOutlierRule] needs.
     */
    public fun analyze(
        profiles: List<AppAccessProfile>,
        context: AuditContext,
        activeCollectors: List<String>,
        rules: List<Rule> = DEFAULT_RULES,
    ): AuditReport {
        val enriched = context.copy(
            deviceOpMedians = context.deviceOpMedians.ifEmpty { RiskEngine.deviceOpMedians(profiles) },
        )
        return engine(rules).report(profiles, enriched, activeCollectors)
    }

    /**
     * Host-side path: analyse raw `dumpsys` text captured by
     * `tools/adb-access-audit.sh`, with no app installed on the device.
     *
     * @param packageDump output of `dumpsys package packages`
     * @param appOpsDump output of `dumpsys appops`
     * @param nowMs the capture time; appop ages are relative to it, so passing a
     *   wall clock far from the capture time skews every timestamp.
     */
    public fun analyzeDumps(
        packageDump: String,
        appOpsDump: String,
        nowMs: Long,
        windowStartMs: Long = nowMs - 24 * 3_600_000L,
        deviceSdkInt: Int = 0,
        zone: ZoneId = ZoneId.systemDefault(),
        suppressions: Set<String> = emptySet(),
        rules: List<Rule> = DEFAULT_RULES,
    ): AuditReport {
        val identities = PackageDumpParser(zone).parse(packageDump)
        val ops = AppOpsDumpParser(nowMs, zone).parse(appOpsDump)

        // An app can show up in the appops dump but not in the package dump (a
        // partial capture, or a package removed between the two commands). Keep
        // it with a synthetic identity rather than dropping the access data.
        val byPackage = identities.associateByTo(LinkedHashMap()) { it.packageName }
        for (pkg in ops.packages()) {
            byPackage.getOrPut(pkg) { AppIdentity(packageName = pkg) }
        }

        val profiles = byPackage.values.map { identity ->
            val tallies = ops.talliesFor(identity.packageName)
            AppAccessProfile(
                app = identity,
                tallies = tallies,
                events = ops.entriesFor(identity.packageName).flatMap { it.events },
                sources = buildSet {
                    if (identity.requestedPermissions.isNotEmpty()) add("package-dump")
                    if (tallies.isNotEmpty()) add("appops-dump")
                },
            )
        }

        return analyze(
            profiles = profiles,
            context = AuditContext(
                nowMs = nowMs,
                windowStartMs = windowStartMs,
                deviceSdkInt = deviceSdkInt,
                suppressions = suppressions,
            ),
            activeCollectors = listOf("package-dump", "appops-dump"),
            rules = rules,
        )
    }
}
