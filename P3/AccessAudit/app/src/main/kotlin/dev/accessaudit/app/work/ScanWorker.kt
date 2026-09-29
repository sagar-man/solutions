package dev.accessaudit.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.accessaudit.app.AccessAuditApp
import dev.accessaudit.core.model.Severity

/**
 * Periodic scan.
 *
 * Its job is to capture appop history before the platform drops it. A missed run
 * is a permanent hole in the log, so failures retry rather than being swallowed.
 */
public class ScanWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? AccessAuditApp ?: return Result.failure()
        return runCatching { app.repository.runScan() }
            .fold(
                onSuccess = { outcome ->
                    val critical = outcome.report.flagged.count { assessment ->
                        assessment.findings.any { it.severity == Severity.CRITICAL }
                    }
                    Result.success(
                        androidx.work.workDataOf(
                            KEY_SCAN_ID to outcome.scanId,
                            KEY_FLAGGED to outcome.report.flagged.size,
                            KEY_CRITICAL to critical,
                        ),
                    )
                },
                // Retry: a transient failure (Shizuku restarting, database busy)
                // should not cost us a window of history.
                onFailure = { if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure() },
            )
    }

    public companion object {
        public const val KEY_SCAN_ID: String = "scanId"
        public const val KEY_FLAGGED: String = "flagged"
        public const val KEY_CRITICAL: String = "critical"
        private const val MAX_ATTEMPTS = 3
    }
}
