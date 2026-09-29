package dev.accessaudit.app.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dev.accessaudit.core.model.AuditReport
import dev.accessaudit.core.report.ReportFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Output shapes a developer might actually want off the device. */
public enum class ExportFormat(public val extension: String, public val mimeType: String) {
    JSON("json", "application/json"),
    FINDINGS_CSV("csv", "text/csv"),
    ACCESS_MATRIX_CSV("csv", "text/csv"),
    TEXT("txt", "text/plain"),
}

/**
 * Writes a report to the app's cache and hands back a share intent.
 *
 * Export is explicitly user-initiated and goes through a content URI: the app has
 * no INTERNET permission, so the only way this data leaves the device is if
 * someone deliberately shares it.
 */
public class ReportExporter(private val context: Context) {

    public suspend fun write(report: AuditReport, format: ExportFormat): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "reports").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(report.generatedAtMs))
            val name = when (format) {
                ExportFormat.JSON -> "access-audit-$stamp.json"
                ExportFormat.FINDINGS_CSV -> "access-audit-findings-$stamp.csv"
                ExportFormat.ACCESS_MATRIX_CSV -> "access-audit-matrix-$stamp.csv"
                ExportFormat.TEXT -> "access-audit-$stamp.txt"
            }
            val content = when (format) {
                ExportFormat.JSON -> ReportFormatter.toJson(report)
                ExportFormat.FINDINGS_CSV -> ReportFormatter.findingsCsv(report)
                ExportFormat.ACCESS_MATRIX_CSV -> ReportFormatter.accessMatrixCsv(report)
                ExportFormat.TEXT -> ReportFormatter.toText(report)
            }
            File(dir, name).apply { writeText(content) }
        }

    public fun shareIntent(file: File, format: ExportFormat): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.reports", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = format.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
