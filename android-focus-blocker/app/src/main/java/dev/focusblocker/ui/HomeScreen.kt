package dev.focusblocker.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.focusblocker.core.AccessibilityStatus
import dev.focusblocker.core.EventLog
import dev.focusblocker.core.ForegroundAppLog
import dev.focusblocker.core.InstalledApps
import dev.focusblocker.core.LaunchableApp
import dev.focusblocker.core.SessionStore
import dev.focusblocker.detect.BlockerAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val clockFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val store = remember { SessionStore(context) }

    // One-second heartbeat drives every derived read on this screen.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000L)
            tick++
        }
    }

    var logVersion by remember { mutableIntStateOf(0) }
    var blocked by remember { mutableStateOf(store.blockedPackages) }
    var durationMinutes by remember { mutableIntStateOf(5) }
    var apps by remember { mutableStateOf<List<LaunchableApp>>(emptyList()) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { InstalledApps.launchable(context) }
    }

    val remaining = remember(tick) { store.remaining() }
    val enabled = remember(tick) { AccessibilityStatus.isEnabled(context) }
    val connected = remember(tick) { BlockerAccessibilityService.isConnected }
    val log = remember(logVersion) { EventLog.read(context) }
    val detections by ForegroundAppLog.detections.collectAsState()

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "FocusBlocker spikes",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Three throwaway experiments: can we detect the foreground app fast " +
                    "enough, can we cover it, and does a session survive a reboot?",
                style = MaterialTheme.typography.bodyMedium,
            )

            SpikeCard(
                title = "Permissions",
                subtitle = "Nothing below works until the accessibility service is on.",
            ) {
                StatusLine("Accessibility enabled in Settings", enabled)
                StatusLine("Service actually connected", connected)
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { context.openAccessibilitySettings() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (enabled) "Open accessibility settings" else "Enable FocusBlocker") }
                OutlinedButton(
                    onClick = { context.openBatterySettings() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Exempt from battery optimisation") }
                if (enabled && !connected) {
                    Text(
                        text = "Enabled but not connected. On Xiaomi, Samsung and Oppo ROMs this " +
                            "is normal after a while — it is the bug this whole project lives or " +
                            "dies on. Note the time and how long it took to happen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            SpikeCard(
                title = "Session",
                subtitle = "Spike 4: start one, reboot the phone, come back here.",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 5, 25, 60).forEach { minutes ->
                        OutlinedButton(onClick = { durationMinutes = minutes }) {
                            Text(
                                text = "${minutes}m",
                                fontWeight = if (minutes == durationMinutes) {
                                    FontWeight.Bold
                                } else {
                                    FontWeight.Normal
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (remaining.isActive) {
                    val totalSeconds = remaining.ms / 1_000L
                    Text(
                        text = "${totalSeconds / 60}m ${totalSeconds % 60}s remaining",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = "Clock trusted: ${remaining.clock}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (remaining.clockSkewMs > 5_000L || remaining.clockSkewMs < -5_000L) {
                        Text(
                            text = "Device clock moved by ${remaining.clockSkewMs / 1000}s " +
                                "mid-session — the monotonic deadline ignored it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            store.clearSession()
                            EventLog.append(context, "SESSION stopped by user")
                            logVersion++
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Stop session") }
                } else {
                    Text("No session running.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            store.startSession(durationMinutes * 60_000L)
                            EventLog.append(context, "SESSION started for ${durationMinutes}m")
                            logVersion++
                        },
                        enabled = blocked.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Start ${durationMinutes}-minute session") }
                    if (blocked.isEmpty()) {
                        Text(
                            text = "Pick at least one app below first.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            SpikeCard(
                title = "Spike 1 — detection latency",
                subtitle = "Delay between a window actually changing and this app hearing " +
                    "about it. Target: under 150 ms.",
            ) {
                val p50 = ForegroundAppLog.latencyPercentile(50)
                val p90 = ForegroundAppLog.latencyPercentile(90)
                Text(
                    text = if (p50 == null) {
                        "No detections yet. Leave this app, open a few others, come back."
                    } else {
                        "p50 ${p50}ms · p90 ${p90}ms · ${detections.size} samples"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                detections.take(12).forEach { detection ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = detection.packageName,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text(
                            text = buildString {
                                append("${detection.latencyMs}ms")
                                if (detection.wasBlocked) append("  BLOCKED")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (detection.wasBlocked) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                if (detections.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { ForegroundAppLog.clear() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Clear samples") }
                }
            }

            SpikeCard(
                title = "Blocked apps",
                subtitle = "Spike 2 fires when one of these comes to the foreground during a " +
                    "session.",
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Filter") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                val visible = apps
                    .filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }
                    .sortedByDescending { it.packageName in blocked }
                    .take(40)
                if (apps.isEmpty()) {
                    Text("Loading installed apps…", style = MaterialTheme.typography.bodySmall)
                }
                visible.forEach { app ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = app.packageName in blocked,
                            onCheckedChange = { checked ->
                                blocked = if (checked) {
                                    blocked + app.packageName
                                } else {
                                    blocked - app.packageName
                                }
                                store.blockedPackages = blocked
                            },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(app.label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = app.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (apps.size > visible.size) {
                    Text(
                        text = "…and ${apps.size - visible.size} more. Use the filter.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            SpikeCard(
                title = "Spike 4 — persistent event log",
                subtitle = "Written to disk, so it survives the reboot you are testing.",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { logVersion++ }) { Text("Refresh") }
                    OutlinedButton(
                        onClick = {
                            EventLog.clear(context)
                            logVersion++
                        },
                    ) { Text("Clear") }
                }
                Spacer(Modifier.height(8.dp))
                if (log.isEmpty()) {
                    Text("Empty.", style = MaterialTheme.typography.bodySmall)
                }
                log.take(30).forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            Text(
                text = "Last refreshed ${clockFormat.format(Date())} · uptime " +
                    "${SystemClock.elapsedRealtime() / 1000}s",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SpikeCard(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            content()
        }
    }
}

@Composable
private fun StatusLine(label: String, ok: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .background(
                    color = if (ok) Color(0xFF16A34A) else Color(0xFFDC2626),
                    shape = RoundedCornerShape(5.dp),
                ),
        )
        Text(
            text = "  $label",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (ok) "yes" else "no",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

private fun Context.openAccessibilitySettings() {
    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
}

private fun Context.openBatterySettings() {
    val power = getSystemService(PowerManager::class.java)
    if (power != null && power.isIgnoringBatteryOptimizations(packageName)) {
        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        return
    }
    @Suppress("BatteryLife")
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(Uri.parse("package:$packageName"))
    runCatching { startActivity(intent) }
        .onFailure { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
}
