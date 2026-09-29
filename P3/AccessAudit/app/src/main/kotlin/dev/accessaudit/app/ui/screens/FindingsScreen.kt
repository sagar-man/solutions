package dev.accessaudit.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.accessaudit.app.data.db.FindingEntity
import dev.accessaudit.app.data.db.KindUsage
import dev.accessaudit.app.ui.components.EvidenceLine
import dev.accessaudit.app.ui.components.SeverityChip
import dev.accessaudit.core.model.Severity

/**
 * Everything flagged across the device, worst first, plus a per-access-class
 * summary of how many apps are exercising each capability.
 */
@Composable
public fun FindingsScreen(
    findings: List<FindingEntity>,
    kindUsage: List<KindUsage>,
    onAppClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (kindUsage.isNotEmpty()) {
            item { Text("Access in use on this device", style = MaterialTheme.typography.titleMedium) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        kindUsage.forEach { usage ->
                            Row(Modifier.fillMaxWidth()) {
                                Text(
                                    usage.accessKind,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "${usage.appCount} app${if (usage.appCount == 1) "" else "s"}, " +
                                        "${usage.totalAccesses} uses",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Text(
                if (findings.isEmpty()) "Nothing flagged" else "Flagged (${findings.size})",
                style = MaterialTheme.typography.titleMedium,
            )
        }

        if (findings.isEmpty()) {
            item {
                Text(
                    "No app tripped a rule at MEDIUM or above in the latest scan. Findings below " +
                        "that threshold are hygiene notes and are shown on each app's page.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        items(findings, key = { "${it.packageName}:${it.ruleId}" }) { finding ->
            FindingSummaryCard(finding = finding, onClick = { onAppClick(finding.packageName) })
        }
    }
}

@Composable
private fun FindingSummaryCard(
    finding: FindingEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val severity = runCatching { Severity.valueOf(finding.severity) }.getOrDefault(Severity.INFO)
    Card(modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SeverityChip(severity)
                Text(finding.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            }
            Text(
                finding.packageName,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            finding.evidence.lines().take(2).filter { it.isNotBlank() }.forEach { EvidenceLine("• $it") }
        }
    }
}
