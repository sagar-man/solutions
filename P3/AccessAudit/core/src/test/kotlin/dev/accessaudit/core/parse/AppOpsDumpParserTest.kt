package dev.accessaudit.core.parse

import dev.accessaudit.core.model.AccessKind
import dev.accessaudit.core.model.OpMode
import dev.accessaudit.core.model.UidState
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppOpsDumpParserTest {

    private val now = 1_700_000_000_000L // fixed clock so relative ages are exact
    private val parser = AppOpsDumpParser(nowMs = now, zone = ZoneId.of("UTC"))

    /** Shaped after real `dumpsys appops` output, including the noise lines. */
    private val dump = """
        Current AppOps Service state:
          Uid mode state:
            com.example.quiet: READ_SMS=ignore

          Uid 10123:
            state=fg
            capability=NONE
            Package com.example.tracker:
              COARSE_LOCATION (allow):
                Access: [fg-tp]2023-11-14 22:10:00.000 (-2h3m)
                Access: [bg-t]2023-11-14 20:00:00.000 (-4h13m)
              RECORD_AUDIO (allow):
                Access: [bg-t]2023-11-14 19:00:00.000 (-5h13m) duration=+1m30s
                Reject: [bg-t]2023-11-14 18:00:00.000 (-6h13m)
              READ_SMS (ignore):
              MANAGE_EXTERNAL_STORAGE (allow):
                Access: [fg-s]2023-11-14 23:00:00.000 (-1h13m)

          Uid 10456:
            state=cch
            Package com.example.wallpaper:
              WRITE_WALLPAPER (allow):
                Access: [tp]2023-11-14 21:00:00.000 (-3h13m)
    """.trimIndent()

    private val parsed = parser.parse(dump)

    @Test
    fun `finds both packages and attributes them to the right uid`() {
        assertEquals(setOf("com.example.tracker", "com.example.wallpaper"), parsed.packages())
        assertTrue(parsed.entriesFor("com.example.tracker").all { it.uid == 10123 })
        assertTrue(parsed.entriesFor("com.example.wallpaper").all { it.uid == 10456 })
    }

    @Test
    fun `reads the op mode`() {
        val entries = parsed.entriesFor("com.example.tracker").associateBy { it.op }
        assertEquals(OpMode.ALLOW, entries.getValue("COARSE_LOCATION").mode)
        assertEquals(OpMode.IGNORE, entries.getValue("READ_SMS").mode)
    }

    @Test
    fun `classifies each op into an access kind`() {
        val entries = parsed.entriesFor("com.example.tracker").associateBy { it.op }
        assertEquals(AccessKind.LOCATION, entries.getValue("COARSE_LOCATION").kind)
        assertEquals(AccessKind.CAPTURE, entries.getValue("RECORD_AUDIO").kind)
        assertEquals(AccessKind.MESSAGING, entries.getValue("READ_SMS").kind)
        assertEquals(AccessKind.READ, entries.getValue("MANAGE_EXTERNAL_STORAGE").kind)
        assertEquals(
            AccessKind.WRITE,
            parsed.entriesFor("com.example.wallpaper").single().kind,
        )
    }

    @Test
    fun `separates foreground from background accesses`() {
        val location = parsed.talliesFor("com.example.tracker").single { it.op == "COARSE_LOCATION" }
        assertEquals(2, location.totalCount)
        assertEquals(1, location.foregroundCount)
        assertEquals(1, location.backgroundCount)
        assertEquals(0, location.deniedCount)
    }

    @Test
    fun `a foreground-service flag does not count as background`() {
        // `[fg-s]` is a visible foreground service, so it is not a silent access.
        val storage = parsed.talliesFor("com.example.tracker").single { it.op == "MANAGE_EXTERNAL_STORAGE" }
        assertEquals(1, storage.totalCount)
        assertEquals(0, storage.backgroundCount)
    }

    @Test
    fun `rejects are counted separately from accesses`() {
        val audio = parsed.talliesFor("com.example.tracker").single { it.op == "RECORD_AUDIO" }
        assertEquals(1, audio.totalCount, "a Reject must not inflate the access count")
        assertEquals(1, audio.deniedCount)
        assertEquals(90_000L, audio.totalDurationMs)
    }

    @Test
    fun `timestamps come from the relative age against the injected clock`() {
        val location = parsed.talliesFor("com.example.tracker").single { it.op == "COARSE_LOCATION" }
        // Newest access is -2h3m from `now`.
        assertEquals(now - (2 * 3_600_000L + 3 * 60_000L), location.lastAccessMs)
        assertEquals(now - (4 * 3_600_000L + 13 * 60_000L), location.lastBackgroundAccessMs)
    }

    @Test
    fun `an op with no access lines is recorded as granted-but-unused`() {
        val storage = parsed.entriesFor("com.example.tracker").single { it.op == "MANAGE_EXTERNAL_STORAGE" }
        assertFalse(storage.grantedButUnused)
        val sms = parsed.entriesFor("com.example.tracker").single { it.op == "READ_SMS" }
        // mode=ignore, so it is not "granted" at all
        assertFalse(sms.grantedButUnused)
        assertTrue(sms.events.isEmpty())
    }

    @Test
    fun `known noise lines are not reported as unparsed`() {
        assertEquals(emptyList(), parsed.unparsedLines, "parser should recognise standard dump scaffolding")
    }

    @Test
    fun `falls back to the absolute timestamp when no relative age is printed`() {
        val text = """
            Uid 10001:
              Package com.example.only.absolute:
                CAMERA (allow):
                  Access: [tp]2023-11-14 22:13:20.000
        """.trimIndent()
        val event = parser.parse(text).events().single()
        // 2023-11-14T22:13:20Z == 1700000000000
        assertEquals(1_700_000_000_000L, event.timestampMs)
        assertEquals(UidState.TOP, event.state)
    }

    @Test
    fun `accepts the android-prefixed op spelling`() {
        val text = """
            Uid 10002:
              Package com.example.prefixed:
                android:fine_location (allow):
                  Access: [bg-t]2023-11-14 22:00:00.000 (-13m)
        """.trimIndent()
        val entry = parser.parse(text).entries.single()
        assertEquals("FINE_LOCATION", entry.op)
        assertEquals(AccessKind.LOCATION, entry.kind)
        assertTrue(entry.events.single().isBackground)
    }

    @Test
    fun `unknown lines are surfaced instead of aborting the parse`() {
        val text = """
            Uid 10003:
              Package com.example.future:
                CAMERA (allow):
                  Access: [tp]2023-11-14 22:00:00.000 (-13m)
                  SomeBrandNewField: 42
        """.trimIndent()
        val result = parser.parse(text)
        assertEquals(1, result.events().size, "the good event must still be parsed")
        assertEquals(1, result.unparsedLines.size)
        assertTrue(result.unparsedLines.single().contains("SomeBrandNewField"))
    }

    @Test
    fun `an empty dump yields nothing rather than throwing`() {
        val result = parser.parse("")
        assertTrue(result.entries.isEmpty())
        assertTrue(result.unparsedLines.isEmpty())
    }
}
