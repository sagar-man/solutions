package dev.accessaudit.app.collect

import dev.accessaudit.core.parse.AppOpsDumpParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Tier 2, primary route: read the platform's own appop history by running
 * `dumpsys appops` through a privileged shell and parsing the output.
 *
 * This is the collector that answers the actual question - which app used which
 * access, when, and whether the app was visible at the time. It reuses
 * [AppOpsDumpParser] from `:core`, so the parsing is covered by unit tests
 * rather than only exercised on a device.
 *
 * It is preferred over [AppOpsReflectionCollector] because `dumpsys` output is a
 * stable-ish text format we can test against captured fixtures, whereas the
 * in-process API is `@SystemApi` and only reachable reflectively.
 */
public class ShellAppOpsCollector(private val shell: ShellGateway) : AccessCollector {

    override val id: String = "appops-shell"
    override val tier: CollectorTier = CollectorTier.APP_OPS

    override suspend fun isAvailable(): Boolean = shell.channel() != ShellChannel.NONE

    override suspend fun collect(windowStartMs: Long, nowMs: Long): CollectionResult =
        withContext(Dispatchers.IO) {
            val channel = shell.channel()
            if (channel == ShellChannel.NONE) {
                return@withContext CollectionResult(
                    collectorId = id,
                    tier = tier,
                    available = false,
                    unavailableReason = "No privileged shell. Without Shizuku or root, this app " +
                        "cannot read other apps' access history - only the permissions they hold.",
                )
            }

            val result = shell.run("dumpsys appops")
            if (!result.succeeded) {
                return@withContext CollectionResult(
                    collectorId = id,
                    tier = tier,
                    available = false,
                    unavailableReason = "`dumpsys appops` failed over ${channel.label}: " +
                        result.stderr.take(300).ifBlank { "exit code ${result.exitCode}" },
                )
            }

            // The dump reports each access as an age relative to capture time, so
            // the parser is anchored to `nowMs` rather than to a later clock read.
            val dump = AppOpsDumpParser(nowMs = nowMs).parse(result.stdout)
            CollectionResult(
                collectorId = id,
                tier = tier,
                available = true,
                events = dump.events().filter { it.timestampMs == 0L || it.timestampMs >= windowStartMs },
            )
        }
}
