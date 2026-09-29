package dev.accessaudit.app.collect

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dev.accessaudit.core.model.AccessEvent
import dev.accessaudit.core.model.OpMode
import dev.accessaudit.core.model.UidState
import dev.accessaudit.core.ops.OpCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Tier 2, in-process route: `AppOpsManager.getPackagesForOps`, reachable once
 * `GET_APP_OPS_STATS` has been granted from a shell:
 *
 * ```
 * adb shell pm grant dev.accessaudit.app android.permission.GET_APP_OPS_STATS
 * ```
 *
 * The permission is `signature|privileged`, so a normal install can never hold
 * it; declaring it in the manifest only makes the grant possible.
 *
 * `getPackagesForOps` and the `OpEntry` accessors are `@SystemApi`, i.e. present
 * on the device but hidden from the SDK, so they are called reflectively and every
 * step is failure-tolerant. Prefer [ShellAppOpsCollector] where a shell is
 * available; this collector exists for the adb-grant setup, where no shell stays
 * running after the grant.
 *
 * What this route gives us that the dump does not: it is fast and needs no
 * subprocess. What it loses: only the *last* access time per state, not a full
 * history, so counts are reported as 1 rather than a true total.
 */
public class AppOpsReflectionCollector(private val context: Context) : AccessCollector {

    override val id: String = "appops-api"
    override val tier: CollectorTier = CollectorTier.APP_OPS

    override suspend fun isAvailable(): Boolean = hasPermission()

    public fun hasPermission(): Boolean =
        context.checkSelfPermission("android.permission.GET_APP_OPS_STATS") ==
            PackageManager.PERMISSION_GRANTED

    override suspend fun collect(windowStartMs: Long, nowMs: Long): CollectionResult =
        withContext(Dispatchers.IO) {
            if (!hasPermission()) {
                return@withContext CollectionResult(
                    collectorId = id,
                    tier = tier,
                    available = false,
                    unavailableReason = "GET_APP_OPS_STATS has not been granted. Run:\n" +
                        ShellGateway.ADB_GRANT_COMMAND,
                )
            }

            val manager = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val events = runCatching { readAllOps(manager, windowStartMs) }
                .getOrElse { error ->
                    return@withContext CollectionResult(
                        collectorId = id,
                        tier = tier,
                        available = false,
                        unavailableReason = "The hidden appops API is not callable on this build " +
                            "(${error::class.simpleName}). Use Shizuku instead.",
                    )
                }

            CollectionResult(collectorId = id, tier = tier, available = true, events = events)
        }

    private fun readAllOps(manager: AppOpsManager, windowStartMs: Long): List<AccessEvent> {
        // getPackagesForOps(null) means "every op", but some builds reject null,
        // so we pass the catalogue explicitly and fall back to null.
        val opNames = OpCatalog.allSpecs().map { "android:${it.op.lowercase()}" }.toTypedArray()
        val method = AppOpsManager::class.java.getMethod("getPackagesForOps", Array<String>::class.java)

        @Suppress("UNCHECKED_CAST")
        val packageOps = (
            runCatching { method.invoke(manager, opNames) }.getOrNull()
                ?: runCatching { method.invoke(manager, null) }.getOrNull()
            ) as? List<Any> ?: return emptyList()

        return packageOps.flatMap { entry -> readPackage(entry, windowStartMs) }
    }

    private fun readPackage(packageOps: Any, windowStartMs: Long): List<AccessEvent> = runCatching {
        val clazz = packageOps.javaClass
        val packageName = clazz.getMethod("getPackageName").invoke(packageOps) as String
        val uid = clazz.getMethod("getUid").invoke(packageOps) as Int

        @Suppress("UNCHECKED_CAST")
        val ops = clazz.getMethod("getOps").invoke(packageOps) as? List<Any> ?: return emptyList()

        ops.flatMap { op -> readOp(op, packageName, uid, windowStartMs) }
    }.getOrDefault(emptyList())

    private fun readOp(
        opEntry: Any,
        packageName: String,
        uid: Int,
        windowStartMs: Long,
    ): List<AccessEvent> = runCatching {
        val clazz = opEntry.javaClass
        val rawOp = clazz.getMethod("getOpStr").invoke(opEntry) as String
        val op = OpCatalog.normalizeOp(rawOp)
        val mode = OpMode.parse(modeName(clazz.getMethod("getMode").invoke(opEntry) as Int))

        // Foreground and background last-access times are separate accessors on
        // API 30+; older builds only expose a single timestamp.
        val events = mutableListOf<AccessEvent>()
        fun add(timestamp: Long, state: UidState, duration: Long) {
            if (timestamp > 0 && timestamp >= windowStartMs) {
                events += AccessEvent(
                    packageName = packageName,
                    uid = uid,
                    op = op,
                    kind = OpCatalog.kindOf(op),
                    mode = mode,
                    timestampMs = timestamp,
                    state = state,
                    durationMs = duration,
                    // The API reports only the most recent access per state, so
                    // a count of 1 is the honest value here. The shell collector
                    // is the one that can produce real totals.
                    count = 1,
                    source = id,
                )
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val foreground = invokeLong(clazz, opEntry, "getLastAccessForegroundTime")
            val background = invokeLong(clazz, opEntry, "getLastAccessBackgroundTime")
            val duration = invokeLong(clazz, opEntry, "getLastDuration")
            add(foreground, UidState.FOREGROUND, duration)
            add(background, UidState.BACKGROUND, duration)
            val rejectBackground = invokeLong(clazz, opEntry, "getLastRejectBackgroundTime")
            if (rejectBackground > 0 && rejectBackground >= windowStartMs) {
                events += AccessEvent(
                    packageName = packageName,
                    uid = uid,
                    op = op,
                    kind = OpCatalog.kindOf(op),
                    mode = mode,
                    timestampMs = rejectBackground,
                    state = UidState.BACKGROUND,
                    denied = true,
                    source = id,
                )
            }
        } else {
            @Suppress("DEPRECATION")
            add(invokeLong(clazz, opEntry, "getTime"), UidState.UNKNOWN, 0)
        }
        events
    }.getOrDefault(emptyList())

    private fun invokeLong(clazz: Class<*>, target: Any, name: String): Long = runCatching {
        clazz.getMethod(name).invoke(target) as? Long ?: 0L
    }.getOrDefault(0L)

    private fun modeName(mode: Int): String = when (mode) {
        AppOpsManager.MODE_ALLOWED -> "allow"
        AppOpsManager.MODE_IGNORED -> "ignore"
        AppOpsManager.MODE_ERRORED -> "deny"
        AppOpsManager.MODE_DEFAULT -> "default"
        AppOpsManager.MODE_FOREGROUND -> "foreground"
        else -> "unknown"
    }
}
