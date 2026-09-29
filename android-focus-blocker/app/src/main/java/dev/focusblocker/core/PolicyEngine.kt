package dev.focusblocker.core

import android.os.SystemClock

/**
 * The decision made on every window change on the device.
 *
 * Everything expensive happens in [refresh], off the hot path. [isBlocked] is a
 * volatile read plus a hash lookup — no disk, no allocation, no parsing. This
 * is the difference between a blocker that feels invisible and one that makes
 * the whole phone feel laggy.
 */
class PolicyEngine(private val store: SessionStore) {

    @Volatile
    private var blockedPackages: Set<String> = emptySet()

    @Volatile
    private var deadlineElapsedMs: Long = 0L

    @Volatile
    var lastRefresh: SessionMath.Remaining = SessionMath.NONE
        private set

    fun refresh() {
        blockedPackages = store.blockedPackages
        val remaining = store.remaining()
        lastRefresh = remaining
        // Translate whichever clock we trusted into a single monotonic deadline,
        // so the hot path only ever compares elapsedRealtime.
        deadlineElapsedMs =
            if (remaining.isActive) SystemClock.elapsedRealtime() + remaining.ms else 0L
    }

    fun isBlocked(packageName: String, nowElapsedMs: Long): Boolean =
        nowElapsedMs < deadlineElapsedMs && packageName in blockedPackages

    fun sessionRemainingMs(nowElapsedMs: Long): Long =
        (deadlineElapsedMs - nowElapsedMs).coerceAtLeast(0L)
}
