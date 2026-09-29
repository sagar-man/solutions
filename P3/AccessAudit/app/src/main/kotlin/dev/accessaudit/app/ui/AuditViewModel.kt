package dev.accessaudit.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.accessaudit.app.AccessAuditApp
import dev.accessaudit.app.collect.CollectorStatus
import dev.accessaudit.app.collect.CollectorTier
import dev.accessaudit.app.collect.ShellChannel
import dev.accessaudit.app.data.db.AppWithRisk
import dev.accessaudit.app.data.db.FindingEntity
import dev.accessaudit.app.data.db.KindUsage
import dev.accessaudit.app.data.db.OpSummary
import dev.accessaudit.app.data.repo.AuditRepository
import dev.accessaudit.app.export.ExportFormat
import dev.accessaudit.app.export.ReportExporter
import dev.accessaudit.core.model.AccessKind
import dev.accessaudit.core.model.AuditReport
import dev.accessaudit.core.model.Severity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/** How the app list is being filtered. */
public data class ListFilters(
    val includeSystemApps: Boolean = false,
    val onlyFlagged: Boolean = false,
    /** null means "any access class". */
    val kind: AccessKind? = null,
    val query: String = "",
)

public data class ScanState(
    val running: Boolean = false,
    val lastScanAtMs: Long = 0,
    val windowStartMs: Long = 0,
    val appsScanned: Int = 0,
    val appsFlagged: Int = 0,
    val collectors: List<CollectorStatus> = emptyList(),
    val shellChannel: ShellChannel = ShellChannel.NONE,
    val error: String? = null,
) {
    /**
     * True when no access-log collector is live, i.e. the report can only show
     * what apps *may* do, not what they did. The UI must say so prominently
     * rather than presenting a static inventory as a behaviour log.
     */
    public val accessLogUnavailable: Boolean
        get() = collectors.none { it.tier == CollectorTier.APP_OPS && it.available }
}

@OptIn(ExperimentalCoroutinesApi::class)
public class AuditViewModel(
    private val repository: AuditRepository,
    private val exporter: ReportExporter,
    private val app: AccessAuditApp,
) : ViewModel() {

    private val _scanState = MutableStateFlow(ScanState())
    public val scanState: StateFlow<ScanState> = _scanState

    private val _filters = MutableStateFlow(ListFilters())
    public val filters: StateFlow<ListFilters> = _filters

    /** The most recent report held in memory, for export. Null until a scan runs. */
    private var lastReport: AuditReport? = null

    private val latestScan = repository.observeLatestScan()

    /**
     * The app list.
     *
     * Only `includeSystemApps` reaches the database query, and it is deduplicated
     * first: without that, every keystroke in the search box would restart the
     * Room query. The remaining predicates are applied in memory, which is
     * cheaper than re-running the join over a few hundred rows.
     */
    public val apps: StateFlow<List<AppWithRisk>> =
        combine(
            latestScan,
            _filters.map { it.includeSystemApps }.distinctUntilChanged(),
        ) { scan, includeSystem -> scan to includeSystem }
            .flatMapLatest { (scan, includeSystem) ->
                if (scan == null) {
                    flowOf(emptyList())
                } else {
                    repository.observeApps(scan.id, includeSystem)
                }
            }
            .combine(_filters) { apps, filters -> applyFilters(apps, filters) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    public val findings: StateFlow<List<FindingEntity>> = latestScan
        .flatMapLatest { scan ->
            if (scan == null) flowOf(emptyList()) else repository.observeFindings(scan.id, Severity.MEDIUM)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    public val kindUsage: StateFlow<List<KindUsage>> = latestScan
        .flatMapLatest { scan ->
            if (scan == null) flowOf(emptyList()) else repository.observeKindUsage(scan.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch { refreshStatus() }
    }

    /**
     * Client-side filtering: the joined query already narrows by system/user, and
     * the remaining predicates are cheap over a few hundred rows - cheaper than
     * rebuilding a parameterised query for each filter combination.
     */
    private fun applyFilters(apps: List<AppWithRisk>, filters: ListFilters): List<AppWithRisk> =
        apps.asSequence()
            .filter { !filters.onlyFlagged || it.findingCount > 0 }
            .filter { app ->
                filters.kind == null || app.observedKinds.split(",").any { it == filters.kind.label }
            }
            .filter { app ->
                filters.query.isBlank() ||
                    app.label.contains(filters.query, ignoreCase = true) ||
                    app.packageName.contains(filters.query, ignoreCase = true)
            }
            .toList()

    public fun setFilters(update: (ListFilters) -> ListFilters) {
        _filters.value = update(_filters.value)
    }

    public suspend fun refreshStatus() {
        val scan = repository.latestScan()
        _scanState.value = _scanState.value.copy(
            lastScanAtMs = scan?.startedAtMs ?: 0,
            windowStartMs = scan?.windowStartMs ?: 0,
            appsScanned = scan?.appsScanned ?: 0,
            appsFlagged = scan?.appsFlagged ?: 0,
            collectors = repository.collectorStatuses(),
            shellChannel = app.shellGateway.channel(),
        )
    }

    public fun scanNow() {
        if (_scanState.value.running) return
        _scanState.value = _scanState.value.copy(running = true, error = null)
        viewModelScope.launch {
            runCatching { repository.runScan() }
                .onSuccess { outcome ->
                    lastReport = outcome.report
                    _scanState.value = _scanState.value.copy(
                        running = false,
                        lastScanAtMs = outcome.report.generatedAtMs,
                        windowStartMs = outcome.report.windowStartMs,
                        appsScanned = outcome.report.assessments.size,
                        appsFlagged = outcome.report.flagged.size,
                        collectors = outcome.collectorStatus,
                        shellChannel = app.shellGateway.channel(),
                    )
                }
                .onFailure { error ->
                    _scanState.value = _scanState.value.copy(
                        running = false,
                        error = "Scan failed: ${error.message ?: error::class.simpleName}",
                    )
                }
        }
    }

    public fun observeOps(packageName: String, sinceMs: Long): kotlinx.coroutines.flow.Flow<List<OpSummary>> =
        repository.observeOps(packageName, sinceMs)

    public fun observeFindingsForApp(
        packageName: String,
    ): kotlinx.coroutines.flow.Flow<List<FindingEntity>> = latestScan.flatMapLatest { scan ->
        if (scan == null) flowOf(emptyList()) else repository.observeFindingsForApp(scan.id, packageName)
    }

    public fun suppress(packageName: String, ruleId: String) {
        viewModelScope.launch { repository.suppress(packageName, ruleId) }
    }

    public fun unsuppress(packageName: String, ruleId: String) {
        viewModelScope.launch { repository.unsuppress(packageName, ruleId) }
    }

    /**
     * Exports the in-memory report. Returns null when no scan has run in this
     * process yet - the database holds history, but a full report object is only
     * rebuilt by a scan.
     */
    public suspend fun export(format: ExportFormat): Pair<File, ExportFormat>? {
        val report = lastReport ?: return null
        return exporter.write(report, format) to format
    }

    public fun requestShizukuPermission(requestCode: Int) {
        app.shellGateway.requestShizukuPermission(requestCode)
    }

    public companion object {
        public fun factory(app: AccessAuditApp): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AuditViewModel(app.repository, ReportExporter(app), app) as T
            }
    }
}
