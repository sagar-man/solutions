package dev.accessaudit.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.accessaudit.app.collect.CollectorStatus
import dev.accessaudit.app.collect.CollectorTier
import dev.accessaudit.app.collect.ShellChannel
import dev.accessaudit.app.collect.ShellGateway
import dev.accessaudit.app.ui.ScanState
import dev.accessaudit.app.ui.components.CaveatCard

/**
 * The honesty screen: what each data source can see, and what is missing.
 *
 * This is not an onboarding funnel. Its purpose is that the user can always tell
 * which tiers are live, because the difference between "this app used your
 * microphone twice while you weren't looking" and "this app is allowed to use
 * your microphone" is the entire value of the report.
 */
@Composable
public fun SetupScreen(
    scanState: ScanState,
    onGrantUsageAccess: () -> Unit,
    onRequestShizuku: () -> Unit,
    onOpenShizukuSite: () -> Unit,
    onCopyAdbCommand: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            CaveatCard(
                title = "What Android lets this app see",
                body = "Android does not let any app watch another app's file reads and writes. " +
                    "What it does keep is a log of privileged operations - camera, microphone, " +
                    "location, contacts, SMS, all-files access - including whether the app was " +
                    "visible at the time. Reading that log needs shell-level access, which is what " +
                    "the steps below grant. True file-level read/write/execute tracing needs root.",
            )
        }

        item { Text("Data sources", style = MaterialTheme.typography.titleMedium) }

        CollectorTier.entries.forEach { tier ->
            item(key = tier.name) {
                TierCard(
                    tier = tier,
                    statuses = scanState.collectors.filter { it.tier == tier },
                )
            }
        }

        item {
            Text("Setup", style = MaterialTheme.typography.titleMedium)
        }

        item {
            SetupStep(
                title = "1. Usage access (optional, no tools needed)",
                body = "Lets the report tell an app you opened from one that acted on its own.",
                actionLabel = "Open Settings",
                onAction = onGrantUsageAccess,
            )
        }

        item {
            SetupStep(
                title = "2a. Shizuku (recommended, no root)",
                body = "Shizuku runs a shell-privileged service that this app can ask to read the " +
                    "access log. You start Shizuku once per boot from adb, or automatically if " +
                    "your device is rooted.",
                actionLabel = if (scanState.shellChannel == ShellChannel.SHIZUKU) {
                    "Connected"
                } else {
                    "Request permission"
                },
                onAction = onRequestShizuku,
                secondaryLabel = "What is Shizuku?",
                onSecondary = onOpenShizukuSite,
            )
        }

        item {
            SetupStep(
                title = "2b. Or grant over adb directly",
                body = "One command, and it survives reboots until the app is reinstalled. Use this " +
                    "if you would rather not install Shizuku.",
                actionLabel = "Copy command",
                onAction = { onCopyAdbCommand(ShellGateway.ADB_GRANT_COMMAND) },
                code = ShellGateway.ADB_GRANT_COMMAND,
            )
        }

        item {
            CaveatCard(
                title = "This app has no network permission",
                body = "Access Audit does not request android.permission.INTERNET, so nothing it " +
                    "collects can leave the device unless you export a report and share it " +
                    "yourself. You can verify that in the manifest.",
            )
        }
    }
}

@Composable
private fun TierCard(tier: CollectorTier, statuses: List<CollectorStatus>, modifier: Modifier = Modifier) {
    val live = statuses.any { it.available }
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tier.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    if (live) "active" else "not available",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (live) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Text(
                "Requires: ${tier.requirement}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Surface the collector's own explanation verbatim; a generic
            // "unavailable" would leave the user with nothing to act on.
            statuses.mapNotNull { it.reason }.distinct().forEach { reason ->
                Text(reason, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SetupStep(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    code: String? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall)
            if (code != null) {
                Text(
                    code,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAction) { Text(actionLabel) }
                if (secondaryLabel != null && onSecondary != null) {
                    androidx.compose.material3.TextButton(onClick = onSecondary) { Text(secondaryLabel) }
                }
            }
        }
    }
}
