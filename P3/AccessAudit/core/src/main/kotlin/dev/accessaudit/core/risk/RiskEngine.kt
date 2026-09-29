package dev.accessaudit.core.risk

import dev.accessaudit.core.model.AppAccessProfile
import dev.accessaudit.core.model.AppAssessment
import dev.accessaudit.core.model.AuditReport
import dev.accessaudit.core.model.Finding
import dev.accessaudit.core.model.OpTally
import dev.accessaudit.core.model.RiskLevel
import dev.accessaudit.core.model.Severity

/**
 * Runs the rule set over each app and turns the findings into a 0..100 score.
 *
 * Scoring is saturating rather than additive. A plain sum lets a dozen LOW
 * hygiene notes outrank one CRITICAL, which is precisely the failure that makes
 * security tools get ignored, so instead:
 *
 *  - the single worst finding sets a floor ([Severity.weight]);
 *  - every additional finding contributes with diminishing weight;
 *  - the total is clamped to 100.
 *
 * The result is that severity dominates and volume only ever breaks ties.
 */
public class RiskEngine(private val rules: List<Rule> = DEFAULT_RULES) {

    public fun assess(profile: AppAccessProfile, context: AuditContext): AppAssessment {
        val findings = rules.flatMap { rule ->
            runCatching { rule.evaluate(RuleInput(profile, context)) }
                // One malformed profile must not take the whole scan down; the
                // failure is surfaced as an INFO finding instead of a crash.
                .getOrElse { error ->
                    listOf(
                        Finding(
                            ruleId = "ENGINE_ERROR.${rule.id}",
                            packageName = profile.app.packageName,
                            title = "Rule ${rule.id} failed to run",
                            severity = Severity.INFO,
                            rationale = "This app was not fully evaluated, so its score may be low.",
                            evidence = listOf(error::class.simpleName + ": " + (error.message ?: "no message")),
                        ),
                    )
                }
        }
        val score = score(findings)
        return AppAssessment(
            app = profile.app,
            score = score,
            level = RiskLevel.forScore(score),
            findings = findings.sortedWith(
                compareByDescending<Finding> { it.severity.ordinal }.thenBy { it.ruleId },
            ),
            tallies = profile.tallies,
            observedKinds = profile.kindsAccessed(),
            foregroundTimeMs = profile.foregroundTimeMs,
        )
    }

    public fun score(findings: List<Finding>): Int {
        if (findings.isEmpty()) return 0
        val weights = findings.map { it.severity.weight }.sortedDescending()
        var total = weights.first().toDouble()
        // Each further finding counts half as much as the one before, so a run
        // of findings at one severity converges on twice that severity's weight
        // and can never reach the next severity's weight. See [Severity].
        var factor = 0.5
        for (weight in weights.drop(1)) {
            total += weight * factor
            factor *= 0.5
        }
        return total.toInt().coerceIn(0, 100)
    }

    public fun assessAll(
        profiles: List<AppAccessProfile>,
        context: AuditContext,
    ): List<AppAssessment> = profiles.map { assess(it, context) }.sortedByDescending { it.score }

    public fun report(
        profiles: List<AppAccessProfile>,
        context: AuditContext,
        activeCollectors: List<String>,
    ): AuditReport = AuditReport(
        generatedAtMs = context.nowMs,
        windowStartMs = context.windowStartMs,
        windowEndMs = context.nowMs,
        deviceSdkInt = context.deviceSdkInt,
        activeCollectors = activeCollectors,
        assessments = assessAll(profiles, context),
    )

    public companion object {
        /**
         * Median accesses per op across apps that used it, used by
         * [AccessFrequencyOutlierRule]. Apps that never touched an op are
         * excluded, otherwise the median of any rare op is always 0.
         */
        public fun deviceOpMedians(profiles: List<AppAccessProfile>): Map<String, Int> {
            val byOp = mutableMapOf<String, MutableList<Int>>()
            for (profile in profiles) {
                for (tally: OpTally in profile.tallies) {
                    if (tally.totalCount > 0) {
                        byOp.getOrPut(tally.op) { mutableListOf() } += tally.totalCount
                    }
                }
            }
            return byOp.mapValues { (_, counts) ->
                val sorted = counts.sorted()
                if (sorted.size % 2 == 1) {
                    sorted[sorted.size / 2]
                } else {
                    (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
                }
            }
        }
    }
}
