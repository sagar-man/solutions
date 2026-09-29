package dev.accessaudit.core.report

import dev.accessaudit.core.AccessAudit
import dev.accessaudit.core.model.AppAccessProfile
import dev.accessaudit.core.model.AppIdentity
import dev.accessaudit.core.model.OpMode
import dev.accessaudit.core.model.OpTally
import dev.accessaudit.core.ops.OpCatalog
import dev.accessaudit.core.risk.AuditContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonWriterTest {

    @Test
    fun `escapes the characters that would break a parser`() {
        assertEquals(""""a\"b"""", JsonWriter.quote("""a"b"""))
        assertEquals(""""a\\b"""", JsonWriter.quote("""a\b"""))
        assertEquals(""""line1\nline2"""", JsonWriter.quote("line1\nline2"))
        assertEquals(""""tab\there"""", JsonWriter.quote("tab\there"))
    }

    @Test
    fun `escapes control characters as unicode`() {
        // The expected values are built with escaped backslashes rather than a
        // raw string: Kotlin resolves a \uXXXX sequence even inside a raw string,
        // which would make the expectation the character itself instead of the
        // escape sequence we want the writer to emit.
        assertEquals("\"\\u0001\"", JsonWriter.quote("\u0001"))
        // U+2028 is legal JSON but breaks JS parsers, so we escape it too.
        assertEquals("\"\\u2028\"", JsonWriter.quote("\u2028"))
    }

    @Test
    fun `writes a nested structure with correct comma placement`() {
        val json = JsonWriter(indentWidth = 0).obj {
            field("a", 1)
            field("b", "two")
            stringArrayField("c", listOf("x", "y"))
            objectField("d") { field("e", true) }
        }.toString()
        assertEquals("""{"a":1,"b":"two","c":["x","y"],"d":{"e":true}}""", json)
    }

    @Test
    fun `an empty array and object are still valid`() {
        val json = JsonWriter(indentWidth = 0).obj {
            stringArrayField("empty", emptyList())
            objectField("obj") {}
        }.toString()
        assertEquals("""{"empty":[],"obj":{}}""", json)
    }
}

class ReportFormatterTest {

    private val now = 1_700_000_000_000L

    private fun report() = AccessAudit.analyze(
        profiles = listOf(
            AppAccessProfile(
                app = AppIdentity(
                    packageName = "com.bad.actor",
                    label = "Flashlight, Free",
                    installerPackage = null,
                    grantedPermissions = setOf(
                        "android.permission.INTERNET",
                        "android.permission.READ_SMS",
                        "android.permission.MANAGE_EXTERNAL_STORAGE",
                    ),
                    targetSdk = 34,
                ),
                tallies = listOf(
                    OpTally(
                        op = "READ_SMS",
                        kind = OpCatalog.kindOf("READ_SMS"),
                        mode = OpMode.ALLOW,
                        totalCount = 12,
                        foregroundCount = 0,
                        backgroundCount = 12,
                        deniedCount = 1,
                        lastAccessMs = now - 600_000,
                        lastBackgroundAccessMs = now - 600_000,
                        totalDurationMs = 0,
                    ),
                ),
                sources = setOf("appops"),
            ),
            AppAccessProfile(
                app = AppIdentity(packageName = "com.example.quiet", installerPackage = "com.android.vending"),
                sources = setOf("appops"),
            ),
        ),
        context = AuditContext(nowMs = now, windowStartMs = now - 86_400_000L, deviceSdkInt = 34),
        activeCollectors = listOf("package-manager", "appops"),
    )

    @Test
    fun `json output is parseable and carries the findings`() {
        val json = ReportFormatter.toJson(report())
        assertBalanced(json)
        assertTrue(json.contains(""""package": "com.bad.actor""""))
        assertTrue(json.contains(""""riskLevel""""))
        assertTrue(json.contains("SMS_INTERCEPTION"))
        // The label contains a comma; it must be quoted, not mangled.
        assertTrue(json.contains(""""label": "Flashlight, Free""""))
    }

    @Test
    fun `the access matrix has one row per observed operation`() {
        val csv = ReportFormatter.accessMatrixCsv(report()).trim().lines()
        assertTrue(csv.first().startsWith("package,label,install_source"))
        val actorRow = csv.single { it.startsWith("com.bad.actor") }
        val cells = parseCsvLine(actorRow)
        assertEquals("READ_SMS", cells[5])
        assertEquals("messaging", cells[7])
        assertEquals("12", cells[9], "total")
        assertEquals("0", cells[10], "foreground")
        assertEquals("12", cells[11], "background")
        assertEquals("1", cells[12], "denied")
    }

    @Test
    fun `an app with no observed access still gets a row`() {
        val csv = ReportFormatter.accessMatrixCsv(report())
        assertTrue(
            csv.lines().any { it.startsWith("com.example.quiet") },
            "a quiet app must be distinguishable from an unscanned one",
        )
    }

    @Test
    fun `csv quotes a field containing a comma`() {
        val csv = ReportFormatter.findingsCsv(report())
        assertTrue(csv.contains("\"Flashlight, Free\""))
    }

    @Test
    fun `text output leads with severity and includes the evidence`() {
        val text = ReportFormatter.toText(report())
        assertTrue(text.contains("Access Audit report"))
        assertTrue(text.contains("com.bad.actor"))
        assertTrue(text.contains("CRITICAL") || text.contains("HIGH"))
        assertTrue(text.contains("why:"), "each finding must explain itself")
        assertTrue(text.contains("Collectors: package-manager, appops"))
    }

    @Test
    fun `the per-app table lists operations with a foreground-background split`() {
        val table = ReportFormatter.accessTableText(report(), "com.bad.actor")
        assertTrue(table.contains("OPERATION"))
        assertTrue(table.contains("Read SMS"), table)
        assertTrue(table.contains("messaging"))
    }

    @Test
    fun `asking for an unknown package says so instead of throwing`() {
        assertTrue(ReportFormatter.accessTableText(report(), "com.nope").contains("No such package"))
    }

    /**
     * Splits one CSV record, honouring quoted fields. The report deliberately
     * quotes values containing commas, so a naive split would shift every
     * column after an app label like "Flashlight, Free".
     */
    private fun parseCsvLine(line: String): List<String> {
        val cells = mutableListOf<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                inQuotes && ch == '"' && line.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> { cells += cell.toString(); cell.clear() }
                else -> cell.append(ch)
            }
            i++
        }
        cells += cell.toString()
        return cells
    }

    /** Cheap structural check: quotes closed, braces and brackets balanced. */
    private fun assertBalanced(json: String) {
        var braces = 0
        var brackets = 0
        var inString = false
        var escaped = false
        for (ch in json) {
            when {
                escaped -> escaped = false
                ch == '\\' && inString -> escaped = true
                ch == '"' -> inString = !inString
                inString -> Unit
                ch == '{' -> braces++
                ch == '}' -> braces--
                ch == '[' -> brackets++
                ch == ']' -> brackets--
            }
            assertTrue(braces >= 0 && brackets >= 0, "unbalanced JSON")
        }
        assertEquals(0, braces, "unbalanced braces")
        assertEquals(0, brackets, "unbalanced brackets")
        assertTrue(!inString, "unterminated string")
    }
}
