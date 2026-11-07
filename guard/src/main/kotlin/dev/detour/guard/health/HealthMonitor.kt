package dev.detour.guard.health

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.detour.core.health.FilterReport
import dev.detour.core.health.HealthCheck
import dev.detour.core.health.HealthInputs
import dev.detour.core.health.HealthIssue
import dev.detour.guard.MainActivity
import dev.detour.guard.R
import dev.detour.guard.data.LapseEventEntity
import dev.detour.guard.detect.DetectorService
import dev.detour.guard.detourApp
import dev.detour.guard.filter.FilterStatusReader
import dev.detour.guard.system.Permissions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class HealthState(val issues: List<HealthIssue>, val filter: FilterReport?, val checkedAtMs: Long)

/**
 * Continuously checks that every part of protection is on. If anything is off:
 * - a persistent notification ("Detour protection is partly off: ...") that comes back if swiped
 * - a banner in the app (via [state])
 * - a neutral lapse event, once per issue each time it appears
 */
class HealthMonitor(private val context: Context) {
    private val prefs = context.getSharedPreferences("health", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow<HealthState?>(null)
    val state: StateFlow<HealthState?> = _state.asStateFlow()

    /** Runs all checks. Call from the detector (every ~30 s) and when the app comes to the front. */
    suspend fun check(detectorRunning: Boolean = DetectorService.isRunning): HealthState {
        val now = System.currentTimeMillis()
        val installed = FilterStatusReader.isInstalled(context)
        val report = if (installed) FilterStatusReader.read(context) else null
        val issues = HealthCheck.issues(
            HealthInputs(
                usageAccess = Permissions.hasUsageAccess(context),
                overlay = Permissions.hasOverlay(context),
                batteryUnrestricted = Permissions.isBatteryUnrestricted(context),
                notifications = Permissions.hasNotifications(context),
                detectorRunning = detectorRunning,
                filterInstalled = installed,
                filter = report,
                nowMs = now,
            ),
        )

        val previous = prefs.getStringSet(K_ISSUES, emptySet())!!
            .mapNotNull { runCatching { HealthIssue.valueOf(it) }.getOrNull() }.toSet()
        for (issue in HealthCheck.newlyAppeared(previous, issues)) {
            context.detourApp.database.lapses().insert(
                LapseEventEntity(atMs = now, kind = "protection_partly_off", detail = issue.message + "."),
            )
        }
        prefs.edit().putStringSet(K_ISSUES, issues.map { it.name }.toSet()).apply()

        updateNotification(issues)
        return HealthState(issues, report, now).also { _state.value = it }
    }

    fun updateNotification(issues: List<HealthIssue> = _state.value?.issues.orEmpty()) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (issues.isEmpty()) {
            nm.cancel(NOTIFICATION_ID)
            return
        }
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Protection status", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Shown only while part of Detour's protection is off."
                setSound(null, null)
                enableVibration(false)
            },
        )
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        // Swiping it away just brings it back while the problem remains.
        val repost = PendingIntent.getBroadcast(
            context, 0, Intent(context, HealthNotificationReceiver::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val summary = HealthCheck.summary(issues)
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_detour)
            .setContentTitle("Detour protection is partly off")
            .setContentText(summary)
            .setStyle(Notification.BigTextStyle().bigText(summary))
            .setContentIntent(open)
            .setDeleteIntent(repost)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, notification) } // fails quietly if notifications are off
    }

    companion object {
        private const val K_ISSUES = "issues"
        private const val CHANNEL_ID = "health"
        private const val NOTIFICATION_ID = 2
    }
}

/** Re-posts the protection notification after it was swiped away, if the problem is still there. */
class HealthNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.detourApp.health.updateNotification()
    }
}
