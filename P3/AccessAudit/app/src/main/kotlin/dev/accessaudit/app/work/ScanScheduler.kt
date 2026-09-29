package dev.accessaudit.app.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

public object ScanScheduler {

    private const val PERIODIC_WORK = "access-audit-periodic-scan"
    private const val ONE_SHOT_WORK = "access-audit-manual-scan"

    /**
     * Schedules the recurring capture.
     *
     * Six hours is a deliberate compromise: the platform keeps appop history for
     * roughly a day, so four captures per day leaves margin for a skipped run
     * without losing events, while staying cheap enough to run on battery.
     * `KEEP` means calling this on every launch does not reset the schedule.
     */
    public fun ensureScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<ScanWorker>(6, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    // Nothing here touches the network; the only cost is CPU, so
                    // we just ask not to run on a critically low battery.
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** Runs a scan now, from the UI's refresh action. */
    public fun scanNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_SHOT_WORK,
            androidx.work.ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ScanWorker>().build(),
        )
    }

    public fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK)
    }
}
