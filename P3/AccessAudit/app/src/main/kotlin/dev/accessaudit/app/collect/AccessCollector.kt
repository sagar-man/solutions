package dev.accessaudit.app.collect

import dev.accessaudit.core.model.AccessEvent
import dev.accessaudit.core.model.AppIdentity

/**
 * How much privilege a collector needs. This is the app's central honesty
 * mechanism: the UI shows which tiers are live, so a user is never led to
 * believe they are seeing a complete access log when they are not.
 */
public enum class CollectorTier(public val label: String, public val requirement: String) {
    /** No permission at all. Static inventory of what each app *may* do. */
    STATIC("Static inventory", "no permission required"),

    /** User-granted special access. Tells foreground use from background use. */
    USAGE("Usage history", "Usage access, granted in Settings"),

    /**
     * The real per-app access log. Needs `GET_APP_OPS_STATS`, which is
     * signature|privileged, so it comes from adb or Shizuku.
     */
    APP_OPS("Access log", "adb or Shizuku"),

    /** Kernel/SELinux level file access. Root only. */
    KERNEL("File-level audit", "root"),
}

/** What one collector managed to produce. */
public data class CollectionResult(
    val collectorId: String,
    val tier: CollectorTier,
    val available: Boolean,
    val identities: List<AppIdentity> = emptyList(),
    val events: List<AccessEvent> = emptyList(),
    /** Per-package foreground milliseconds, when this collector measures it. */
    val foregroundTimeMs: Map<String, Long> = emptyMap(),
    /**
     * Why the collector produced nothing, phrased for a user rather than a log.
     * Shown verbatim on the setup screen.
     */
    val unavailableReason: String? = null,
)

/**
 * A source of access data.
 *
 * Collectors are independent and failure-tolerant: a scan runs every one it can
 * and merges what comes back, so losing Shizuku degrades the report rather than
 * breaking it.
 */
public interface AccessCollector {
    public val id: String
    public val tier: CollectorTier

    /** Cheap check used to render the setup screen without running a scan. */
    public suspend fun isAvailable(): Boolean

    public suspend fun collect(windowStartMs: Long, nowMs: Long): CollectionResult
}
