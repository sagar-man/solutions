package dev.accessaudit.core.model

/**
 * How much a finding should move the needle.
 *
 * [weight] feeds the 0..100 risk score, and the numbers are chosen against both
 * the [RiskLevel] bands and the engine's 0.5 decay factor (see
 * [dev.accessaudit.core.risk.RiskEngine.score]). Repeated findings of one
 * severity saturate at twice its weight, so each weight is set so that:
 *
 *  - one finding lands in its intended band (one HIGH reaches SUSPICIOUS, one
 *    CRITICAL reaches HIGH_RISK, one LOW stays CLEAN);
 *  - and an unlimited pile of a lower severity still cannot outrank a single
 *    finding of the next severity up (2*LOW < MEDIUM, 2*MEDIUM < HIGH,
 *    2*HIGH < CRITICAL).
 *
 * That second property is the whole point: an all-files-access sideload must
 * never be buried under a dozen cosmetic hygiene notes.
 */
public enum class Severity(public val weight: Int) {
    INFO(0),
    LOW(6),
    MEDIUM(18),
    HIGH(40),
    CRITICAL(85),
    ;

    public val isSuspicious: Boolean get() = ordinal >= MEDIUM.ordinal
}

/** Overall verdict band for an app, derived from its score. */
public enum class RiskLevel(public val minScore: Int) {
    CLEAN(0),
    NOTABLE(15),
    SUSPICIOUS(35),
    HIGH_RISK(60),
    ;

    public companion object {
        public fun forScore(score: Int): RiskLevel =
            entries.last { score >= it.minScore }
    }
}

/**
 * One concrete reason an app was flagged.
 *
 * [evidence] is the part that makes the tool usable rather than alarmist: every
 * finding has to point at the observation that produced it, so a developer can
 * disagree with the rule and still trust the data.
 */
public data class Finding(
    val ruleId: String,
    val packageName: String,
    val title: String,
    val severity: Severity,
    /** Why this pattern is worth a human's attention. */
    val rationale: String,
    val evidence: List<String> = emptyList(),
    val recommendation: String = "",
    /** Access classes implicated, so the UI can group findings by read/write/execute. */
    val kinds: Set<AccessKind> = emptySet(),
    val firstSeenMs: Long = 0,
    val lastSeenMs: Long = 0,
)

/** The result of running the rule set over one app. */
public data class AppAssessment(
    val app: AppIdentity,
    val score: Int,
    val level: RiskLevel,
    val findings: List<Finding>,
    /**
     * Per-operation roll-up, carried through so the report can render the
     * "which app used which access" table without re-joining against the
     * profiles it was built from.
     */
    val tallies: List<OpTally> = emptyList(),
    /** Access classes this app was actually observed exercising. */
    val observedKinds: Set<AccessKind> = emptySet(),
    /** Foreground milliseconds in the window, when usage stats were available. */
    val foregroundTimeMs: Long = 0,
) {
    public val isFlagged: Boolean get() = findings.any { it.severity.isSuspicious }

    public fun findingsBySeverity(): List<Finding> = findings.sortedWith(
        compareByDescending<Finding> { it.severity.ordinal }.thenBy { it.ruleId },
    )

    /** Observed operations, worst-first, for the detail screen and the CSV matrix. */
    public fun talliesByRisk(): List<OpTally> = tallies
        .filter { it.totalCount > 0 || it.deniedCount > 0 }
        .sortedWith(
            compareByDescending<OpTally> { it.backgroundCount }
                .thenByDescending { it.totalCount },
        )
}

/** A full audit pass over the device. */
public data class AuditReport(
    val generatedAtMs: Long,
    /** Start of the observation window the events came from. */
    val windowStartMs: Long,
    val windowEndMs: Long,
    val deviceSdkInt: Int,
    /** Which collector tiers were live for this pass, so gaps are self-documenting. */
    val activeCollectors: List<String>,
    val assessments: List<AppAssessment>,
) {
    public val flagged: List<AppAssessment>
        get() = assessments.filter { it.isFlagged }.sortedByDescending { it.score }

    public fun byLevel(level: RiskLevel): List<AppAssessment> = assessments.filter { it.level == level }
}
