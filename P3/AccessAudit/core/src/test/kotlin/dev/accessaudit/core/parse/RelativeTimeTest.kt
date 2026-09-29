package dev.accessaudit.core.parse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RelativeTimeTest {

    @Test
    fun `parses a full compound duration`() {
        // 1d2h3m4s5ms
        val expected = 86_400_000L + 2 * 3_600_000L + 3 * 60_000L + 4_000L + 5L
        assertEquals(-expected, RelativeTime.parseSignedMillis("-1d2h3m4s5ms"))
    }

    @Test
    fun `milliseconds are not mistaken for minutes`() {
        assertEquals(5L, RelativeTime.parseSignedMillis("+5ms"))
        assertEquals(5 * 60_000L, RelativeTime.parseSignedMillis("+5m"))
    }

    @Test
    fun `honours the sign`() {
        assertEquals(-9 * 3_600_000L, RelativeTime.parseSignedMillis("-9h"))
        assertEquals(90_000L, RelativeTime.parseSignedMillis("+1m30s"))
        // No sign at all reads as positive.
        assertEquals(3_600_000L, RelativeTime.parseSignedMillis("1h"))
    }

    @Test
    fun `rejects strings that are only partly a duration`() {
        // Guards against a package name or hash being read as a duration.
        assertNull(RelativeTime.parseSignedMillis("com.example.2s.app"))
        assertNull(RelativeTime.parseSignedMillis("-2hx"))
        assertNull(RelativeTime.parseSignedMillis(""))
        assertNull(RelativeTime.parseSignedMillis("abc"))
    }

    @Test
    fun `duration helper always returns a positive value`() {
        assertEquals(90_000L, RelativeTime.parseDurationMillis("+1m30s"))
        assertEquals(90_000L, RelativeTime.parseDurationMillis("-1m30s"))
        assertEquals(0L, RelativeTime.parseDurationMillis("nonsense"))
    }
}
