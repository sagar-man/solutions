package dev.focusblocker

import android.app.Application
import dev.focusblocker.core.EventLog

class FocusApp : Application() {
    override fun onCreate() {
        super.onCreate()
        EventLog.append(this, "APP process started")
    }
}
