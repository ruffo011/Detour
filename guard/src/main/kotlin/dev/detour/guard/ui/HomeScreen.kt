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
import dev.detour.guard.detect.DetectorService
import dev.detour.guard.system.Permissions

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
fun HomeScreen(hasNetworkPermission: Boolean, onOpenApps: () -> Unit) {
    val context = LocalContext.current
    var refreshTick by remember { mutableIntStateOf(0) }
    val status = remember(refreshTick) { readStatus(context) }

    // Re-check every time you come back from a Settings page, and start watching once allowed.
    LifecycleResumeEffect(Unit) {
        refreshTick++
        DetectorService.startIfPermitted(context)
        onPauseOrDispose { }
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
        Text(
            if (watching) "Detour is on. Blocked apps will be interrupted."
            else "Detour needs two permissions before it can work.",
            style = MaterialTheme.typography.bodyLarge,
        )

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
        Button(onClick = onOpenApps, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Choose app categories")
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
