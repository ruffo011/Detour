package dev.detour.guard.newapps

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.detour.core.AppCategory
import dev.detour.core.CategoryReason
import dev.detour.core.settings.SettingsOp
import dev.detour.guard.MainActivity
import dev.detour.guard.R
import dev.detour.guard.data.label
import dev.detour.guard.detourApp
import dev.detour.guard.system.InstalledApp
import dev.detour.guard.ui.submitChange
import kotlinx.coroutines.launch

/** A newly installed app still in its 24-hour Allowed period, waiting for you to choose. */
data class NewApp(val app: InstalledApp, val decideByMs: Long)

/**
 * Newly installed apps count as Allowed for 24 hours. This posts one notification per new app,
 * with Allowed / Distracting / Blocked buttons, and removes it once you've chosen or the 24 hours
 * are up (then the app counts as Distracting until you choose).
 */
object NewAppNotifier {
    private const val CHANNEL_ID = "new_apps"
    private const val PREFS = "new_apps"
    private const val K_NOTIFIED = "notified"

    const val ACTION_CHOOSE = "dev.detour.guard.action.CHOOSE_CATEGORY"
    const val EXTRA_PACKAGE = "package"
    const val EXTRA_LABEL = "label"
    const val EXTRA_CATEGORY = "category"
    const val EXTRA_OPEN_APPS = "open_apps"

    fun newApps(context: Context): List<NewApp> {
        val app = context.detourApp
        val choices = app.classifications.choices.value ?: return emptyList()
        return app.catalog.launchableApps().mapNotNull { a ->
            val r = app.catalog.resolve(a.packageName, choices)
            if (r.reason == CategoryReason.NEW_APP && r.decideByMs != null) NewApp(a, r.decideByMs!!) else null
        }
    }

    /** Posts notifications for new apps not yet announced; clears ones that are decided or expired. */
    fun check(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pending = newApps(context)
        val pendingPkgs = pending.map { it.app.packageName }.toSet()
        val notified = prefs.getStringSet(K_NOTIFIED, emptySet())!!.toMutableSet()

        // Chosen or past 24 hours: take the notification down.
        for (pkg in notified.filter { it !in pendingPkgs }) {
            cancel(context, pkg)
            notified.remove(pkg)
        }
        for (n in pending.filter { it.app.packageName !in notified }) {
            post(context, n)
            notified += n.app.packageName
        }
        prefs.edit().putStringSet(K_NOTIFIED, notified).apply()
    }

    fun cancel(context: Context, packageName: String) {
        context.getSystemService(NotificationManager::class.java).cancel(idFor(packageName))
    }

    private fun idFor(pkg: String) = 1_000 + (pkg.hashCode() and 0xFFFF)

    private fun post(context: Context, n: NewApp) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "New apps", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Asks you to choose a category for newly installed apps."
            },
        )
        val pkg = n.app.packageName
        val open = PendingIntent.getActivity(
            context, idFor(pkg),
            Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_APPS, true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fun action(cat: AppCategory): Notification.Action {
            val intent = Intent(context, NewAppChoiceReceiver::class.java)
                .setAction(ACTION_CHOOSE)
                .putExtra(EXTRA_PACKAGE, pkg)
                .putExtra(EXTRA_LABEL, n.app.label)
                .putExtra(EXTRA_CATEGORY, cat.name)
            val pi = PendingIntent.getBroadcast(
                context, idFor(pkg) * 4 + cat.ordinal, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return Notification.Action.Builder(null, cat.label(), pi).build()
        }
        val text = "Allowed for now. Choose a category, or it counts as Distracting 24 hours after it was installed."
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_detour)
            .setContentTitle("New app: ${n.app.label}")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter((n.decideByMs - System.currentTimeMillis()).coerceAtLeast(1))
            .addAction(action(AppCategory.ALLOWED))
            .addAction(action(AppCategory.DISTRACTING))
            .addAction(action(AppCategory.BLOCKED))
            .build()
        runCatching { nm.notify(idFor(pkg), notification) }
    }
}

/** Handles the Allowed / Distracting / Blocked buttons on a new-app notification. Not exported. */
class NewAppChoiceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != NewAppNotifier.ACTION_CHOOSE) return
        val pkg = intent.getStringExtra(NewAppNotifier.EXTRA_PACKAGE) ?: return
        val label = intent.getStringExtra(NewAppNotifier.EXTRA_LABEL) ?: pkg
        val category = runCatching { AppCategory.valueOf(intent.getStringExtra(NewAppNotifier.EXTRA_CATEGORY)!!) }.getOrNull() ?: return
        val pending = goAsync()
        val app = context.detourApp
        app.appScope.launch {
            try {
                // Same path as the Apps screen, so the tighten/loosen rule still applies.
                submitChange(context.applicationContext, SettingsOp.SetCategory(pkg, category), "$label → ${category.label()}")
                NewAppNotifier.cancel(context, pkg)
            } finally {
                pending.finish()
            }
        }
    }
}
