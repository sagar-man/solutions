package dev.accessaudit.app.collect

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Tier 1: per-app foreground time from `UsageStatsManager`.
 *
 * On its own this says nothing about permissions. Its value is as the denominator
 * for every other signal: "used the microphone" means something very different
 * for an app with 40 minutes of screen time than for one the user never opened.
 * [dev.accessaudit.core.risk.NeverForegroundRule] depends on it and deliberately
 * refuses to fire without it.
 *
 * The user grants this in Settings > Apps > Special app access > Usage access.
 */
public class UsageStatsCollector(private val context: Context) : AccessCollector {

    override val id: String = "usage-stats"
    override val tier: CollectorTier = CollectorTier.USAGE

    override suspend fun isAvailable(): Boolean = hasUsageAccess()

    /**
     * Checks the appop rather than the permission: `PACKAGE_USAGE_STATS` is
     * declared in the manifest but only takes effect once the user flips the
     * Settings toggle, which is recorded as `GET_USAGE_STATS`.
     */
    public fun hasUsageAccess(): Boolean = runCatching {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        // The op name is spelled out rather than using AppOpsManager.OPSTR_GET_USAGE_STATS,
        // which is only public from API 29; the string itself has been stable since API 21.
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(OP_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(OP_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    override suspend fun collect(windowStartMs: Long, nowMs: Long): CollectionResult =
        withContext(Dispatchers.IO) {
            if (!hasUsageAccess()) {
                return@withContext CollectionResult(
                    collectorId = id,
                    tier = tier,
                    available = false,
                    unavailableReason = "Usage access is off. Without it, the report cannot tell " +
                        "an app you opened from one that ran on its own.",
                )
            }
            val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val stats = runCatching {
                manager.queryAndAggregateUsageStats(windowStartMs, nowMs)
            }.getOrNull()
                ?: return@withContext CollectionResult(
                    collectorId = id,
                    tier = tier,
                    available = false,
                    unavailableReason = "The system returned no usage data for this window.",
                )

            CollectionResult(
                collectorId = id,
                tier = tier,
                available = true,
                foregroundTimeMs = stats.mapValues { (_, usage) -> usage.totalTimeInForeground },
            )
        }
}
