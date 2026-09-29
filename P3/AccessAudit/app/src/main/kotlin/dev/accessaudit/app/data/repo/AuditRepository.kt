package dev.accessaudit.app.data.repo

import android.os.Build
import dev.accessaudit.app.collect.AccessCollector
import dev.accessaudit.app.collect.CollectionResult
import dev.accessaudit.app.collect.CollectorTier
import dev.accessaudit.app.data.db.AccessEventEntity
import dev.accessaudit.app.data.db.AppDatabase
import dev.accessaudit.app.data.db.AppEntity
import dev.accessaudit.app.data.db.AssessmentEntity
import dev.accessaudit.app.data.db.FindingEntity
import dev.accessaudit.app.data.db.ScanEntity
import dev.accessaudit.app.data.db.SuppressionEntity
import dev.accessaudit.core.AccessAudit
import dev.accessaudit.core.model.AccessEvent
import dev.accessaudit.core.model.AppAccessProfile
import dev.accessaudit.core.model.AppIdentity
import dev.accessaudit.core.model.AuditReport
import dev.accessaudit.core.model.OpTally
import dev.accessaudit.core.model.Severity
import dev.accessaudit.core.model.UidState
import dev.accessaudit.core.ops.OpCatalog
import dev.accessaudit.core.risk.AuditContext
import kotlinx.coroutines.flow.Flow

/** What a finished scan tells the UI. */
public data class ScanOutcome(
    val scanId: Long,
    val report: AuditReport,
    /** Per-collector availability, so the UI can explain what was NOT covered. */
    val collectorStatus: List<CollectorStatus>,
)

public data class CollectorStatus(
    val collectorId: String,
    val tier: CollectorTier,
    val available: Boolean,
    val reason: String?,
    val eventCount: Int,
)

/**
 * Runs a scan and persists it.
 *
 * The flow is: every collector that can run does, their output is merged into one
 * profile per package, `:core` scores it, and the result is written as a new scan
 * row. Nothing is overwritten, so history accumulates beyond the roughly 24 hours
 * the platform itself retains.
 */
public class AuditRepository(
    private val db: AppDatabase,
    private val collectors: List<AccessCollector>,
) {

    public suspend fun collectorStatuses(): List<CollectorStatus> = collectors.map { collector ->
        val available = runCatching { collector.isAvailable() }.getOrDefault(false)
        CollectorStatus(collector.id, collector.tier, available, null, 0)
    }

    public suspend fun runScan(
        windowHours: Long = DEFAULT_WINDOW_HOURS,
        nowMs: Long = System.currentTimeMillis(),
    ): ScanOutcome {
        val windowStartMs = nowMs - windowHours * 3_600_000L

        val results = collectors.map { collector ->
            // A collector that throws must not abort the scan: the report is more
            // useful with one tier missing than not produced at all.
            runCatching { collector.collect(windowStartMs, nowMs) }.getOrElse { error ->
                CollectionResult(
                    collectorId = collector.id,
                    tier = collector.tier,
                    available = false,
                    unavailableReason = "Collector failed: ${error::class.simpleName}: ${error.message}",
                )
            }
        }

        val identities = mergeIdentities(results)
        val eventsByPackage = results.flatMap { it.events }.groupBy { it.packageName }
        val foregroundTime = results.fold(emptyMap<String, Long>()) { acc, result ->
            acc + result.foregroundTimeMs
        }
        val activeSources = results.filter { it.available }.map { it.collectorId }

        val profiles = identities.map { identity ->
            val events = eventsByPackage[identity.packageName].orEmpty()
            AppAccessProfile(
                app = identity,
                tallies = toTallies(events),
                events = events,
                foregroundTimeMs = foregroundTime[identity.packageName] ?: 0L,
                sources = buildSet {
                    addAll(activeSources)
                    if (events.isNotEmpty()) add("appops")
                },
            )
        }

        val suppressions = db.suppressions().all()
            .map { "${it.packageName}:${it.ruleId}" }
            .toSet()

        val report = AccessAudit.analyze(
            profiles = profiles,
            context = AuditContext(
                nowMs = nowMs,
                windowStartMs = windowStartMs,
                deviceSdkInt = Build.VERSION.SDK_INT,
                suppressions = suppressions,
            ),
            activeCollectors = activeSources,
        )

        val scanId = persist(report, results, nowMs, windowStartMs)
        return ScanOutcome(
            scanId = scanId,
            report = report,
            collectorStatus = results.map { result ->
                CollectorStatus(
                    collectorId = result.collectorId,
                    tier = result.tier,
                    available = result.available,
                    reason = result.unavailableReason,
                    eventCount = result.events.size,
                )
            },
        )
    }

    /**
     * Merges identities from every collector that produced them, preferring the
     * richest record. Only [dev.accessaudit.app.collect.PackageInventoryCollector]
     * normally supplies these, but a package seen only in the access log still
     * needs a stub so its behaviour is not silently dropped.
     */
    private fun mergeIdentities(results: List<CollectionResult>): List<AppIdentity> {
        val merged = LinkedHashMap<String, AppIdentity>()
        for (result in results) {
            for (identity in result.identities) {
                val existing = merged[identity.packageName]
                merged[identity.packageName] =
                    if (existing == null || identity.requestedPermissions.size > existing.requestedPermissions.size) {
                        identity
                    } else {
                        existing
                    }
            }
        }
        for (result in results) {
            for (packageName in result.events.map { it.packageName }.distinct()) {
                merged.getOrPut(packageName) { AppIdentity(packageName = packageName) }
            }
        }
        return merged.values.toList()
    }

    /** Collapses raw events into per-op roll-ups, matching the parser's own logic. */
    private fun toTallies(events: List<AccessEvent>): List<OpTally> =
        events.groupBy { it.op }.map { (op, opEvents) ->
            val allowed = opEvents.filter { !it.denied }
            OpTally(
                op = op,
                kind = OpCatalog.kindOf(op),
                mode = opEvents.first().mode,
                totalCount = allowed.sumOf { it.count },
                foregroundCount = allowed.filter { !it.isBackground }.sumOf { it.count },
                backgroundCount = allowed.filter { it.isBackground }.sumOf { it.count },
                deniedCount = opEvents.filter { it.denied }.sumOf { it.count },
                lastAccessMs = allowed.maxOfOrNull { it.timestampMs } ?: 0L,
                lastBackgroundAccessMs = allowed.filter { it.isBackground }
                    .maxOfOrNull { it.timestampMs } ?: 0L,
                totalDurationMs = allowed.sumOf { it.durationMs },
            )
        }

    private suspend fun persist(
        report: AuditReport,
        results: List<CollectionResult>,
        nowMs: Long,
        windowStartMs: Long,
    ): Long {
        val scanId = db.scans().insert(
            ScanEntity(
                startedAtMs = nowMs,
                windowStartMs = windowStartMs,
                deviceSdkInt = report.deviceSdkInt,
                collectors = report.activeCollectors.joinToString(","),
                appsScanned = report.assessments.size,
                appsFlagged = report.flagged.size,
            ),
        )

        db.apps().upsertAll(
            report.assessments.map { assessment ->
                AppEntity(
                    packageName = assessment.app.packageName,
                    label = assessment.app.label,
                    uid = assessment.app.uid,
                    versionName = assessment.app.versionName,
                    versionCode = assessment.app.versionCode,
                    targetSdk = assessment.app.targetSdk,
                    installerPackage = assessment.app.installerPackage,
                    installSource = assessment.app.installSource.name,
                    isSystem = assessment.app.isSystem,
                    firstInstallMs = assessment.app.firstInstallMs,
                    lastUpdateMs = assessment.app.lastUpdateMs,
                    signingSha256 = assessment.app.signingSha256.firstOrNull(),
                    lastSeenScanId = scanId,
                )
            },
        )

        db.assessments().insertAll(
            report.assessments.map { assessment ->
                AssessmentEntity(
                    scanId = scanId,
                    packageName = assessment.app.packageName,
                    riskScore = assessment.score,
                    riskLevel = assessment.level.name,
                    observedKinds = assessment.observedKinds.joinToString(",") { it.label },
                    foregroundTimeMs = assessment.foregroundTimeMs,
                )
            },
        )

        db.findings().insertAll(
            report.assessments.flatMap { assessment ->
                assessment.findings.map { finding ->
                    FindingEntity(
                        scanId = scanId,
                        packageName = assessment.app.packageName,
                        ruleId = finding.ruleId,
                        title = finding.title,
                        severity = finding.severity.name,
                        severityWeight = finding.severity.weight,
                        rationale = finding.rationale,
                        evidence = finding.evidence.joinToString("\n"),
                        recommendation = finding.recommendation,
                        accessKinds = finding.kinds.joinToString(",") { it.label },
                        lastSeenMs = finding.lastSeenMs,
                    )
                }
            },
        )

        // Events are stored per (package, op, state) to match the primary key; the
        // tallies already hold the foreground/background split we need.
        db.access().insertAll(
            report.assessments.flatMap { assessment ->
                assessment.tallies.flatMap { tally ->
                    buildList {
                        if (tally.foregroundCount > 0 || tally.deniedCount > 0) {
                            add(
                                eventRow(
                                    scanId, assessment.app.packageName, tally,
                                    UidState.FOREGROUND, tally.foregroundCount, tally.deniedCount,
                                    tally.lastAccessMs, results,
                                ),
                            )
                        }
                        if (tally.backgroundCount > 0) {
                            add(
                                eventRow(
                                    scanId, assessment.app.packageName, tally,
                                    UidState.BACKGROUND, tally.backgroundCount, 0,
                                    tally.lastBackgroundAccessMs, results,
                                ),
                            )
                        }
                    }
                }
            },
        )

        db.scans().deleteOlderThan(nowMs - HISTORY_RETENTION_MS)
        db.access().deleteOrphans()
        db.findings().deleteOrphans()
        db.assessments().deleteOrphans()
        return scanId
    }

    private fun eventRow(
        scanId: Long,
        packageName: String,
        tally: OpTally,
        state: UidState,
        count: Int,
        deniedCount: Int,
        lastAccessMs: Long,
        results: List<CollectionResult>,
    ) = AccessEventEntity(
        scanId = scanId,
        packageName = packageName,
        op = tally.op,
        accessKind = tally.kind.label,
        mode = tally.mode.name,
        state = state.name,
        count = count,
        deniedCount = deniedCount,
        lastAccessMs = lastAccessMs,
        totalDurationMs = tally.totalDurationMs,
        collectorId = results.firstOrNull { it.tier == CollectorTier.APP_OPS && it.available }
            ?.collectorId ?: "unknown",
    )

    // ---- reads for the UI ----------------------------------------------------

    public fun observeLatestScan(): Flow<ScanEntity?> = db.scans().observeLatest()

    public fun observeApps(scanId: Long, includeSystem: Boolean) =
        db.assessments().observeForScan(scanId, includeSystem)

    public fun observeFindings(scanId: Long, minSeverity: Severity = Severity.MEDIUM) =
        db.findings().observeForScan(scanId, minSeverity.weight)

    public fun observeFindingsForApp(scanId: Long, packageName: String) =
        db.findings().observeForApp(scanId, packageName)

    public fun observeOps(packageName: String, sinceMs: Long) =
        db.access().observeOpsFor(packageName, sinceMs)

    public fun observeKindUsage(scanId: Long) = db.access().observeKindUsage(scanId)

    public suspend fun scoreHistory(packageName: String) = db.assessments().scoreHistory(packageName)

    public suspend fun suppress(packageName: String, ruleId: String, note: String = "") {
        db.suppressions().upsert(
            SuppressionEntity(
                packageName = packageName,
                ruleId = ruleId,
                createdAtMs = System.currentTimeMillis(),
                note = note,
            ),
        )
    }

    public suspend fun unsuppress(packageName: String, ruleId: String) {
        db.suppressions().delete(packageName, ruleId)
    }

    public fun observeSuppressions() = db.suppressions().observeAll()

    public suspend fun latestScan(): ScanEntity? = db.scans().latest()

    public companion object {
        public const val DEFAULT_WINDOW_HOURS: Long = 24

        /** Two weeks of scan history: long enough to spot a change, small enough to stay tiny. */
        public const val HISTORY_RETENTION_MS: Long = 14L * 24 * 3_600_000
    }
}
