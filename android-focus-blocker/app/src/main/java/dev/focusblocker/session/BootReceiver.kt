package dev.focusblocker.session

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.focusblocker.core.EventLog
import dev.focusblocker.core.SessionStore

/**
 * Spike 4's witness, and the reboot signal [dev.focusblocker.core.SessionMath]
 * depends on.
 *
 * Nothing here restarts the blocking engine — the system re-binds an enabled
 * AccessibilityService by itself after boot, which is exactly the property the
 * spike is testing. This receiver bumps the boot counter and writes down what
 * the session looked like on the far side of the reboot, so there is evidence
 * rather than a vibe.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val store = SessionStore(context)
        val bootCount = store.recordBoot()
        val remaining = store.remaining()
        EventLog.append(
            context,
            "BOOT #$bootCount completed — session ${remaining.ms / 1000}s left " +
                "via ${remaining.clock}",
        )
    }
}
