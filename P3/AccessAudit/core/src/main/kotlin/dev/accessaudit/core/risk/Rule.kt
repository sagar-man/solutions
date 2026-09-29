package dev.accessaudit.core.risk

import dev.accessaudit.core.model.AppAccessProfile
import dev.accessaudit.core.model.AppIdentity
import dev.accessaudit.core.model.Finding
import dev.accessaudit.core.model.OpTally

/**
 * Device-wide facts a rule may need, injected so that rules stay pure functions
 * and the whole engine is testable without a device.
 */
public data class AuditContext(
    val nowMs: Long,
    /** Start of the window the events cover; findings should not claim more than this. */
    val windowStartMs: Long,
    val deviceSdkInt: Int = 0,
    /**
     * Packages the user has explicitly accepted. Suppression is recorded per
     * `packageName:ruleId`, so trusting one behaviour of an app does not mute
     * the others.
     */
    val suppressions: Set<String> = emptySet(),
    /** Baseline of how often each op is used device-wide, for the outlier rule. */
    val deviceOpMedians: Map<String, Int> = emptyMap(),
) {
    public fun isSuppressed(packageName: String, ruleId: String): Boolean =
        "$packageName:$ruleId" in suppressions

    public val windowMs: Long get() = (nowMs - windowStartMs).coerceAtLeast(0)
}

/** Everything one rule gets to look at. */
public data class RuleInput(
    val profile: AppAccessProfile,
    val context: AuditContext,
) {
    public val app: AppIdentity get() = profile.app
    public val tallies: List<OpTally> get() = profile.tallies
}

/**
 * A single detection.
 *
 * Rules are deliberately small and independent: each one answers one question
 * and returns zero or more [Finding]s. That keeps them individually testable
 * and makes the report explainable - every point of an app's score traces back
 * to a named rule.
 */
public interface Rule {
    /** Stable identifier; also the key used for user suppression. */
    public val id: String

    /** Human summary of what the rule looks for, shown in the app's rule list. */
    public val description: String

    public fun evaluate(input: RuleInput): List<Finding>
}

/** Convenience base class that skips system apps, which are noisy and unactionable. */
public abstract class UserAppRule : Rule {
    /**
     * Whether pre-installed apps are exempt. Most behavioural rules exempt them:
     * the system camera using the camera is not news, and a user cannot act on
     * it anyway. Rules about *sideloaded* behaviour override this to false.
     */
    protected open val skipSystemApps: Boolean = true

    final override fun evaluate(input: RuleInput): List<Finding> {
        if (skipSystemApps && input.app.isSystem) return emptyList()
        if (input.context.isSuppressed(input.app.packageName, id)) return emptyList()
        return check(input)
    }

    protected abstract fun check(input: RuleInput): List<Finding>
}
