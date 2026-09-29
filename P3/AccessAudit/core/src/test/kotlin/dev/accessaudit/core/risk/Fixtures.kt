package dev.accessaudit.core.risk

import dev.accessaudit.core.model.AccessKind
import dev.accessaudit.core.model.AppAccessProfile
import dev.accessaudit.core.model.AppIdentity
import dev.accessaudit.core.model.OpMode
import dev.accessaudit.core.model.OpTally
import dev.accessaudit.core.ops.OpCatalog

internal const val NOW = 1_700_000_000_000L
internal const val DAY = 86_400_000L

internal fun context(
    nowMs: Long = NOW,
    windowHours: Long = 24,
    deviceSdkInt: Int = 34,
    suppressions: Set<String> = emptySet(),
    medians: Map<String, Int> = emptyMap(),
) = AuditContext(
    nowMs = nowMs,
    windowStartMs = nowMs - windowHours * 3_600_000L,
    deviceSdkInt = deviceSdkInt,
    suppressions = suppressions,
    deviceOpMedians = medians,
)

internal fun app(
    packageName: String = "com.example.app",
    installer: String? = "com.android.vending",
    isSystem: Boolean = false,
    granted: Set<String> = emptySet(),
    requested: Set<String> = granted,
    targetSdk: Int = 34,
    accessibilityServices: List<String> = emptyList(),
    isDebuggable: Boolean = false,
    sharedUserId: String? = null,
    allowsBackup: Boolean = false,
    executables: List<dev.accessaudit.core.model.ExecutableArtifact> = emptyList(),
    specialAccess: Set<String> = emptySet(),
) = AppIdentity(
    packageName = packageName,
    label = packageName.substringAfterLast('.'),
    uid = 10_000,
    installerPackage = installer,
    isSystem = isSystem,
    targetSdk = targetSdk,
    grantedPermissions = granted,
    requestedPermissions = requested + granted,
    accessibilityServices = accessibilityServices,
    isDebuggable = isDebuggable,
    sharedUserId = sharedUserId,
    allowsBackup = allowsBackup,
    executableArtifacts = executables,
    specialAccess = specialAccess,
)

/** Builds a tally without making every test spell out nine fields. */
internal fun tally(
    op: String,
    total: Int = 1,
    background: Int = 0,
    denied: Int = 0,
    lastAccessMs: Long = NOW - 3_600_000L,
    durationMs: Long = 0,
    mode: OpMode = OpMode.ALLOW,
) = OpTally(
    op = OpCatalog.normalizeOp(op),
    kind = OpCatalog.kindOf(op),
    mode = mode,
    totalCount = total,
    foregroundCount = total - background,
    backgroundCount = background,
    deniedCount = denied,
    lastAccessMs = lastAccessMs,
    lastBackgroundAccessMs = if (background > 0) lastAccessMs else 0L,
    totalDurationMs = durationMs,
)

internal fun profile(
    app: AppIdentity = app(),
    tallies: List<OpTally> = emptyList(),
    foregroundTimeMs: Long = 10 * 60_000L,
    sources: Set<String> = setOf("package-manager", "appops"),
) = AppAccessProfile(
    app = app,
    tallies = tallies,
    foregroundTimeMs = foregroundTimeMs,
    sources = sources,
)

internal fun kinds(vararg kind: AccessKind): Set<AccessKind> = kind.toSet()
