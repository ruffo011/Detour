package dev.detour.guard.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.provider.Settings
import androidx.compose.runtime.rememberCoroutineScope
import dev.detour.core.budget.BudgetSettings
import dev.detour.core.health.HealthIssue
import dev.detour.core.review.WeeklyReview
import dev.detour.core.settings.SettingsOp
import dev.detour.guard.newapps.NewApp
import dev.detour.guard.newapps.NewAppNotifier
import dev.detour.guard.setup.SetupProgress
import dev.detour.guard.setup.SetupStep
import androidx.compose.runtime.mutableStateOf
import dev.detour.guard.data.TodayUsage
import dev.detour.guard.detect.DetectorService
import dev.detour.guard.detourApp
import dev.detour.guard.filter.FilterStatusReader
import dev.detour.guard.health.HealthState
import dev.detour.guard.system.Permissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class SetupStatus(
    val usage: Boolean,
    val overlay: Boolean,
    val notifications: Boolean,
    val battery: Boolean,
)

private fun readStatus(context: Context) = SetupStatus(
    usage = Permissions.hasUsageAccess(context),
    overlay = Permissions.hasOverlay(context),
    notifications = Permissions.hasNotifications(context),
    battery = Permissions.isBatteryUnrestricted(context),
)

@Composable
fun HomeScreen(
    hasNetworkPermission: Boolean,
    onOpenApps: () -> Unit,
    onOpenBudget: () -> Unit,
    onOpenAlternatives: () -> Unit,
    onOpenBlocklist: () -> Unit,
    onOpenPending: () -> Unit,
    onOpenSettingsGuard: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenSetup: () -> Unit,
    onOpenReview: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.detourApp
    val today by app.usage.today.collectAsStateWithLifecycle()
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    val health by app.health.state.collectAsStateWithLifecycle()
    val pending by app.changes.pending.collectAsStateWithLifecycle(emptyList())
    val backup by app.backup.status.collectAsStateWithLifecycle()
    val setupCompleted by app.setup.completed.collectAsStateWithLifecycle()
    val reviewViewed by app.setup.reviewViewedMs.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var setupSteps by remember { mutableStateOf<List<SetupStep>>(emptyList()) }
    var newApps by remember { mutableStateOf<List<NewApp>>(emptyList()) }
    var refreshTick by remember { mutableIntStateOf(0) }
    val status = remember(refreshTick) { readStatus(context) }

    // Re-check every time you come back from a Settings page, and start watching once allowed.
    LifecycleResumeEffect(Unit) {
        refreshTick++
        DetectorService.startIfPermitted(context)
        val job = scope.launch {
            app.changes.applyDue()
            setupSteps = withContext(Dispatchers.IO) { SetupProgress.steps(context) }
            newApps = withContext(Dispatchers.IO) { NewAppNotifier.newApps(context) }
            delay(800) // give a just-started detector a moment before checking it
            withContext(Dispatchers.IO) { app.health.check() }
        }
        onPauseOrDispose { job.cancel() }
    }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshTick++
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Detour", style = MaterialTheme.typography.headlineMedium)

        val watching = status.usage && status.overlay
        if (!watching) {
            Text("Detour needs two permissions before it can work.", style = MaterialTheme.typography.bodyLarge)
        } else {
            TodayCard(today, settings, System.currentTimeMillis())
        }

        health?.let { h ->
            // Permission problems have their own cards below; the banner covers the rest.
            val shown = h.issues.filter { it !in PERMISSION_ISSUES }
            if (shown.isNotEmpty()) HealthBanner(shown) { issue -> fix(context, issue) }
            FilterLine(h)
        }

        if (newApps.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    newApps.sortedBy { it.decideByMs }.forEach {
                        Text(
                            "New app: ${it.app.label}. Allowed for now, Distracting ${timeUntil(it.decideByMs)} unless you choose.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    OutlinedButton(onClick = onOpenApps) { Text("Choose categories") }
                }
            }
        }

        val stepsLeft = setupSteps.count { !it.done }
        if (!setupCompleted || stepsLeft > 0) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (stepsLeft > 0) "Setup: $stepsLeft step${if (stepsLeft == 1) "" else "s"} left " +
                            "(${setupSteps.filter { !it.done }.joinToString(", ") { it.title.lowercase() }})."
                        else "Setup looks complete.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = onOpenSetup) { Text("Continue setup") }
                }
            }
        }

        if (WeeklyReview.isDue(app.setup.firstRunMs, reviewViewed, System.currentTimeMillis())) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Your weekly review is ready: a calm look at the past seven days.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = onOpenReview) { Text("Open weekly review") }
                }
            }
        }

        if (backup.foundBackupMs != null || (backup.folderUri == null && setupCompleted)) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (backup.foundBackupMs != null) "A backup was found. Restore it or start fresh."
                        else "Choose a backup folder, so a reinstall can restore everything in about a minute.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = onOpenBackup) { Text(if (backup.foundBackupMs != null) "Review backup" else "Set up backup") }
                }
            }
        }
        if (pending.isNotEmpty()) {
            TextButton(onClick = onOpenPending) {
                Text("${pending.size} change${if (pending.size == 1) "" else "s"} waiting 24 hours · next ${timeUntil(pending.first().applyAtMs)}")
            }
        }

        val baseline = remember(settings) { app.settings.baseline() }
        if (baseline != null && !settings.budgetConfirmed && settings.dailyBudgetMinutes != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "During the observe week you used Distracting apps for about ${baseline.averageMinutes} min " +
                            "a day and opened them about ${baseline.averageOpens} times. Your daily time now " +
                            "starts at ${settings.dailyBudgetMinutes} min.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { scope.launch { submitChange(context, SettingsOp.SetBudgetConfirmed(true), "Budget confirmed") } }) {
                            Text("Keep ${settings.dailyBudgetMinutes} min")
                        }
                        OutlinedButton(onClick = onOpenBudget) { Text("Adjust") }
                    }
                }
            }
        }

        SetupRow(
            title = "Usage access",
            explanation = "Sees which app is open and when. Does not see what's on the screen, what you type, or any web pages.",
            done = status.usage,
            onFix = {
                context.startSafely(Permissions.usageAccessIntent(context), Permissions.usageAccessFallbackIntent())
            },
        )
        SetupRow(
            title = "Display over other apps",
            explanation = "Lets Detour show its pause screen on top of an app. Does not let it read other apps.",
            done = status.overlay,
            onFix = { context.startSafely(Permissions.overlayIntent(context)) },
        )
        if (!status.usage || !status.overlay) RestrictedSettingsHint(onOpenAppInfo = {
            context.startSafely(Permissions.appInfoIntent(context))
        })
        SetupRow(
            title = "Notifications",
            explanation = "Only used to tell you if protection is partly off. Never reminders or nudges.",
            done = status.notifications,
            onFix = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
        )
        SetupRow(
            title = "Battery: Unrestricted",
            explanation = "Stops Android from putting Detour to sleep, so it keeps watching. It uses very little battery.",
            done = status.battery,
            onFix = { context.startSafely(Permissions.batteryIntent(context), Permissions.appInfoIntent(context)) },
        )

        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onOpenReview, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Weekly review")
        }
        OutlinedButton(onClick = onOpenBudget, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Budget & hours")
        }
        OutlinedButton(onClick = onOpenAlternatives, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Alternatives")
        }
        OutlinedButton(onClick = onOpenApps, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("App categories")
        }
        OutlinedButton(onClick = onOpenBlocklist, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Browser blocklist")
        }
        OutlinedButton(onClick = onOpenSettingsGuard, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Settings guard")
        }
        OutlinedButton(onClick = onOpenPending, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(if (pending.isEmpty()) "Pending changes" else "Pending changes (${pending.size})")
        }
        OutlinedButton(onClick = onOpenBackup, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Backup")
        }
        OutlinedButton(onClick = onOpenSetup, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Setup checklist")
        }

        Text(
            if (hasNetworkPermission) "Warning: this build can access the network. It should not."
            else "This app has no internet access, so nothing you enter here can leave the phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = if (hasNetworkPermission) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val PERMISSION_ISSUES = setOf(
    HealthIssue.USAGE_ACCESS_OFF, HealthIssue.OVERLAY_OFF, HealthIssue.BATTERY_RESTRICTED, HealthIssue.NOTIFICATIONS_OFF,
)

@Composable
private fun HealthBanner(issues: List<HealthIssue>, onFix: (HealthIssue) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Protection is partly off", style = MaterialTheme.typography.titleMedium)
            issues.forEach { issue ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(issue.message + ".", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    if (issue != HealthIssue.FILTER_NOT_INSTALLED && issue != HealthIssue.FILTER_UNREACHABLE) {
                        TextButton(onClick = { onFix(issue) }) { Text("Fix") }
                    }
                }
            }
            if (HealthIssue.FILTER_NOT_INSTALLED in issues || HealthIssue.FILTER_UNREACHABLE in issues) {
                Text(
                    "Install Detour Filter from Android Studio (choose “filter” and press Run). Both apps must " +
                        "be built on the same computer so they share a signing key.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FilterLine(h: HealthState) {
    val f = h.filter ?: return
    val line = if (f.running) {
        "Chrome filter is on · ${f.blockedToday} blocked lookup${if (f.blockedToday == 1) "" else "s"} today."
    } else {
        "Chrome filter is off."
    }
    Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun fix(context: Context, issue: HealthIssue) {
    when (issue) {
        HealthIssue.DETECTOR_STOPPED -> DetectorService.startIfPermitted(context)
        HealthIssue.FILTER_OFF, HealthIssue.FILTER_UNRESPONSIVE ->
            FilterStatusReader.launchIntent(context)?.let { context.startSafely(it) }
        HealthIssue.PRIVATE_DNS_STRICT -> context.startSafely(Intent(Settings.ACTION_WIRELESS_SETTINGS))
        else -> context.startSafely(Permissions.appInfoIntent(context))
    }
}

@Composable
private fun TodayCard(today: TodayUsage?, settings: BudgetSettings, nowMs: Long) {
    val minutes = today?.usedMinutes ?: 0
    val opens = today?.opens ?: 0
    val opensText = if (opens == 1) "1 open" else "$opens opens"
    val line = when {
        settings.isObserving(nowMs) -> {
            val until = settings.observeUntilMs!!
            val daysLeft = ((until - nowMs + 86_399_999) / 86_400_000).coerceAtLeast(1)
            "Observing a normal week ($daysLeft day${if (daysLeft == 1L) "" else "s"} left). " +
                "Today: $minutes min in Distracting apps · $opensText."
        }
        settings.dailyBudgetMinutes != null -> "Today: $minutes of ${settings.dailyBudgetMinutes} min used · $opensText."
        else -> "Today: $minutes min in Distracting apps · $opensText."
    }
    Text("Detour is on.", style = MaterialTheme.typography.bodyLarge)
    Text(line, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SetupRow(title: String, explanation: String, done: Boolean, onFix: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (done) {
                    Text("On", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                } else {
                    OutlinedButton(onClick = onFix) { Text("Turn on") }
                }
            }
            Text(explanation, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RestrictedSettingsHint(onOpenAppInfo: () -> Unit) {
    Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Switch greyed out, or a \"Restricted setting\" message? Android blocks these for apps " +
                "installed outside the Play Store until you allow it: open App info, tap ⋮ (top right), " +
                "then \"Allow restricted settings\". Then come back and try again.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onOpenAppInfo) { Text("Open App info") }
    }
}

private fun Context.startSafely(intent: Intent, fallback: Intent? = null) {
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        fallback?.let { runCatching { startActivity(it) } }
    }
}
