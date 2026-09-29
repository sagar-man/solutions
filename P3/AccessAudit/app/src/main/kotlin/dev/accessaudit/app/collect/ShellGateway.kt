package dev.accessaudit.app.collect

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader

/** How a shell command was executed, for the evidence trail. */
public enum class ShellChannel(public val label: String) {
    SHIZUKU("Shizuku"),
    ROOT("root"),
    NONE("unavailable"),
}

public data class ShellResult(
    val channel: ShellChannel,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    public val succeeded: Boolean get() = exitCode == 0 && stdout.isNotBlank()
}

/**
 * Runs shell commands with the privilege needed to read other apps' appop history.
 *
 * Two channels, tried in order:
 *  1. **Shizuku** - the user starts a shell-privileged service once (via adb or a
 *     rooted device) and this app then asks it to run commands. This is the only
 *     way an unrooted device can reach this data.
 *  2. **root** - `su -c`, when available.
 *
 * `Shizuku.newProcess` is annotated `@RestrictTo` in the published API rather
 * than being formally public, so it is invoked reflectively. That is contained
 * here, behind one function, so a Shizuku API change breaks one call site
 * instead of the collectors.
 */
public class ShellGateway(private val context: Context) {

    public suspend fun channel(): ShellChannel = withContext(Dispatchers.IO) {
        when {
            shizukuReady() -> ShellChannel.SHIZUKU
            rootAvailable() -> ShellChannel.ROOT
            else -> ShellChannel.NONE
        }
    }

    public fun shizukuInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    }.getOrDefault(false)

    /** True when Shizuku is running *and* has granted this app permission. */
    public fun shizukuReady(): Boolean = runCatching {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    public fun shizukuRunningButUnauthorized(): Boolean = runCatching {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    public fun requestShizukuPermission(requestCode: Int) {
        runCatching { Shizuku.requestPermission(requestCode) }
    }

    private fun rootAvailable(): Boolean = runCatching {
        val process = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use(BufferedReader::readText)
        process.waitFor() == 0 && output.contains("uid=0")
    }.getOrDefault(false)

    public suspend fun run(command: String): ShellResult = withContext(Dispatchers.IO) {
        when (channel()) {
            ShellChannel.SHIZUKU -> runViaShizuku(command)
            ShellChannel.ROOT -> runViaRoot(command)
            ShellChannel.NONE -> ShellResult(
                channel = ShellChannel.NONE,
                exitCode = -1,
                stdout = "",
                stderr = "No privileged shell available.",
            )
        }
    }

    private fun runViaShizuku(command: String): ShellResult = runCatching {
        // Reflection: newProcess is @RestrictTo in the Shizuku API surface.
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        ).apply { isAccessible = true }

        val process = method.invoke(null, arrayOf("sh", "-c", command), null, null)
            ?: return@runCatching ShellResult(ShellChannel.SHIZUKU, -1, "", "newProcess returned null")

        val clazz = process.javaClass
        val stdout = (clazz.getMethod("getInputStream").invoke(process) as java.io.InputStream)
            .bufferedReader().use(BufferedReader::readText)
        val stderr = (clazz.getMethod("getErrorStream").invoke(process) as java.io.InputStream)
            .bufferedReader().use(BufferedReader::readText)
        val exit = clazz.getMethod("waitFor").invoke(process) as Int
        ShellResult(ShellChannel.SHIZUKU, exit, stdout, stderr)
    }.getOrElse { error ->
        ShellResult(
            channel = ShellChannel.SHIZUKU,
            exitCode = -1,
            stdout = "",
            stderr = "Shizuku call failed: ${error::class.simpleName}: ${error.message}",
        )
    }

    private fun runViaRoot(command: String): ShellResult = runCatching {
        val process = ProcessBuilder("su", "-c", command).start()
        val stdout = process.inputStream.bufferedReader().use(BufferedReader::readText)
        val stderr = process.errorStream.bufferedReader().use(BufferedReader::readText)
        ShellResult(ShellChannel.ROOT, process.waitFor(), stdout, stderr)
    }.getOrElse { error ->
        ShellResult(ShellChannel.ROOT, -1, "", "root call failed: ${error.message}")
    }

    public companion object {
        public const val SHIZUKU_PACKAGE: String = "moe.shizuku.privileged.api"

        /**
         * The grant that unlocks the in-process appops API. Offered to the user
         * as copyable text on the setup screen for the plain-adb route.
         */
        public const val ADB_GRANT_COMMAND: String =
            "adb shell pm grant dev.accessaudit.app android.permission.GET_APP_OPS_STATS"
    }
}
