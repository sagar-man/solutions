package dev.accessaudit.app

import android.app.Application
import dev.accessaudit.app.collect.AccessCollector
import dev.accessaudit.app.collect.AppOpsReflectionCollector
import dev.accessaudit.app.collect.PackageInventoryCollector
import dev.accessaudit.app.collect.RootFileAuditCollector
import dev.accessaudit.app.collect.ShellAppOpsCollector
import dev.accessaudit.app.collect.ShellGateway
import dev.accessaudit.app.collect.UsageStatsCollector
import dev.accessaudit.app.data.db.AppDatabase
import dev.accessaudit.app.data.repo.AuditRepository
import dev.accessaudit.app.work.ScanScheduler

/**
 * Manual dependency container.
 *
 * No DI framework on purpose: the graph is five objects deep and a container the
 * reader can follow is worth more here than the indirection.
 */
public class AccessAuditApp : Application() {

    public val shellGateway: ShellGateway by lazy { ShellGateway(this) }

    public val usageStatsCollector: UsageStatsCollector by lazy { UsageStatsCollector(this) }

    public val appOpsReflectionCollector: AppOpsReflectionCollector by lazy {
        AppOpsReflectionCollector(this)
    }

    public val collectors: List<AccessCollector> by lazy {
        listOf(
            // Order matters only for reporting; each one runs independently.
            PackageInventoryCollector(this),
            usageStatsCollector,
            ShellAppOpsCollector(shellGateway),
            appOpsReflectionCollector,
            RootFileAuditCollector(shellGateway),
        )
    }

    public val repository: AuditRepository by lazy {
        AuditRepository(AppDatabase.get(this), collectors)
    }

    override fun onCreate() {
        super.onCreate()
        // A periodic scan is what turns this from a snapshot tool into a log: the
        // platform discards appop history after roughly a day, so anything not
        // captured while it is still there is lost for good.
        ScanScheduler.ensureScheduled(this)
    }
}
