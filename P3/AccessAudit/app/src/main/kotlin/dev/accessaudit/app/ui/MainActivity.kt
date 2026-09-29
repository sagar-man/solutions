package dev.accessaudit.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.accessaudit.app.AccessAuditApp
import dev.accessaudit.app.collect.ShellGateway
import dev.accessaudit.app.export.ExportFormat
import dev.accessaudit.app.ui.components.relativeTime
import dev.accessaudit.app.ui.screens.AppDetailScreen
import dev.accessaudit.app.ui.screens.AppListScreen
import dev.accessaudit.app.ui.screens.FindingsScreen
import dev.accessaudit.app.ui.screens.SetupScreen
import dev.accessaudit.app.ui.theme.AccessAuditTheme
import kotlinx.coroutines.launch

private const val SHIZUKU_REQUEST_CODE = 4001

private enum class Tab(val label: String) { APPS("Apps"), FINDINGS("Flagged"), SETUP("Setup") }

public class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as AccessAuditApp
        setContent {
            AccessAuditTheme {
                AuditApp(app = app, activity = this)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuditApp(app: AccessAuditApp, activity: ComponentActivity) {
    val viewModel: AuditViewModel = viewModel(factory = AuditViewModel.factory(app))
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(Tab.APPS) }
    var selectedPackage by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    val apps by viewModel.apps.collectAsState()
    val findings by viewModel.findings.collectAsState()
    val kindUsage by viewModel.kindUsage.collectAsState()
    val filters by viewModel.filters.collectAsState()
    val scanState by viewModel.scanState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(selectedPackage?.let { "App detail" } ?: "Access Audit")
                        Text(
                            subtitle(scanState),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                navigationIcon = {
                    if (selectedPackage != null) {
                        IconButton(onClick = { selectedPackage = null }) {
                            Icon(Icons.Filled.Apps, contentDescription = "Back to the app list")
                        }
                    }
                },
                actions = {
                    if (scanState.running) {
                        CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp))
                    } else {
                        IconButton(onClick = { viewModel.scanNow() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Scan now")
                        }
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Export")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        ExportFormat.entries.forEach { format ->
                            DropdownMenuItem(
                                text = { Text(exportLabel(format)) },
                                onClick = {
                                    menuOpen = false
                                    scope.launch { exportAndShare(activity, viewModel, format) }
                                },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry && selectedPackage == null,
                        onClick = {
                            tab = entry
                            selectedPackage = null
                        },
                        icon = {
                            Icon(
                                when (entry) {
                                    Tab.APPS -> Icons.Filled.Apps
                                    Tab.FINDINGS -> Icons.Filled.Warning
                                    Tab.SETUP -> Icons.Filled.Settings
                                },
                                contentDescription = entry.label,
                            )
                        },
                        label = { Text(entry.label) },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.Top) {
            val current = selectedPackage
            if (current != null) {
                // Keyed remember: rebuilding these flows on every recomposition
                // would restart the queries and flicker the detail screen.
                val opsFlow = remember(current, scanState.windowStartMs) {
                    viewModel.observeOps(current, scanState.windowStartMs)
                }
                val findingsFlow = remember(current) { viewModel.observeFindingsForApp(current) }
                val ops by opsFlow.collectAsState(initial = emptyList())
                val appFindings by findingsFlow.collectAsState(initial = emptyList())
                AppDetailScreen(
                    packageName = current,
                    label = apps.firstOrNull { it.packageName == current }?.label ?: current,
                    ops = ops,
                    findings = appFindings,
                    nowMs = System.currentTimeMillis(),
                    onSuppress = { ruleId -> viewModel.suppress(current, ruleId) },
                )
            } else {
                when (tab) {
                    Tab.APPS -> AppListScreen(
                        apps = apps,
                        filters = filters,
                        scanState = scanState,
                        onFilterChange = { update -> viewModel.setFilters(update) },
                        onAppClick = { selectedPackage = it },
                    )

                    Tab.FINDINGS -> FindingsScreen(
                        findings = findings,
                        kindUsage = kindUsage,
                        onAppClick = { selectedPackage = it },
                    )

                    Tab.SETUP -> SetupScreen(
                        scanState = scanState,
                        onGrantUsageAccess = { activity.openUsageAccessSettings() },
                        onRequestShizuku = { viewModel.requestShizukuPermission(SHIZUKU_REQUEST_CODE) },
                        onOpenShizukuSite = { activity.openUrl("https://shizuku.rikka.app/") },
                        onCopyAdbCommand = { command -> activity.copyToClipboard(command) },
                    )
                }
            }
        }
    }
}

private fun subtitle(state: ScanState): String = when {
    state.error != null -> state.error
    state.lastScanAtMs == 0L -> "No scan yet"
    else -> "Scanned ${relativeTime(System.currentTimeMillis(), state.lastScanAtMs)} - " +
        "${state.appsScanned} apps, ${state.appsFlagged} flagged" +
        if (state.accessLogUnavailable) " - permissions only" else ""
}

private fun exportLabel(format: ExportFormat): String = when (format) {
    ExportFormat.JSON -> "Export JSON report"
    ExportFormat.FINDINGS_CSV -> "Export findings (CSV)"
    ExportFormat.ACCESS_MATRIX_CSV -> "Export access matrix (CSV)"
    ExportFormat.TEXT -> "Export text report"
}

private suspend fun exportAndShare(
    activity: ComponentActivity,
    viewModel: AuditViewModel,
    format: ExportFormat,
) {
    val result = viewModel.export(format)
    if (result == null) {
        Toast.makeText(activity, "Run a scan first - there is nothing to export yet.", Toast.LENGTH_SHORT).show()
        return
    }
    val (file, usedFormat) = result
    val exporter = dev.accessaudit.app.export.ReportExporter(activity)
    activity.startActivity(Intent.createChooser(exporter.shareIntent(file, usedFormat), "Share report"))
}

private fun Context.openUsageAccessSettings() {
    runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        .onFailure { Toast.makeText(this, "Could not open Settings on this device.", Toast.LENGTH_SHORT).show() }
}

private fun Context.openUrl(url: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        .onFailure { Toast.makeText(this, url, Toast.LENGTH_LONG).show() }
}

private fun Context.copyToClipboard(text: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(ShellGateway.ADB_GRANT_COMMAND, text))
    Toast.makeText(this, "Command copied.", Toast.LENGTH_SHORT).show()
}
