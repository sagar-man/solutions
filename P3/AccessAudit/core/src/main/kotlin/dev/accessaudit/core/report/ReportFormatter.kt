package dev.accessaudit.core.report

import dev.accessaudit.core.model.AuditReport
import dev.accessaudit.core.model.RiskLevel
import dev.accessaudit.core.model.Severity
import dev.accessaudit.core.ops.OpCatalog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Renders an [AuditReport] for the three consumers that matter: a machine
 * (JSON), a spreadsheet (CSV) and a developer reading a terminal (text).
 */
public object ReportFormatter {

    private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

    private fun iso(epochMs: Long): String? =
        if (epochMs <= 0) null else ISO.format(Instant.ofEpochMilli(epochMs))

    /** Full machine-readable report, including every finding's evidence. */
    public fun toJson(report: AuditReport, indent: Int = 2): String = JsonWriter(indent).obj {
        field("schemaVersion", 1)
        field("generatedAt", iso(report.generatedAtMs))
        field("generatedAtMs", report.generatedAtMs)
        objectField("window") {
            field("startMs", report.windowStartMs)
            field("endMs", report.windowEndMs)
            field("start", iso(report.windowStartMs))
            field("end", iso(report.windowEndMs))
            field("durationHours", ((report.windowEndMs - report.windowStartMs) / 3_600_000L))
        }
        field("deviceSdkInt", report.deviceSdkInt)
        stringArrayField("activeCollectors", report.activeCollectors)
        objectField("summary") {
            field("appsScanned", report.assessments.size)
            field("appsFlagged", report.flagged.size)
            RiskLevel.entries.forEach { level ->
                field(level.name.lowercase(), report.byLevel(level).size)
            }
        }
        arrayField("apps", report.assessments) { assessment ->
            element {
                field("package", assessment.app.packageName)
                field("label", assessment.app.label)
                field("uid", assessment.app.uid)
                field("versionName", assessment.app.versionName)
                field("versionCode", assessment.app.versionCode)
                field("targetSdk", assessment.app.targetSdk)
                field("installSource", assessment.app.installSource.label)
                field("installer", assessment.app.installerPackage)
                field("isSystem", assessment.app.isSystem)
                field("firstInstall", iso(assessment.app.firstInstallMs))
                field("lastUpdate", iso(assessment.app.lastUpdateMs))
                field("riskScore", assessment.score)
                field("riskLevel", assessment.level.name)
                stringArrayField("observedAccessKinds", assessment.observedKinds.map { it.label }.sorted())
                arrayField("findings", assessment.findingsBySeverity()) { finding ->
                    element {
                        field("ruleId", finding.ruleId)
                        field("title", finding.title)
                        field("severity", finding.severity.name)
                        field("rationale", finding.rationale)
                        stringArrayField("evidence", finding.evidence)
                        field("recommendation", finding.recommendation)
                        stringArrayField("accessKinds", finding.kinds.map { it.label }.sorted())
                        field("lastSeen", iso(finding.lastSeenMs))
                    }
                }
            }
        }
    }.toString()

    /**
     * One row per app per observed operation: the "which app used which access"
     * table, flat enough to sort and pivot in a spreadsheet.
     *
     * Apps with no observed operations still get a single row with an empty
     * operation, so that "scanned and quiet" stays distinguishable from
     * "never scanned".
     */
    public fun accessMatrixCsv(report: AuditReport): String {
        val sb = StringBuilder()
        sb.appendLine(
            "package,label,install_source,risk_score,risk_level,operation,access_label,access_kind," +
                "mode,total,foreground,background,denied,last_access,last_background_access,duration_ms",
        )
        for (assessment in report.assessments) {
            val rows = assessment.talliesByRisk()
            if (rows.isEmpty()) {
                sb.appendLine(
                    (appColumns(assessment) + listOf("", "", "", "", "0", "0", "0", "0", "", "", "0"))
                        .joinToString(","),
                )
                continue
            }
            for (tally in rows) {
                sb.appendLine(
                    (
                        appColumns(assessment) + listOf(
                            csv(tally.op),
                            csv(OpCatalog.labelOf(tally.op)),
                            tally.kind.label,
                            tally.mode.name,
                            tally.totalCount.toString(),
                            tally.foregroundCount.toString(),
                            tally.backgroundCount.toString(),
                            tally.deniedCount.toString(),
                            iso(tally.lastAccessMs) ?: "",
                            iso(tally.lastBackgroundAccessMs) ?: "",
                            tally.totalDurationMs.toString(),
                        )
                        ).joinToString(","),
                )
            }
        }
        return sb.toString()
    }

    private fun appColumns(assessment: dev.accessaudit.core.model.AppAssessment): List<String> = listOf(
        csv(assessment.app.packageName),
        csv(assessment.app.label),
        csv(assessment.app.installSource.label),
        assessment.score.toString(),
        assessment.level.name,
    )

    /** One row per finding, for triage in a spreadsheet or a diff between scans. */
    public fun findingsCsv(report: AuditReport): String {
        val sb = StringBuilder()
        sb.appendLine("package,label,install_source,risk_score,risk_level,rule_id,severity,title,access_kinds,evidence")
        for (assessment in report.assessments) {
            for (finding in assessment.findingsBySeverity()) {
                sb.appendLine(
                    listOf(
                        csv(assessment.app.packageName),
                        csv(assessment.app.label),
                        csv(assessment.app.installSource.label),
                        assessment.score.toString(),
                        assessment.level.name,
                        csv(finding.ruleId),
                        finding.severity.name,
                        csv(finding.title),
                        csv(finding.kinds.map { it.label }.sorted().joinToString(" ")),
                        csv(finding.evidence.joinToString(" | ")),
                    ).joinToString(","),
                )
            }
        }
        return sb.toString()
    }

    /**
     * Human-readable report for `:cli` output.
     *
     * Ordered worst-first and it leads with the evidence, because a reviewer
     * needs to be able to disagree with a rule without re-running the scan.
     */
    public fun toText(report: AuditReport, zone: ZoneId = ZoneId.systemDefault(), maxApps: Int = 0): String {
        val sb = StringBuilder()
        val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(zone)
            .format(Instant.ofEpochMilli(report.generatedAtMs))
        val hours = (report.windowEndMs - report.windowStartMs) / 3_600_000L

        sb.appendLine("Access Audit report - $stamp")
        sb.appendLine("Observation window: ${hours}h    Device SDK: ${report.deviceSdkInt}")
        sb.appendLine("Collectors: ${report.activeCollectors.joinToString().ifEmpty { "none" }}")
        sb.appendLine(
            "Apps scanned: ${report.assessments.size}    flagged: ${report.flagged.size}    " +
                RiskLevel.entries.reversed().joinToString("  ") { "${it.name}=${report.byLevel(it).size}" },
        )
        sb.appendLine("=".repeat(78))

        val shown = report.assessments
            .filter { it.findings.isNotEmpty() }
            .let { if (maxApps > 0) it.take(maxApps) else it }

        if (shown.isEmpty()) {
            sb.appendLine("No findings. Note what this does and does not cover - see the Readme.")
            return sb.toString()
        }

        for (assessment in shown) {
            sb.appendLine()
            sb.appendLine(
                "[${assessment.level.name}] ${assessment.score.toString().padStart(3)}  " +
                    "${assessment.app.label} (${assessment.app.packageName})",
            )
            sb.appendLine(
                "      source=${assessment.app.installSource.label}  " +
                    "targetSdk=${assessment.app.targetSdk}  " +
                    "access=${assessment.observedKinds.map { it.label }.sorted().joinToString("/").ifEmpty { "none observed" }}",
            )
            for (finding in assessment.findingsBySeverity()) {
                if (finding.severity == Severity.INFO) continue
                sb.appendLine("  - ${finding.severity.name.padEnd(8)} ${finding.title}  [${finding.ruleId}]")
                sb.appendLine("      why: ${wrap(finding.rationale, 68, "           ")}")
                finding.evidence.forEach { sb.appendLine("      * $it") }
                if (finding.recommendation.isNotEmpty()) {
                    sb.appendLine("      fix: ${wrap(finding.recommendation, 68, "           ")}")
                }
            }
        }
        return sb.toString()
    }

    /** Compact per-app access breakdown, the terminal form of the app's detail screen. */
    public fun accessTableText(
        report: AuditReport,
        packageName: String,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val assessment = report.assessments.firstOrNull { it.app.packageName == packageName }
            ?: return "No such package in this report: $packageName"
        val clock = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(zone)
        val sb = StringBuilder()
        sb.appendLine(
            "${assessment.app.label} ($packageName)  " +
                "risk=${assessment.score} ${assessment.level.name}  " +
                "source=${assessment.app.installSource.label}",
        )
        val rows = assessment.talliesByRisk()
        if (rows.isEmpty()) {
            sb.appendLine("  No operations observed in this window.")
            return sb.toString()
        }
        sb.appendLine(
            "  %-30s %-14s %6s %6s %6s %6s  %s".format(
                "OPERATION", "KIND", "TOTAL", "FG", "BG", "DENY", "LAST",
            ),
        )
        for (tally in rows) {
            val last = if (tally.lastAccessMs > 0) {
                clock.format(Instant.ofEpochMilli(tally.lastAccessMs))
            } else {
                "-"
            }
            sb.appendLine(
                "  %-30s %-14s %6d %6d %6d %6d  %s".format(
                    OpCatalog.labelOf(tally.op).take(30),
                    tally.kind.label,
                    tally.totalCount,
                    tally.foregroundCount,
                    tally.backgroundCount,
                    tally.deniedCount,
                    last,
                ),
            )
        }
        return sb.toString()
    }

    private fun csv(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    private fun wrap(text: String, width: Int, continuation: String): String {
        if (text.length <= width) return text
        val out = StringBuilder()
        var lineLength = 0
        for (word in text.split(' ')) {
            if (lineLength + word.length + 1 > width) {
                out.append('\n').append(continuation)
                lineLength = continuation.length
            } else if (lineLength > 0) {
                out.append(' ')
                lineLength++
            }
            out.append(word)
            lineLength += word.length
        }
        return out.toString()
    }
}
