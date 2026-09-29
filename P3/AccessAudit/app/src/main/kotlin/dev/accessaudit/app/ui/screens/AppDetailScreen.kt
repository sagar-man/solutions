package dev.accessaudit.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.accessaudit.app.data.db.FindingEntity
import dev.accessaudit.app.data.db.OpSummary
import dev.accessaudit.app.ui.components.AccessKindChip
import dev.accessaudit.app.ui.components.EvidenceLine
import dev.accessaudit.app.ui.components.SeverityChip
import dev.accessaudit.app.ui.components.relativeTime
import dev.accessaudit.core.model.AccessKind
import dev.accessaudit.core.model.Severity
import dev.accessaudit.core.ops.OpCatalog

/**
 * Per-app detail: the access table first, then the findings.
 *
 * Deliberately in that order. The raw "what it used, how often, how much of it
 * while invisible" table is the evidence; the findings are one reading of it. A
 * developer needs to be able to check the second against the first.
 */
@Composable
public fun AppDetailScreen(
    packageName: String,
    label: String,
    ops: List<OpSummary>,
    findings: List<FindingEntity>,
    nowMs: Long,
    onSuppress: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column {
                Text(label, style = MaterialTheme.typography.titleLarge)
                Text(
                    packageName,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            Text("Access used", style = MaterialTheme.typography.titleMedium)
        }

        if (ops.isEmpty()) {
            item {
                Text(
                    "No access recorded for this app in the stored window.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            item { OpTableHeader() }
            items(ops, key = { it.op }) { op -> OpRow(op = op, nowMs = nowMs) }
        }

        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }

        item {
            Text(
                if (findings.isEmpty()) "No findings" else "Findings",
                style = MaterialTheme.typography.titleMedium,
            )
        }

        items(findings, key = { it.ruleId }) { finding ->
            FindingCard(finding = finding, onSuppress = { onSuppress(finding.ruleId) })
        }
    }
}

@Composable
private fun OpTableHeader() {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("OPERATION", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(2.4f))
        Text("USES", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(0.7f))
        Text("HIDDEN", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(0.9f))
        Text("LAST", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun OpRow(op: OpSummary, nowMs: Long, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(2.4f)) {
            Text(OpCatalog.labelOf(op.op), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            AccessKindChip(AccessKind.fromLabel(op.accessKind))
        }
        Text(op.total.toString(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.7f))
        // "Hidden" rather than "background": it is the user-meaningful framing of
        // the same number - access that happened with nothing on screen.
        Text(
            if (op.background > 0) "${op.background}" else "-",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (op.background > 0) FontWeight.Bold else FontWeight.Normal,
            color = if (op.background > 0) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(0.9f),
        )
        Text(
            relativeTime(nowMs, op.lastAccessMs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun FindingCard(finding: FindingEntity, onSuppress: () -> Unit, modifier: Modifier = Modifier) {
    val severity = runCatching { Severity.valueOf(finding.severity) }.getOrDefault(Severity.INFO)
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SeverityChip(severity)
                Text(finding.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            }
            Text(finding.rationale, style = MaterialTheme.typography.bodySmall)
            if (finding.evidence.isNotBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    finding.evidence.lines().forEach { EvidenceLine("• $it") }
                }
            }
            if (finding.recommendation.isNotBlank()) {
                Text(
                    finding.recommendation,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    finding.ruleId,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).align(Alignment.CenterVertically),
                )
                // Per-app, per-rule: accepting one behaviour never mutes the rest.
                TextButton(onClick = onSuppress) { Text("Accept this") }
            }
        }
    }
}
