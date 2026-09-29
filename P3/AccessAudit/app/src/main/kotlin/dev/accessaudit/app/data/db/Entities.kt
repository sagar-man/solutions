package dev.accessaudit.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One audit pass. Keeping scans as rows rather than overwriting a single
 * snapshot is what lets the app show "this app started doing this on Tuesday",
 * which the platform's own 24-hour window cannot.
 */
@Entity(tableName = "scans")
public data class ScanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtMs: Long,
    val windowStartMs: Long,
    val deviceSdkInt: Int,
    /** Comma-separated collector ids that were live, so gaps stay explainable. */
    val collectors: String,
    val appsScanned: Int,
    val appsFlagged: Int,
)

/** Static identity of an app as of a given scan. */
@Entity(
    tableName = "apps",
    indices = [Index("packageName", unique = true)],
)
public data class AppEntity(
    @PrimaryKey val packageName: String,
    val label: String,
    val uid: Int,
    val versionName: String?,
    val versionCode: Long,
    val targetSdk: Int,
    val installerPackage: String?,
    val installSource: String,
    val isSystem: Boolean,
    val firstInstallMs: Long,
    val lastUpdateMs: Long,
    /** First signing certificate digest; a change here means the app was replaced. */
    val signingSha256: String?,
    val lastSeenScanId: Long,
)

/**
 * An observed access, deduplicated per (scan, package, op, state).
 *
 * The platform reports aggregate counts rather than individual syscalls, so a row
 * is "this app used this op N times in this state during this scan's window".
 */
@Entity(
    tableName = "access_events",
    primaryKeys = ["scanId", "packageName", "op", "state"],
    indices = [Index("packageName"), Index("scanId"), Index("op")],
)
public data class AccessEventEntity(
    val scanId: Long,
    val packageName: String,
    val op: String,
    val accessKind: String,
    val mode: String,
    val state: String,
    val count: Int,
    val deniedCount: Int,
    val lastAccessMs: Long,
    val totalDurationMs: Long,
    val collectorId: String,
)

/** A rule hit, stored per scan so a finding's first and last appearance is known. */
@Entity(
    tableName = "findings",
    primaryKeys = ["scanId", "packageName", "ruleId"],
    indices = [Index("packageName"), Index("severity")],
)
public data class FindingEntity(
    val scanId: Long,
    val packageName: String,
    val ruleId: String,
    val title: String,
    val severity: String,
    val severityWeight: Int,
    val rationale: String,
    /** Evidence lines joined with a newline; this table is read, not queried, on them. */
    val evidence: String,
    val recommendation: String,
    val accessKinds: String,
    val lastSeenMs: Long,
)

/** Per-app risk verdict for a scan. */
@Entity(
    tableName = "assessments",
    primaryKeys = ["scanId", "packageName"],
    indices = [Index("riskScore")],
)
public data class AssessmentEntity(
    val scanId: Long,
    val packageName: String,
    val riskScore: Int,
    val riskLevel: String,
    val observedKinds: String,
    val foregroundTimeMs: Long,
)

/**
 * A user's decision to stop being told about one behaviour of one app.
 *
 * Scoped to `packageName` + `ruleId` on purpose: accepting that your maps app
 * uses background location must not also silence its SMS access.
 */
@Entity(tableName = "suppressions", primaryKeys = ["packageName", "ruleId"])
public data class SuppressionEntity(
    val packageName: String,
    val ruleId: String,
    val createdAtMs: Long,
    val note: String = "",
)
