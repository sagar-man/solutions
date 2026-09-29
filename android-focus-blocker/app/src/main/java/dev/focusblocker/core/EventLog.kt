package dev.focusblocker.core

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Append-only diagnostic log on disk.
 *
 * Spike 4 asks whether a session survives a reboot, so the evidence has to
 * outlive the process. An in-memory log would be erased by exactly the event
 * we are trying to observe.
 */
object EventLog {

    private const val FILE_NAME = "spike-events.log"
    private const val MAX_LINES = 400

    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    private val lock = Any()

    fun append(context: Context, line: String) {
        val text = "${stamp.format(Date())}  $line\n"
        synchronized(lock) {
            val file = File(context.applicationContext.filesDir, FILE_NAME)
            runCatching {
                file.appendText(text)
                val lines = file.readLines()
                if (lines.size > MAX_LINES) {
                    file.writeText(lines.takeLast(MAX_LINES).joinToString("\n", postfix = "\n"))
                }
            }
        }
    }

    /** Newest first, for display. */
    fun read(context: Context): List<String> {
        synchronized(lock) {
            val file = File(context.applicationContext.filesDir, FILE_NAME)
            if (!file.exists()) return emptyList()
            return runCatching { file.readLines().asReversed() }.getOrElse { emptyList() }
        }
    }

    fun clear(context: Context) {
        synchronized(lock) {
            File(context.applicationContext.filesDir, FILE_NAME).delete()
        }
    }
}
