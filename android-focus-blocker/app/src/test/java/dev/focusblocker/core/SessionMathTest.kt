package dev.focusblocker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bypasses a focus session has to survive, expressed as tests.
 *
 * Times are in ms. "Wall" numbers are arbitrary epoch values; "elapsed" numbers
 * are uptime since boot.
 */
class SessionMathTest {

    private val wallStart = 1_700_000_000_000L
    private val elapsedStart = 600_000L // 10 minutes of uptime
    private val duration = 25 * 60_000L // 25 minutes
    private val bootCount = 3

    private val deadlines = SessionMath.deadlinesFor(
        startWallMs = wallStart,
        startElapsedMs = elapsedStart,
        durationMs = duration,
        bootCount = bootCount,
    )

    @Test
    fun `no session stored is inactive`() {
        val result = SessionMath.remaining(null, wallStart, elapsedStart, bootCount)
        assertEquals(SessionMath.Clock.NONE, result.clock)
        assertFalse(result.isActive)
        assertEquals(0L, result.ms)
    }

    @Test
    fun `mid-session on the same boot uses the monotonic clock`() {
        val tenMinutesIn = 10 * 60_000L
        val result = SessionMath.remaining(
            deadlines = deadlines,
            nowWallMs = wallStart + tenMinutesIn,
            nowElapsedMs = elapsedStart + tenMinutesIn,
            currentBootCount = bootCount,
        )
        assertEquals(SessionMath.Clock.MONOTONIC, result.clock)
        assertEquals(15 * 60_000L, result.ms)
        assertEquals(0L, result.clockSkewMs)
        assertTrue(result.isActive)
    }

    @Test
    fun `pushing the wall clock forward does not shorten the session`() {
        val tenMinutesIn = 10 * 60_000L
        val cheat = 60 * 60_000L // user sets the clock an hour ahead
        val result = SessionMath.remaining(
            deadlines = deadlines,
            nowWallMs = wallStart + tenMinutesIn + cheat,
            nowElapsedMs = elapsedStart + tenMinutesIn,
            currentBootCount = bootCount,
        )
        assertEquals(SessionMath.Clock.MONOTONIC, result.clock)
        assertEquals("session length must be unaffected", 15 * 60_000L, result.ms)
        assertEquals("tampering must be reported", -cheat, result.clockSkewMs)
    }

    @Test
    fun `winding the wall clock back does not extend the session either`() {
        val tenMinutesIn = 10 * 60_000L
        val result = SessionMath.remaining(
            deadlines = deadlines,
            nowWallMs = wallStart + tenMinutesIn - 30 * 60_000L,
            nowElapsedMs = elapsedStart + tenMinutesIn,
            currentBootCount = bootCount,
        )
        assertEquals(SessionMath.Clock.MONOTONIC, result.clock)
        assertEquals(15 * 60_000L, result.ms)
        assertEquals(30 * 60_000L, result.clockSkewMs)
    }

    @Test
    fun `small clock corrections are not reported as tampering`() {
        val result = SessionMath.remaining(
            deadlines = deadlines,
            nowWallMs = wallStart + 2_000L, // NTP nudge, monotonic unchanged
            nowElapsedMs = elapsedStart,
            currentBootCount = bootCount,
        )
        assertEquals(0L, result.clockSkewMs)
    }

    @Test
    fun `session expiring on the same boot reports zero, not a negative`() {
        val result = SessionMath.remaining(
            deadlines = deadlines,
            nowWallMs = wallStart + duration + 60_000L,
            nowElapsedMs = elapsedStart + duration + 60_000L,
            currentBootCount = bootCount,
        )
        assertEquals(0L, result.ms)
        assertFalse(result.isActive)
    }

    @Test
    fun `after a reboot the wall clock carries the session`() {
        // Five wall minutes passed across the reboot; uptime restarted.
        val result = SessionMath.remaining(
            deadlines = deadlines,
            nowWallMs = wallStart + 5 * 60_000L,
            nowElapsedMs = 30_000L,
            currentBootCount = bootCount + 1,
        )
        assertEquals(SessionMath.Clock.WALL_CLOCK_AFTER_REBOOT, result.clock)
        assertEquals(20 * 60_000L, result.ms)
        assertTrue(result.isActive)
    }

    @Test
    fun `a session that ran out during downtime is over after the reboot`() {
        val result = SessionMath.remaining(
            deadlines = deadlines,
            nowWallMs = wallStart + duration + 10 * 60_000L,
            nowElapsedMs = 30_000L,
            currentBootCount = bootCount + 1,
        )
        assertEquals(0L, result.ms)
        assertFalse(result.isActive)
    }

    @Test
    fun `a reboot whose uptime already passed the old one is still a reboot`() {
        // The boot counter is what catches this: uptime is higher than it was at
        // session start, so the backwards-clock signal alone would miss it and we
        // would over-block.
        val result = SessionMath.remaining(
            deadlines = deadlines,
            nowWallMs = wallStart + 5 * 60_000L,
            nowElapsedMs = elapsedStart + 60_000L,
            currentBootCount = bootCount + 1,
        )
        assertEquals(SessionMath.Clock.WALL_CLOCK_AFTER_REBOOT, result.clock)
        assertEquals(20 * 60_000L, result.ms)
    }

    @Test
    fun `a missed boot broadcast still falls back to the monotonic reset`() {
        // App was force-stopped, so BOOT_COMPLETED never arrived and the counter
        // is stale. Uptime running backwards has to cover for it.
        val result = SessionMath.remaining(
            deadlines = deadlines,
            nowWallMs = wallStart + 5 * 60_000L,
            nowElapsedMs = 20_000L,
            currentBootCount = bootCount,
        )
        assertEquals(SessionMath.Clock.WALL_CLOCK_AFTER_REBOOT, result.clock)
        assertEquals(20 * 60_000L, result.ms)
    }
}
