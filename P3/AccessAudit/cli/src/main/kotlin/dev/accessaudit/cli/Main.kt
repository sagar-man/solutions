package dev.accessaudit.cli

import dev.accessaudit.core.AccessAudit
import dev.accessaudit.core.report.ReportFormatter
import dev.accessaudit.core.risk.DEFAULT_RULES
import java.io.File
import java.time.ZoneId
import kotlin.system.exitProcess

private const val USAGE = """
access-audit ${AccessAudit.VERSION} - analyse Android access data captured over adb

Usage:
  access-audit --packages <file> --appops <file> [options]
  access-audit --dir <capture-dir> [options]
  access-audit --rules

Input:
  --dir <dir>          capture directory produced by tools/adb-access-audit.sh
                       (expects packages.txt and appops.txt inside)
  --packages <file>    output of: adb shell dumpsys package packages
  --appops <file>      output of: adb shell dumpsys appops

Options:
  --format text|json|csv|matrix   output format (default: text)
  --out <file>                    write to a file instead of stdout
  --sdk <int>                     device SDK level, improves a few rules
  --window-hours <int>            observation window the capture covers (default: 24)
  --now-ms <long>                 capture time in epoch millis (default: file mtime, else now)
  --package <name>                with --format text, print only this app's access table
  --min-severity <name>           drop findings below this severity (INFO..CRITICAL)
  --suppress <pkg:RULE_ID>        ignore one rule for one package; repeatable
  --rules                         list the rule set and exit
  --help                          this text

Exit codes:
  0  no app was flagged
  1  usage or input error
  2  at least one app was flagged (useful in CI)
"""

private class Args(argv: Array<String>) {
    val flags = mutableMapOf<String, String>()
    val suppressions = mutableSetOf<String>()
    var wantsRules = false
    var wantsHelp = false

    init {
        var i = 0
        while (i < argv.size) {
            when (val arg = argv[i]) {
                "--help", "-h" -> wantsHelp = true
                "--rules" -> wantsRules = true
                "--suppress" -> suppressions += requireValue(argv, ++i, arg)
                else -> {
                    if (!arg.startsWith("--")) fail("unexpected argument: $arg")
                    flags[arg.removePrefix("--")] = requireValue(argv, ++i, arg)
                }
            }
            i++
        }
    }

    private fun requireValue(argv: Array<String>, index: Int, flag: String): String =
        argv.getOrNull(index) ?: fail("$flag needs a value")

    operator fun get(name: String): String? = flags[name]
}

private fun fail(message: String): Nothing {
    System.err.println("access-audit: $message")
    System.err.println("Run with --help for usage.")
    exitProcess(1)
}

public fun main(argv: Array<String>) {
    val args = Args(argv)
    if (args.wantsHelp || argv.isEmpty()) {
        println(USAGE.trim())
        return
    }
    if (args.wantsRules) {
        println("Rule set (${DEFAULT_RULES.size} rules):")
        DEFAULT_RULES.forEach { println("  %-28s %s".format(it.id, it.description)) }
        return
    }

    val dir = args["dir"]?.let { File(it) }
    val packagesFile = args["packages"]?.let { File(it) }
        ?: dir?.resolve("packages.txt")
        ?: fail("need --packages or --dir")
    val appOpsFile = args["appops"]?.let { File(it) }
        ?: dir?.resolve("appops.txt")
        ?: fail("need --appops or --dir")

    if (!packagesFile.isFile) fail("not a file: $packagesFile")
    if (!appOpsFile.isFile) fail("not a file: $appOpsFile")

    // appop timestamps are recorded as an age relative to capture time, so the
    // capture's mtime is a far better reference than the current wall clock.
    val nowMs = args["now-ms"]?.toLongOrNull()
        ?: appOpsFile.lastModified().takeIf { it > 0 }
        ?: System.currentTimeMillis()
    val windowHours = args["window-hours"]?.toLongOrNull() ?: 24L

    val report = AccessAudit.analyzeDumps(
        packageDump = packagesFile.readText(),
        appOpsDump = appOpsFile.readText(),
        nowMs = nowMs,
        windowStartMs = nowMs - windowHours * 3_600_000L,
        deviceSdkInt = args["sdk"]?.toIntOrNull() ?: 0,
        zone = ZoneId.systemDefault(),
        suppressions = args.suppressions,
    )

    val filtered = args["min-severity"]?.let { name ->
        val floor = runCatching { dev.accessaudit.core.model.Severity.valueOf(name.uppercase()) }
            .getOrElse { fail("unknown severity: $name") }
        report.copy(
            assessments = report.assessments.map { assessment ->
                assessment.copy(findings = assessment.findings.filter { it.severity >= floor })
            },
        )
    } ?: report

    val single = args["package"]
    val output = when (val format = args["format"] ?: "text") {
        "json" -> ReportFormatter.toJson(filtered)
        "csv" -> ReportFormatter.findingsCsv(filtered)
        "matrix" -> ReportFormatter.accessMatrixCsv(filtered)
        "text" -> if (single != null) {
            ReportFormatter.accessTableText(filtered, single)
        } else {
            ReportFormatter.toText(filtered)
        }
        else -> fail("unknown format: $format")
    }

    args["out"]?.let { path ->
        File(path).writeText(output)
        System.err.println("access-audit: wrote $path")
    } ?: print(output)

    exitProcess(if (filtered.flagged.isNotEmpty()) 2 else 0)
}
