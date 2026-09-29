package dev.focusblocker.core

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import dev.focusblocker.detect.BlockerAccessibilityService

object AccessibilityStatus {

    /**
     * Whether the user has switched our service on in Settings.
     *
     * This is separate from [BlockerAccessibilityService.isConnected]: "enabled"
     * is the user's choice, "connected" is whether the system has actually bound
     * the service. On OEM ROMs that kill background work the two disagree, and
     * that gap is the single most common cause of a blocker silently doing
     * nothing.
     */
    fun isEnabled(context: Context): Boolean {
        val expected = ComponentName(context, BlockerAccessibilityService::class.java)
        val setting = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(setting)
        for (entry in splitter) {
            val parsed = ComponentName.unflattenFromString(entry) ?: continue
            if (parsed == expected) return true
        }
        return false
    }
}
