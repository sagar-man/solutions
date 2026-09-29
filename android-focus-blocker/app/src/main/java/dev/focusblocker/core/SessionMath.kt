package dev.focusblocker.core

import kotlin.math.abs

/**
 * The clock arithmetic behind a focus session, as a pure function.
 *
 * A session needs two clocks and neither is sufficient alone:
 *
 *  - The monotonic clock (elapsedRealtime) cannot be moved by the user, so it is
 *    the honest one — but it resets to zero on reboot.
 *  - The wall clock survives reboot — but the user can move it, which is the
 *    oldest bypass there is.
 *
 * The trap is deciding *which* situation we are in. The obvious test — "did
 * startWall - startElapsed change?" — is wrong, because moving the wall clock
 * shifts that estimate exactly as a reboot does, so a clock change would be
 * misread as a reboot and end the session early. Instead we count boots (the
 * boot receiver increments a counter) and treat a wall-clock shift purely as
 * tamper evidence.
 *
 * Deliberately free of Android types so it can be unit-tested on the JVM — this
 * is the logic spike 4 exercises, and "seemed fine on my phone" is not a test.
 */
object SessionMath {

    enum class Clock { NONE, MONOTONIC, WALL_CLOCK_AFTER_REBOOT }

    data class Remaining(
        val ms: Long,
        val clock: Clock,
        /**
         * How far the device clock moved while this session was running, within
         * one boot. Negative means it was pushed forward (the bypass attempt);
         * zero means untouched. Never acted on — only reported.
         */
        val clockSkewMs: Long,
    ) {
        val isActive: Boolean get() = ms > 0L
    }

    /** What we persisted when the session started. */
    data class Deadlines(
        val startElapsedMs: Long,
        val endElapsedMs: Long,
        val endWallMs: Long,
        val startBootCount: Int,
    )

    /** Below this, a wall-clock shift is just NTP jitter rather than tampering. */
    const val SKEW_NOISE_MS = 5_000L

    val NONE = Remaining(0L, Clock.NONE, 0L)

    fun deadlinesFor(
        startWallMs: Long,
        startElapsedMs: Long,
        durationMs: Long,
        bootCount: Int,
    ) = Deadlines(
        startElapsedMs = startElapsedMs,
        endElapsedMs = startElapsedMs + durationMs,
        endWallMs = startWallMs + durationMs,
        startBootCount = bootCount,
    )

    fun remaining(
        deadlines: Deadlines?,
        nowWallMs: Long,
        nowElapsedMs: Long,
        currentBootCount: Int,
    ): Remaining {
        if (deadlines == null) return NONE

        // Two reboot signals, both clock-independent:
        //  - the boot counter moved (exact, unless BOOT_COMPLETED was never
        //    delivered because the app was in the stopped state), and
        //  - the monotonic clock ran backwards (conclusive whenever it fires).
        val bootCounterMoved = currentBootCount != deadlines.startBootCount
        val monotonicWentBackwards = nowElapsedMs < deadlines.startElapsedMs

        return if (bootCounterMoved || monotonicWentBackwards) {
            // Post-reboot the monotonic reference is gone, so the wall clock is
            // all we have. That is a real hole — a reboot plus a clock change
            // ends a session early. Closing it needs DevicePolicyManager, which
            // is a later phase, not a spike.
            Remaining(
                ms = (deadlines.endWallMs - nowWallMs).coerceAtLeast(0L),
                clock = Clock.WALL_CLOCK_AFTER_REBOOT,
                clockSkewMs = 0L,
            )
        } else {
            // Same boot: trust the monotonic deadline, whatever the wall clock
            // now says. The disagreement between the two is the tamper report.
            val skew = (deadlines.endWallMs - nowWallMs) - (deadlines.endElapsedMs - nowElapsedMs)
            Remaining(
                ms = (deadlines.endElapsedMs - nowElapsedMs).coerceAtLeast(0L),
                clock = Clock.MONOTONIC,
                clockSkewMs = if (abs(skew) < SKEW_NOISE_MS) 0L else skew,
            )
        }
    }
}
