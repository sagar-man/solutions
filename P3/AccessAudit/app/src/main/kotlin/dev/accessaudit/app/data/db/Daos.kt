package dev.accessaudit.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A joined row for the app list: identity plus its latest verdict. */
public data class AppWithRisk(
    val packageName: String,
    val label: String,
    val installSource: String,
    val isSystem: Boolean,
    val riskScore: Int,
    val riskLevel: String,
    val observedKinds: String,
    val findingCount: Int,
)

/** Per-op totals for the detail screen, summed across the scans in range. */
public data class OpSummary(
    val op: String,
    val accessKind: String,
    val mode: String,
    val total: Int,
    val background: Int,
    val denied: Int,
    val lastAccessMs: Long,
    val totalDurationMs: Long,
)

/** "Which access class is used by how many apps", for the dashboard. */
public data class KindUsage(val accessKind: String, val appCount: Int, val totalAccesses: Int)

@Dao
public interface ScanDao {
    @Insert
    public suspend fun insert(scan: ScanEntity): Long

    @Query("SELECT * FROM scans ORDER BY startedAtMs DESC LIMIT 1")
    public suspend fun latest(): ScanEntity?

    @Query("SELECT * FROM scans ORDER BY startedAtMs DESC LIMIT 1")
    public fun observeLatest(): Flow<ScanEntity?>

    @Query("SELECT * FROM scans ORDER BY startedAtMs DESC LIMIT :limit")
    public suspend fun recent(limit: Int): List<ScanEntity>

    /** Trims history so the database cannot grow without bound. */
    @Query("DELETE FROM scans WHERE startedAtMs < :cutoffMs")
    public suspend fun deleteOlderThan(cutoffMs: Long): Int
}

@Dao
public interface AppDao {
    @Upsert
    public suspend fun upsertAll(apps: List<AppEntity>)

    @Query("SELECT * FROM apps WHERE packageName = :packageName")
    public suspend fun byPackage(packageName: String): AppEntity?

    @Query("SELECT signingSha256 FROM apps WHERE packageName = :packageName")
    public suspend fun signingOf(packageName: String): String?

    @Query("DELETE FROM apps WHERE lastSeenScanId < :scanId")
    public suspend fun deleteMissingSince(scanId: Long): Int
}

@Dao
public interface AccessDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun insertAll(events: List<AccessEventEntity>)

    /**
     * Per-op totals for one app across every scan in range.
     *
     * Rows are stored per state, so background use is recovered with a conditional
     * sum rather than a second query.
     */
    @Query(
        """
        SELECT op,
               accessKind AS accessKind,
               MAX(mode) AS mode,
               SUM(count) AS total,
               SUM(CASE WHEN state IN ('BACKGROUND', 'CACHED') THEN count ELSE 0 END) AS background,
               SUM(deniedCount) AS denied,
               MAX(lastAccessMs) AS lastAccessMs,
               SUM(totalDurationMs) AS totalDurationMs
        FROM access_events
        WHERE packageName = :packageName AND scanId IN (SELECT id FROM scans WHERE startedAtMs >= :sinceMs)
        GROUP BY op, accessKind
        ORDER BY background DESC, total DESC
        """,
    )
    public fun observeOpsFor(packageName: String, sinceMs: Long): Flow<List<OpSummary>>

    @Query(
        """
        SELECT accessKind AS accessKind,
               COUNT(DISTINCT packageName) AS appCount,
               SUM(count) AS totalAccesses
        FROM access_events
        WHERE scanId = :scanId
        GROUP BY accessKind
        ORDER BY totalAccesses DESC
        """,
    )
    public fun observeKindUsage(scanId: Long): Flow<List<KindUsage>>

    /** Timeline for one app and op, newest first. */
    @Query(
        """
        SELECT * FROM access_events
        WHERE packageName = :packageName AND op = :op
        ORDER BY lastAccessMs DESC
        LIMIT :limit
        """,
    )
    public suspend fun historyFor(packageName: String, op: String, limit: Int): List<AccessEventEntity>

    @Query("DELETE FROM access_events WHERE scanId NOT IN (SELECT id FROM scans)")
    public suspend fun deleteOrphans(): Int
}

@Dao
public interface FindingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun insertAll(findings: List<FindingEntity>)

    @Query(
        """
        SELECT * FROM findings
        WHERE scanId = :scanId AND severityWeight >= :minWeight
        ORDER BY severityWeight DESC, packageName
        """,
    )
    public fun observeForScan(scanId: Long, minWeight: Int): Flow<List<FindingEntity>>

    @Query("SELECT * FROM findings WHERE scanId = :scanId AND packageName = :packageName ORDER BY severityWeight DESC")
    public fun observeForApp(scanId: Long, packageName: String): Flow<List<FindingEntity>>

    /**
     * When a rule first fired for an app, so the UI can say "new since Tuesday"
     * instead of re-alerting on a finding the user has already seen.
     */
    @Query(
        """
        SELECT MIN(s.startedAtMs) FROM findings f
        JOIN scans s ON s.id = f.scanId
        WHERE f.packageName = :packageName AND f.ruleId = :ruleId
        """,
    )
    public suspend fun firstSeenMs(packageName: String, ruleId: String): Long?

    @Query("DELETE FROM findings WHERE scanId NOT IN (SELECT id FROM scans)")
    public suspend fun deleteOrphans(): Int
}

@Dao
public interface AssessmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun insertAll(assessments: List<AssessmentEntity>)

    @Query(
        """
        SELECT a.packageName AS packageName,
               app.label AS label,
               app.installSource AS installSource,
               app.isSystem AS isSystem,
               a.riskScore AS riskScore,
               a.riskLevel AS riskLevel,
               a.observedKinds AS observedKinds,
               (SELECT COUNT(*) FROM findings f
                 WHERE f.scanId = a.scanId AND f.packageName = a.packageName) AS findingCount
        FROM assessments a
        JOIN apps app ON app.packageName = a.packageName
        WHERE a.scanId = :scanId AND (:includeSystem OR app.isSystem = 0)
        ORDER BY a.riskScore DESC, app.label ASC
        """,
    )
    public fun observeForScan(scanId: Long, includeSystem: Boolean): Flow<List<AppWithRisk>>

    /** Score history for one app, for the trend line on the detail screen. */
    @Query(
        """
        SELECT s.startedAtMs AS startedAtMs, a.riskScore AS riskScore
        FROM assessments a JOIN scans s ON s.id = a.scanId
        WHERE a.packageName = :packageName
        ORDER BY s.startedAtMs ASC
        """,
    )
    public suspend fun scoreHistory(packageName: String): List<ScorePoint>

    @Query("DELETE FROM assessments WHERE scanId NOT IN (SELECT id FROM scans)")
    public suspend fun deleteOrphans(): Int
}

public data class ScorePoint(val startedAtMs: Long, val riskScore: Int)

@Dao
public interface SuppressionDao {
    @Upsert
    public suspend fun upsert(suppression: SuppressionEntity)

    @Query("DELETE FROM suppressions WHERE packageName = :packageName AND ruleId = :ruleId")
    public suspend fun delete(packageName: String, ruleId: String)

    @Query("SELECT * FROM suppressions")
    public suspend fun all(): List<SuppressionEntity>

    @Query("SELECT * FROM suppressions")
    public fun observeAll(): Flow<List<SuppressionEntity>>
}
