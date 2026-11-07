package dev.detour.guard.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.detour.core.settings.SettingsOp
import dev.detour.core.setup.DeviceStepsCatalog
import dev.detour.guard.detourApp
import dev.detour.guard.filter.FilterStatusReader
import dev.detour.guard.setup.SetupProgress
import dev.detour.guard.setup.SetupState
import dev.detour.guard.setup.SetupStep
import dev.detour.guard.system.InstalledApp
import dev.detour.guard.system.Permissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class SetupData(val steps: List<SetupStep>, val browsers: List<InstalledApp>, val allowedBrowser: String)

/**
 * The setup checklist (onboarding). Every step shows live whether it's done, so a half-finished
 * setup always shows what's missing. Settings paths are specific to this phone's maker.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetupScreen(
    onBack: () -> Unit,
    onOpenApps: () -> Unit,
    onOpenBudget: () -> Unit,
    onOpenAlternatives: () -> Unit,
    onOpenSettingsGuard: () -> Unit,
    onOpenBackup: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.detourApp
    val scope = rememberCoroutineScope()
    val device = remember { DeviceStepsCatalog.forManufacturer(Build.MANUFACTURER, Build.MODEL) }
    val checked by app.setup.checked.collectAsStateWithLifecycle()
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }
    val data by produceState<SetupData?>(null, refresh, checked) {
        value = withContext(Dispatchers.IO) {
            app.catalog.invalidate()
            SetupData(SetupProgress.steps(context), app.catalog.browsers(), app.catalog.allowedBrowser)
        }
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val d = data

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Back") }
            Text("Set up Detour", style = MaterialTheme.typography.titleLarge)
        }
        if (d == null) {
            Text("Checking…", Modifier.padding(24.dp))
            return@Column
        }
        val done = d.steps.count { it.done }
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("$done of ${d.steps.size} steps done · instructions for your ${device.deviceName}", style = MaterialTheme.typography.bodyLarge)
            if (app.setup.isSetupWindowOpen()) {
                Note(
                    "Setup window: for the next ${app.setup.setupWindowMinutesLeft()} min every change applies right away and " +
                        "the Settings guard stays quiet. After that, loosening changes wait 24 hours.",
                )
            }

            StepCard(d.steps[0]) {
                Note("Usage access sees which app is open and when. It doesn't see what's on screen, what you type, or web pages.")
                Note("Display over other apps lets Detour show its pause screen. It can't read other apps.")
                Note("If a switch is greyed out (“Restricted setting”): ${device.restrictedSettings}")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!Permissions.hasUsageAccess(context)) OutlinedButton(onClick = { context.open(Permissions.usageAccessIntent(context), Permissions.usageAccessFallbackIntent()) }) { Text("Usage access") }
                    if (!Permissions.hasOverlay(context)) OutlinedButton(onClick = { context.open(Permissions.overlayIntent(context)) }) { Text("Display over apps") }
                    if (!Permissions.hasNotifications(context) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        OutlinedButton(onClick = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Notifications") }
                    }
                    if (!Permissions.isBatteryUnrestricted(context)) OutlinedButton(onClick = { context.open(Permissions.batteryIntent(context), Permissions.appInfoIntent(context)) }) { Text("Battery") }
                    OutlinedButton(onClick = { context.open(Permissions.appInfoIntent(context)) }) { Text("App info") }
                }
                Note("Battery, step by step: ${device.battery}")
            }

            StepCard(d.steps[1]) {
                val filterInstalled = FilterStatusReader.isInstalled(context)
                if (!filterInstalled) Note("Install Detour Filter from Android Studio (choose “filter” and press Run).")
                else OutlinedButton(onClick = { FilterStatusReader.launchIntent(context)?.let { context.open(it) } }) { Text("Open Detour Filter and turn it on") }
                CheckRow(SetupState.CHECK_ALWAYS_ON in checked, "Always-on VPN is on: ${device.alwaysOnVpn}") { app.setup.toggle(SetupState.CHECK_ALWAYS_ON) }
                CheckRow(SetupState.CHECK_CHROME_DNS in checked, "Chrome → ⋮ → Settings → Privacy and security → Use secure DNS: Off") { app.setup.toggle(SetupState.CHECK_CHROME_DNS) }
                Note("Private DNS must be Automatic or Off (Detour checks this for you): ${device.privateDns}")
            }

            StepCard(d.steps[2]) {
                Note("Pick Allowed, Distracting or Blocked for each app under “Needs a decision”.")
                OutlinedButton(onClick = onOpenApps) { Text("App categories") }
                Text("Your one allowed browser (all others are blocked; this one goes through the filter):", style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    d.browsers.forEach { b ->
                        FilterChip(
                            selected = b.packageName == d.allowedBrowser,
                            onClick = {
                                if (b.packageName != d.allowedBrowser) scope.launch {
                                    submitChange(context, SettingsOp.SetAllowedBrowser(b.packageName), "Allowed browser → ${b.label}")
                                    refresh++
                                }
                            },
                            label = { Text(b.label) },
                        )
                    }
                }
                val hours = settings.allowedHours
                Note("Allowed hours: ${hours?.let { "%d:%02d–%d:%02d".format(it.start.hour, it.start.minute, it.end.hour, it.end.minute) } ?: "any time"}")
                OutlinedButton(onClick = onOpenBudget) { Text("Budget & hours") }
            }

            StepCard(d.steps[3]) {
                Note("Make the list yours: things you'd genuinely rather do, tagged with when they help.")
                OutlinedButton(onClick = onOpenAlternatives) { Text("Edit alternatives") }
                CheckRow(SetupState.CHECK_ALTERNATIVES in checked, "I've looked through my alternatives") { app.setup.toggle(SetupState.CHECK_ALTERNATIVES) }
            }

            StepCard(d.steps[4]) {
                Note("Open each page once so Detour learns its name on this phone. At least do both App info pages.")
                OutlinedButton(onClick = onOpenSettingsGuard) { Text("Settings guard") }
            }

            StepCard(d.steps[5]) {
                Note("Make a new folder (e.g. Documents/Detour) and choose it.")
                OutlinedButton(onClick = onOpenBackup) { Text("Backup") }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Outside the app", style = MaterialTheme.typography.titleMedium)
                    Note("Things only you can do. Tick them off as you go; they don't affect anything else.")
                    DeviceStepsCatalog.OUTSIDE_THE_APP.forEach { (key, text) ->
                        CheckRow("outside_$key" in checked, if (key == "notifs") "$text: ${device.notificationsForApp}" else text) {
                            app.setup.toggle("outside_$key")
                        }
                    }
                }
            }

            Button(
                onClick = {
                    app.setup.markCompleted()
                    onBack()
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(if (done == d.steps.size) "Finish setup" else "Finish later (${d.steps.size - done} left)") }
        }
    }
}

@Composable
private fun StepCard(step: SetupStep, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${step.number}. ${step.title}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    if (step.done) "Done" else "To do",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (step.done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            content()
        }
    }
}

@Composable
private fun CheckRow(checked: Boolean, text: String, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Note(text: String) =
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

private fun Context.open(intent: Intent, fallback: Intent? = null) {
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        fallback?.let { runCatching { startActivity(it) } }
    }
}
