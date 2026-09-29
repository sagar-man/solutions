package dev.focusblocker.block

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.focusblocker.core.EventLog
import java.util.Locale

/**
 * Spike 2: the blocking screen.
 *
 * Two deliberate choices:
 *
 *  - Window type is TYPE_ACCESSIBILITY_OVERLAY, which an AccessibilityService
 *    may use *without* the SYSTEM_ALERT_WINDOW permission. One fewer scary
 *    permission in onboarding.
 *  - The view hierarchy is plain Android Views, not Compose. Hosting Compose in
 *    a WindowManager window owned by a Service means supplying your own
 *    lifecycle, saved-state and recomposer owners. Not worth it for a screen
 *    that is three text views and two buttons.
 */
class BlockOverlay(private val service: AccessibilityService) {

    private val windowManager =
        service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var root: View? = null
    private var countdown: TextView? = null
    private var shownFor: String? = null

    /** Ticks the remaining-time label once a second while the overlay is up. */
    private val tick = object : Runnable {
        override fun run() {
            countdown?.text = remainingLabel()
            handler.postDelayed(this, 1_000L)
        }
    }

    private var remainingMsProvider: () -> Long = { 0L }

    val isShowing: Boolean get() = root != null

    fun show(packageName: String, appLabel: String, remainingMs: () -> Long) {
        remainingMsProvider = remainingMs

        // Already up for this app: nothing to do. Re-adding the window on every
        // event would flicker and leak.
        if (root != null && shownFor == packageName) return
        if (root != null) hide()

        val layout = buildView(appLabel)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }

        runCatching { windowManager.addView(layout, params) }
            .onSuccess {
                root = layout
                shownFor = packageName
                layout.requestFocus()
                handler.post(tick)
                EventLog.append(service, "OVERLAY shown over $packageName")
            }
            .onFailure { error ->
                EventLog.append(service, "OVERLAY failed: ${error.javaClass.simpleName} ${error.message}")
            }
    }

    fun hide() {
        val current = root ?: return
        handler.removeCallbacks(tick)
        runCatching { windowManager.removeView(current) }
        root = null
        countdown = null
        shownFor = null
    }

    private fun buildView(appLabel: String): View {
        val container = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xF20F172A.toInt())
            setPadding(dp(32), dp(32), dp(32), dp(32))
            // Focusable so we receive — and swallow — the back key.
            isFocusableInTouchMode = true
            isClickable = true
            setOnKeyListener { _, keyCode, _ ->
                keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_APP_SWITCH
            }
        }

        container.addView(
            TextView(service).apply {
                text = "Blocked"
                setTextColor(Color.WHITE)
                textSize = 34f
                gravity = Gravity.CENTER
            },
        )
        container.addView(
            TextView(service).apply {
                text = "$appLabel is blocked during this focus session."
                setTextColor(0xFFCBD5E1.toInt())
                textSize = 17f
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(24))
            },
        )
        val countdownView = TextView(service).apply {
            text = remainingLabel()
            setTextColor(0xFF7DD3FC.toInt())
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(32))
        }
        countdown = countdownView
        container.addView(countdownView)

        container.addView(
            Button(service).apply {
                text = "Back to home screen"
                setOnClickListener {
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                    hide()
                }
            },
        )
        container.addView(
            Button(service).apply {
                // Present only while this is a spike. A shipping build replaces
                // this with the friction ladder, never a free exit.
                text = "Dismiss (spike only)"
                setOnClickListener {
                    EventLog.append(service, "OVERLAY dismissed manually")
                    hide()
                }
            },
        )
        return container
    }

    private fun remainingLabel(): String {
        val totalSeconds = remainingMsProvider() / 1_000L
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%d:%02d left", minutes, seconds)
    }

    private fun dp(value: Int): Int =
        (value * service.resources.displayMetrics.density).toInt()
}
