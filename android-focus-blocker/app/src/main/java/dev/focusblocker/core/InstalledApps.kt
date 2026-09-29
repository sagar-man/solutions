package dev.focusblocker.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

data class LaunchableApp(val packageName: String, val label: String)

object InstalledApps {

    /**
     * Apps with a launcher icon, which is all a blocker needs. Resolved through
     * the <queries> manifest element rather than QUERY_ALL_PACKAGES.
     */
    fun launchable(context: Context): List<LaunchableApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION") // the flags overload is API 33+; this works everywhere
        val resolved = pm.queryIntentActivities(intent, 0)
        return resolved
            .asSequence()
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == context.packageName) return@mapNotNull null
                LaunchableApp(pkg, info.loadLabel(pm).toString())
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    /** The current launcher, which must never be blocked. */
    fun homePackage(context: Context): String? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        @Suppress("DEPRECATION")
        val info = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return info?.activityInfo?.packageName
    }

    fun labelFor(context: Context, packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)
}
