package dev.accessaudit.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.accessaudit.app.data.db.AppWithRisk
import dev.accessaudit.app.ui.ListFilters
import dev.accessaudit.app.ui.ScanState
import dev.accessaudit.app.ui.components.AccessKindChip
import dev.accessaudit.app.ui.components.CaveatCard
import dev.accessaudit.app.ui.theme.RiskColors
import dev.accessaudit.core.model.AccessKind
import dev.accessaudit.core.model.RiskLevel

/**
 * The main list: every app, worst first.
 *
 * Sorting by risk rather than alphabetically is the whole point - a user opening
 * this app wants to know what to look at, not to browse an inventory.
 */
@Composable
public fun AppListScreen(
    apps: List<AppWithRisk>,
    filters: ListFilters,
    scanState: ScanState,
    onFilterChange: ((ListFilters) -> ListFilters) -> Unit,
    onAppClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = filters.query,
            onValueChange = { query -> onFilterChange { it.copy(query = query) } },
            label = { Text("Search apps") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )

        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = filters.onlyFlagged,
                onClick = { onFilterChange { it.copy(onlyFlagged = !it.onlyFlagged) } },
                label = { Text("Flagged only") },
            )
            FilterChip(
                selected = filters.includeSystemApps,
                onClick = { onFilterChange { it.copy(includeSystemApps = !it.includeSystemApps) } },
                label = { Text("Include system") },
            )
            // One chip per access class, so "show me everything using the mic"
            // is one tap.
            for (kind in ACCESS_FILTERS) {
                FilterChip(
                    selected = filters.kind == kind,
                    onClick = {
                        onFilterChange { current ->
                            current.copy(kind = if (current.kind == kind) null else kind)
                        }
                    },
                    label = { Text(kind.label) },
                )
            }
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (scanState.accessLogUnavailable) {
                item {
                    CaveatCard(
                        title = "Showing permissions, not behaviour",
                        body = "No access-log source is connected, so this list reflects what each " +
                            "app is allowed to do - not what it actually did. Open Setup to " +
                            "connect Shizuku or grant access over adb.",
                    )
                }
            }
            if (apps.isEmpty()) {
                item {
                    Text(
                        if (scanState.lastScanAtMs == 0L) {
                            "No scan yet. Pull the refresh action to run one."
                        } else {
                            "No apps match these filters."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(apps, key = { it.packageName }) { app ->
                AppRow(app = app, onClick = { onAppClick(app.packageName) })
            }
        }
    }
}

private val ACCESS_FILTERS = listOf(
    AccessKind.READ,
    AccessKind.WRITE,
    AccessKind.EXECUTE,
    AccessKind.CAPTURE,
    AccessKind.LOCATION,
    AccessKind.MESSAGING,
    AccessKind.SYSTEM_CONTROL,
)

@Composable
private fun AppRow(app: AppWithRisk, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val level = runCatching { RiskLevel.valueOf(app.riskLevel) }.getOrDefault(RiskLevel.CLEAN)
    Card(modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // The score doubles as the visual weight of the row.
            Text(
                text = app.riskScore.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = RiskColors.forLevel(level),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(app.label, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(
                    app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    app.observedKinds.split(",")
                        .filter { it.isNotBlank() }
                        .take(4)
                        .forEach { AccessKindChip(AccessKind.fromLabel(it)) }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(level.name, style = MaterialTheme.typography.labelSmall, color = RiskColors.forLevel(level))
                if (app.findingCount > 0) {
                    Text(
                        "${app.findingCount} finding${if (app.findingCount == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
