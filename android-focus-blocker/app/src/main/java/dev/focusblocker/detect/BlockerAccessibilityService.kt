package dev.focusblocker.detect

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import dev.focusblocker.block.BlockOverlay
import dev.focusblocker.core.EventLog
import dev.focusblocker.core.ForegroundAppLog
import dev.focusblocker.core.InstalledApps
import dev.focusblocker.core.PolicyEngine
import dev.focusblocker.core.SessionStore

/**
 * Spike 1 (detection) and spike 2 (intervention).
 *
 * TYPE_WINDOW_STATE_CHANGED fires whenever a new window comes to the front, and
 * the event carries the package that owns it. That is the whole detection
 * mechanism — everything else here is about keeping the callback cheap and not
 * blocking things we must never block.
 */
class BlockerAccessibilityService : AccessibilityService() {

    private lateinit var store: SessionStore
    private lateinit var policy: PolicyEngine
    private lateinit var overlay: BlockOverlay

    private var homePackage: String? = null
    private var lastPackage: String? = null
    private var lastEventAtMs: Long = 0L

    /** Held as a field so it is not garbage collected while registered. */
    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            policy.refresh()
            if (!policy.lastRefresh.isActive) overlay.hide()
        }

    override fun onServiceConnected() {
        super.onServiceConnected()
        store = SessionStore(this)
        policy = PolicyEngine(store)
        overlay = BlockOverlay(this)
        homePackage = InstalledApps.homePackage(this)

        policy.refresh()
        store.registerListener(prefsListener)

        isConnected = true
        val remaining = policy.lastRefresh
        EventLog.append(
            this,
            "SERVICE connected — session ${remaining.ms / 1000}s left via ${remaining.clock}",
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = event.packageName?.toString() ?: return

        if (packageName == this.packageName) return
        if (packageName == homePackage) return
        if (packageName in NEVER_BLOCK) return

        // The same launch produces several window events; collapse them.
        val nowUptime = SystemClock.uptimeMillis()
        if (packageName == lastPackage && nowUptime - lastEventAtMs < DEBOUNCE_MS) return
        lastPackage = packageName
        lastEventAtMs = nowUptime

        // event.eventTime is on the uptimeMillis timebase, so this is the real
        // delay between the window changing and us hearing about it.
        val latencyMs = (nowUptime - event.eventTime).coerceAtLeast(0L)

        val nowElapsed = SystemClock.elapsedRealtime()
        val blocked = policy.isBlocked(packageName, nowElapsed)

        ForegroundAppLog.record(
            ForegroundAppLog.Detection(
                packageName = packageName,
                latencyMs = latencyMs,
                atWallMs = System.currentTimeMillis(),
                wasBlocked = blocked,
            ),
        )

        if (blocked) {
            overlay.show(
                packageName = packageName,
                appLabel = InstalledApps.labelFor(this, packageName),
                remainingMs = { policy.sessionRemainingMs(SystemClock.elapsedRealtime()) },
            )
        } else {
            overlay.hide()
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        teardown("unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown("destroyed")
        super.onDestroy()
    }

    private fun teardown(reason: String) {
        if (!isConnected) return
        isConnected = false
        runCatching { store.unregisterListener(prefsListener) }
        runCatching { overlay.hide() }
        EventLog.append(this, "SERVICE $reason")
    }

    companion object {
        /**
         * Whether the service is currently connected. Service and UI share a
         * process, so a volatile flag is enough; the UI cross-checks against
         * Settings.Secure for the "enabled but not yet running" case.
         */
        @Volatile
        var isConnected: Boolean = false
            internal set

        private const val DEBOUNCE_MS = 400L

        /**
         * Safety floor. Blocking the dialer or system UI is how a focus app
         * becomes a liability. Settings is exempt for now so the spike cannot
         * lock you out of your own device — hardening that is a later phase,
         * and deliberately not part of these spikes.
         */
        private val NEVER_BLOCK = setOf(
            "com.android.systemui",
            "com.android.settings",
            "com.android.dialer",
            "com.google.android.dialer",
            "com.android.server.telecom",
            "com.android.emergency",
            "com.android.phone",
            "android",
        )
    }
}
