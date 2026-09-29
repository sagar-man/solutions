package dev.focusblocker.core

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock

/**
 * Persistence for the session. All the clock reasoning lives in [SessionMath] so
 * it can be tested without a device.
 */
class SessionStore(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var blockedPackages: Set<String>
        get() = prefs.getStringSet(KEY_PACKAGES, emptySet()).orEmpty()
        set(value) = prefs.edit().putStringSet(KEY_PACKAGES, value).apply()

    /**
     * How many times the device has booted since install, as far as we have
     * observed. Survives [clearSession] on purpose — it is a property of the
     * device, not of a session.
     */
    val bootCount: Int get() = prefs.getInt(KEY_BOOT_COUNT, 0)

    fun recordBoot(): Int {
        val next = bootCount + 1
        // commit(), not apply(): at boot the process may be torn down again
        // immediately, and losing this write would make the next reboot
        // invisible to SessionMath.
        prefs.edit().putInt(KEY_BOOT_COUNT, next).commit()
        return next
    }

    fun startSession(durationMs: Long) {
        val deadlines = SessionMath.deadlinesFor(
            startWallMs = System.currentTimeMillis(),
            startElapsedMs = SystemClock.elapsedRealtime(),
            durationMs = durationMs,
            bootCount = bootCount,
        )
        prefs.edit()
            .putLong(KEY_START_ELAPSED, deadlines.startElapsedMs)
            .putLong(KEY_END_ELAPSED, deadlines.endElapsedMs)
            .putLong(KEY_END_WALL, deadlines.endWallMs)
            .putInt(KEY_START_BOOT_COUNT, deadlines.startBootCount)
            .apply()
    }

    fun clearSession() {
        prefs.edit()
            .remove(KEY_START_ELAPSED)
            .remove(KEY_END_ELAPSED)
            .remove(KEY_END_WALL)
            .remove(KEY_START_BOOT_COUNT)
            .apply()
    }

    fun remaining(): SessionMath.Remaining = SessionMath.remaining(
        deadlines = storedDeadlines(),
        nowWallMs = System.currentTimeMillis(),
        nowElapsedMs = SystemClock.elapsedRealtime(),
        currentBootCount = bootCount,
    )

    private fun storedDeadlines(): SessionMath.Deadlines? {
        val endWall = prefs.getLong(KEY_END_WALL, 0L)
        if (endWall == 0L) return null
        return SessionMath.Deadlines(
            startElapsedMs = prefs.getLong(KEY_START_ELAPSED, 0L),
            endElapsedMs = prefs.getLong(KEY_END_ELAPSED, 0L),
            endWallMs = endWall,
            startBootCount = prefs.getInt(KEY_START_BOOT_COUNT, 0),
        )
    }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    private companion object {
        const val PREFS = "focus_session"
        const val KEY_PACKAGES = "blocked_packages"
        const val KEY_START_ELAPSED = "start_elapsed"
        const val KEY_END_ELAPSED = "end_elapsed"
        const val KEY_END_WALL = "end_wall"
        const val KEY_START_BOOT_COUNT = "start_boot_count"
        const val KEY_BOOT_COUNT = "boot_count"
    }
}
