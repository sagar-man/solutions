package dev.accessaudit.core.risk

import dev.accessaudit.core.model.Finding
import dev.accessaudit.core.model.RiskLevel
import dev.accessaudit.core.model.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val P = "android.permission."

class RiskEngineTest {

    private val engine = RiskEngine()

    private fun finding(severity: Severity, id: String = "R") =
        Finding(ruleId = id, packageName = "com.example.app", title = id, severity = severity, rationale = "")

    @Test
    fun `no findings scores zero and reads as clean`() {
        assertEquals(0, engine.score(emptyList()))
        assertEquals(RiskLevel.CLEAN, RiskLevel.forScore(0))
    }

    @Test
    fun `the worst finding sets the floor`() {
        val score = engine.score(listOf(finding(Severity.HIGH)))
        assertEquals(Severity.HIGH.weight, score)
    }

    @Test
    fun `a pile of low findings cannot outrank one critical`() {
        // This is the property the whole scoring design exists to guarantee:
        // volume of trivia must never bury a single severe problem.
        val manyLows = engine.score(List(20) { finding(Severity.LOW, "R$it") })
        val oneCritical = engine.score(listOf(finding(Severity.CRITICAL)))
        assertTrue(
            oneCritical > manyLows,
            "20 LOW findings scored $manyLows but one CRITICAL scored $oneCritical",
        )
    }

    @Test
    fun `a pile of mediums cannot outrank one high`() {
        val manyMediums = engine.score(List(20) { finding(Severity.MEDIUM, "R$it") })
        assertTrue(engine.score(listOf(finding(Severity.HIGH))) > manyMediums)
    }

    @Test
    fun `extra findings of the same severity still raise the score`() {
        val one = engine.score(listOf(finding(Severity.MEDIUM, "A")))
        val three = engine.score(listOf(finding(Severity.MEDIUM, "A"), finding(Severity.MEDIUM, "B"), finding(Severity.MEDIUM, "C")))
        assertTrue(three > one, "volume should break ties within a severity band")
    }

    @Test
    fun `the score is clamped to 100`() {
        val score = engine.score(List(30) { finding(Severity.CRITICAL, "R$it") })
        assertEquals(100, score)
    }

    @Test
    fun `risk bands map from the score`() {
        assertEquals(RiskLevel.CLEAN, RiskLevel.forScore(0))
        assertEquals(RiskLevel.CLEAN, RiskLevel.forScore(14))
        assertEquals(RiskLevel.NOTABLE, RiskLevel.forScore(15))
        assertEquals(RiskLevel.SUSPICIOUS, RiskLevel.forScore(35))
        assertEquals(RiskLevel.HIGH_RISK, RiskLevel.forScore(60))
        assertEquals(RiskLevel.HIGH_RISK, RiskLevel.forScore(100))
    }

    @Test
    fun `a LOW-only app is not reported as flagged`() {
        // Hygiene notes must not produce an alert, or users stop reading alerts.
        val assessment = engine.assess(
            profile(app = app(granted = setOf("${P}CAMERA"))),
            context(windowHours = 48),
        )
        assertTrue(assessment.findings.isNotEmpty())
        assertTrue(assessment.findings.all { it.severity == Severity.LOW })
        assertTrue(!assessment.isFlagged, "an app with only LOW findings should not be flagged")
    }

    @Test
    fun `a spyware-shaped app lands in the top band`() {
        val assessment = engine.assess(
            profile(
                app = app(
                    packageName = "com.bad.actor",
                    installer = null,
                    granted = setOf(
                        "${P}INTERNET",
                        "${P}READ_SMS",
                        "${P}RECORD_AUDIO",
                        "${P}ACCESS_FINE_LOCATION",
                        "${P}READ_CONTACTS",
                        "${P}SYSTEM_ALERT_WINDOW",
                        "${P}MANAGE_EXTERNAL_STORAGE",
                    ),
                    accessibilityServices = listOf("com.bad.actor/.Watcher"),
                ),
                tallies = listOf(
                    tally("READ_SMS", total = 12, background = 12),
                    tally("RECORD_AUDIO", total = 6, background = 6, durationMs = 400_000),
                    tally("FINE_LOCATION", total = 30, background = 28),
                    tally("READ_CONTACTS", total = 4, background = 4),
                ),
            ),
            context(),
        )
        assertEquals(RiskLevel.HIGH_RISK, assessment.level)
        assertTrue(assessment.isFlagged)
        assertTrue(
            assessment.findings.any { it.severity == Severity.CRITICAL },
            "expected at least one CRITICAL finding, got ${assessment.findings.map { it.ruleId to it.severity }}",
        )
    }

    @Test
    fun `an ordinary app stays clean`() {
        val assessment = engine.assess(
            profile(
                app = app(packageName = "com.example.notes", granted = setOf("${P}INTERNET")),
                tallies = listOf(tally("WRITE_CLIPBOARD", total = 3)),
            ),
            context(),
        )
        assertEquals(
            RiskLevel.CLEAN,
            assessment.level,
            "unexpected findings: ${assessment.findings.map { it.ruleId }}",
        )
    }

    @Test
    fun `a rule that throws does not take the scan down`() {
        val exploding = object : Rule {
            override val id = "BOOM"
            override val description = "always throws"
            override fun evaluate(input: RuleInput): List<Finding> = error("kaboom")
        }
        val assessment = RiskEngine(listOf(exploding)).assess(profile(), context())
        assertEquals(1, assessment.findings.size)
        assertEquals("ENGINE_ERROR.BOOM", assessment.findings.single().ruleId)
        assertEquals(Severity.INFO, assessment.findings.single().severity)
    }

    @Test
    fun `device medians ignore apps that never used the op`() {
        val profiles = listOf(
            profile(app = app(packageName = "a"), tallies = listOf(tally("FINE_LOCATION", total = 10))),
            profile(app = app(packageName = "b"), tallies = listOf(tally("FINE_LOCATION", total = 20))),
            profile(app = app(packageName = "c"), tallies = listOf(tally("FINE_LOCATION", total = 0))),
        )
        assertEquals(mapOf("FINE_LOCATION" to 15), RiskEngine.deviceOpMedians(profiles))
    }

    @Test
    fun `report carries the window and collector list through`() {
        val report = engine.report(
            listOf(profile()),
            context(windowHours = 12),
            listOf("package-manager", "appops"),
        )
        assertEquals(NOW, report.generatedAtMs)
        assertEquals(NOW - 12 * 3_600_000L, report.windowStartMs)
        assertEquals(listOf("package-manager", "appops"), report.activeCollectors)
        assertEquals(1, report.assessments.size)
    }
}
