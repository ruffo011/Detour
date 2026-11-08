package dev.detour.guard.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.detour.core.settings.Calibration
import dev.detour.core.settings.GuardTarget
import dev.detour.core.settings.SettingsOp
import dev.detour.guard.data.RestoreSummary
import dev.detour.guard.detourApp
import dev.detour.guard.guardrail.GuardScreen
import dev.detour.guard.guardrail.SettingsGuardStore
import dev.detour.ipc.DetourIpc
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
private fun ScreenScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Back") }
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) { content() }
    }
}

@Composable
private fun Note(text: String) =
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

// ---- Pending changes ----------------------------------------------------------------------

@Composable
fun PendingChangesScreen(onBack: () -> Unit) {
    val app = LocalContext.current.detourApp
    val pending by app.changes.pending.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(30_000)
        }
    }

    ScreenScaffold("Pending changes", onBack) {
        Note(
            "Changes that loosen a setting wait 24 hours, so the decision is made by the you who planned it. " +
                "Cancel any of them if you've changed your mind.",
        )
        if (pending.isEmpty()) Text("Nothing is waiting.", style = MaterialTheme.typography.bodyLarge)
        pending.forEach { p ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.description, style = MaterialTheme.typography.bodyLarge)
                        Text("Applies ${timeUntil(p.applyAtMs, now)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { scope.launch { app.changes.cancel(p.id) } }) { Text("Cancel") }
                }
            }
        }
    }
}

// ---- Settings guard -----------------------------------------------------------------------

@Composable
fun SettingsGuardScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.detourApp
    val enabled by app.settingsGuard.enabled.collectAsStateWithLifecycle()
    val targets by app.settingsGuard.targets.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var lastResult by remember { mutableStateOf<String?>(null) }

    // When you come back from a page opened for calibration, record what came to the front.
    LifecycleResumeEffect(Unit) {
        val (screen, since) = app.recentScreens.calibrating ?: (null to 0L)
        if (screen != null) {
            app.recentScreens.calibrating = null
            val picked = Calibration.pick(app.recentScreens.snapshot(), since, screen.name)
            if (picked != null) {
                val target = GuardTarget(screen.name, picked.packageName, picked.className)
                scope.launch { submitChange(context, SettingsOp.AddGuardTarget(target), "Guard ${screen.label}") }
                lastResult = "Recorded ${screen.label}: ${picked.className.substringAfterLast('.')}"
            } else {
                lastResult = if (screen == GuardScreen.CHROME_SETTINGS) {
                    "Nothing recorded: in Chrome, tap ⋮ → Settings and let it open before coming back. Chrome's normal screens are never guarded."
                } else {
                    "Couldn't see a Settings page open. Try again, and make sure the page fully opens."
                }
            }
        }
        onPauseOrDispose { }
    }

    ScreenScaffold("Settings guard", onBack) {
        Note(
            "When you open a page that can turn Detour off, you get a 60-second pause and are asked why. " +
                "Then you can go ahead. It's friction, not a lock. It stays quiet for the first hour after setup.",
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Settings guard", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = {
                scope.launch { submitChange(context, SettingsOp.SetSettingsGuard(it), "Settings guard ${if (it) "on" else "off"}") }
            })
        }
        Text("Calibrate", style = MaterialTheme.typography.titleMedium)
        Note(
            "Page names differ between phones. Tap Open for each page, let it load, then come back, and Detour " +
                "remembers it. The first time for each page, it won't pause you.",
        )
        lastResult?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
        GuardScreen.entries.forEach { screen ->
            val forScreen = targets.filter { it.key == screen.name }
            val calibrated = forScreen.any { it !in SettingsGuardStore.DEFAULT_TARGETS }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(screen.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                when {
                                    calibrated -> "Calibrated on this phone"
                                    forScreen.isNotEmpty() -> "Using a built-in default"
                                    else -> "Not guarded yet"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(onClick = {
                            app.recentScreens.calibrating = screen to System.currentTimeMillis()
                            openForCalibration(context, screen)
                        }) { Text("Open") }
                    }
                    screen.hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

private fun openForCalibration(context: android.content.Context, screen: GuardScreen) {
    val pkg = { p: String -> Uri.parse("package:$p") }
    val intent = when (screen) {
        GuardScreen.GUARD_APP_INFO -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg(DetourIpc.GUARD_PACKAGE))
        GuardScreen.FILTER_APP_INFO -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg(DetourIpc.FILTER_PACKAGE))
        GuardScreen.USAGE_ACCESS -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        GuardScreen.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        GuardScreen.VPN -> Intent(Settings.ACTION_VPN_SETTINGS)
        GuardScreen.PRIVATE_DNS -> Intent(Settings.ACTION_WIRELESS_SETTINGS)
        GuardScreen.BATTERY -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        GuardScreen.CHROME_SETTINGS -> context.packageManager.getLaunchIntentForPackage("com.android.chrome") ?: return
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
    }
}

// ---- Backup -------------------------------------------------------------------------------

@Composable
fun BackupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.detourApp
    val status by app.backup.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var restored by remember { mutableStateOf<RestoreSummary?>(null) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch { app.backup.setFolder(uri) }
    }

    ScreenScaffold("Backup", onBack) {
        Note(
            "Detour saves your settings, alternatives and what has helped to a file in a folder you choose, " +
                "every time something changes. If you reinstall, pick the same folder to restore in about a minute. " +
                "Pick a folder on this phone (e.g. Documents). Detour itself has no internet; a cloud folder's own app may sync it.",
        )
        val found = status.foundBackupMs
        if (found != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "This folder has a backup from ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(found))}. " +
                            "Restore it? Anything stricter than now applies right away; anything looser waits 24 hours.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { scope.launch { restored = app.backup.restore() } }) { Text("Restore") }
                        OutlinedButton(onClick = { scope.launch { app.backup.discardFoundBackup() } }) { Text("Start fresh") }
                    }
                }
            }
        }
        restored?.let {
            Text(
                "Restored: ${it.appliedNow} setting(s) applied, ${it.queued} waiting 24 hours, " +
                    "${it.pendingRestored} pending change(s) kept, ${it.alternatives} alternative(s).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            when {
                status.folderUri == null -> "No backup folder chosen yet."
                status.lastBackupMs > 0 -> "Last backup: ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(status.lastBackupMs))}"
                else -> "Backup folder chosen."
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        status.error?.let { Text("Last backup failed: $it", color = MaterialTheme.colorScheme.error) }
        Button(onClick = { pickFolder.launch(null) }, modifier = Modifier.fillMaxWidth()) {
            Text(if (status.folderUri == null) "Choose backup folder" else "Choose a different folder")
        }
        if (status.folderUri != null && found == null) {
            OutlinedButton(onClick = { scope.launch { app.backup.exportNow() } }, modifier = Modifier.fillMaxWidth()) {
                Text("Back up now")
            }
        }
    }
}
