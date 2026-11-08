package dev.detour.guard.detect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import dev.detour.core.AppCategory
import dev.detour.core.ForegroundTracker
import dev.detour.core.InterventionPolicy
import dev.detour.core.UsageEvent
import dev.detour.core.alternatives.CheckInPicker
import dev.detour.core.budget.DistractingGate
import dev.detour.core.budget.GateDecision
import dev.detour.core.budget.GateInput
import dev.detour.guard.MainActivity
import dev.detour.guard.R
import dev.detour.guard.data.LapseEventEntity
import dev.detour.guard.data.ObserveWeek
import dev.detour.guard.detourApp
import dev.detour.guard.intervene.CheckInActivity
import dev.detour.guard.intervene.InterventionActivity
import dev.detour.guard.intervene.InterventionInfo
import dev.detour.guard.intervene.InterventionMode
import dev.detour.guard.newapps.NewAppNotifier
import dev.detour.guard.system.Permissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.format.DateTimeFormatter

/**
 * Watches which app is in the foreground, using Usage Access (UsageStatsManager events).
 *
 * - Polls every [POLL_MS] while the screen is on; sleeps while it is off.
 * - Blocked app in front -> intervention.
 * - Distracting app in front -> records time and asks [DistractingGate]: pause, block, or allow
 *   (with the session check-in and 5-minutes-left overlays).
 * - On unlock, offers the "how was it?" check-in for an alternative you chose earlier.
 * - Writes a heartbeat so a later start can tell the system killed it, and logs that neutrally.
 */
class DetectorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val tracker = ForegroundTracker()
    private val policy = InterventionPolicy()
    private val screenOn = MutableStateFlow(true)
    private lateinit var keepAlive: OverlayKeepAlive
    private lateinit var overlays: SessionOverlays
    private val prefs by lazy { getSharedPreferences("detector", MODE_PRIVATE) }

    /** Events at or before this time have been processed. */
    private var lastEventTimeMs = 0L
    private var lastHeartbeatMs = 0L
    private var lastKeepAliveCheckMs = 0L
    private var lastHealthCheckMs = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn.value = false
                    overlays.hideAll()
                    scope.launch { detourApp.usage.closeOpen(System.currentTimeMillis()) }
                }
                Intent.ACTION_SCREEN_ON -> screenOn.value = true
                Intent.ACTION_USER_PRESENT -> scope.launch { offerCheckIn() }
            }
        }
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            detourApp.catalog.invalidate()
            // A newly installed app: ask for its category right away.
            scope.launch(Dispatchers.IO) { runCatching { NewAppNotifier.check(this@DetectorService) } }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startInForeground()
        keepAlive = OverlayKeepAlive(this)
        keepAlive.attach()
        overlays = SessionOverlays(this, detourApp.session, goHome = ::goHome)

        screenOn.value = getSystemService(PowerManager::class.java).isInteractive
        // System broadcasts still reach NOT_EXPORTED receivers; other apps can't send to them.
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(
            this,
            packageReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        val now = System.currentTimeMillis()
        recordStartAndDetectGap(now)
        lastEventTimeMs = now - INITIAL_LOOKBACK_MS
        scope.launch { runLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        isRunning = false
        // A normal stop (not a kill) — not a lapse.
        prefs.edit().putBoolean(KEY_CLEAN_STOP, true).putLong(KEY_HEARTBEAT, System.currentTimeMillis()).apply()
        overlays.hideAll()
        keepAlive.detach()
        unregisterReceiver(screenReceiver)
        unregisterReceiver(packageReceiver)
        runCatching { runBlocking { detourApp.usage.closeOpen(System.currentTimeMillis()) } }
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runLoop() {
        // Don't classify anything until the user's choices have been read from the database.
        detourApp.classifications.choices.first { it != null }
        while (scope.isActive) {
            if (screenOn.value) {
                runCatching { tick() }.onFailure { Log.w(TAG, "tick failed", it) }
                delay(POLL_MS)
            } else {
                heartbeat(System.currentTimeMillis())
                withTimeoutOrNull(SCREEN_OFF_HEARTBEAT_MS) { screenOn.first { it } }
            }
        }
    }

    private suspend fun tick() {
        val app = detourApp
        val now = System.currentTimeMillis()
        val events = withContext(Dispatchers.Default) { readEventsSince(lastEventTimeMs, now) }
        events.lastOrNull()?.let { lastEventTimeMs = it.timeMs }
        tracker.onEvents(events)
        events.filter { it.type == UsageEvent.Type.RESUMED }.forEach { app.recentScreens.add(it.packageName, it.className, it.timeMs) }

        val fg = tracker.current
        val choices = app.classifications.choices.value ?: return
        val category = fg?.let { app.catalog.resolve(it.packageName, choices).category }
        val distractingPkg = fg?.packageName?.takeIf { category == AppCategory.DISTRACTING }

        val today = app.usage.update(distractingPkg, now)
        app.session.touch(fg?.packageName, now)
        maybeFinishObserving(now)

        val settings = app.settings.current
        var info: InterventionInfo? = fg?.let { settingsGuardInfo(it.packageName, it.className, now) }
        var allowedSession = false
        if (info != null) {
            // Settings guard pause takes precedence.
        } else if (fg != null && category == AppCategory.BLOCKED) {
            info = InterventionInfo(fg.packageName, InterventionMode.BLOCKED)
        } else if (distractingPkg != null) {
            val decision = DistractingGate.decide(
                settings,
                GateInput(
                    nowMs = now,
                    localTime = app.settings.clock().localTimeAt(now),
                    usedMs = today.usedMs,
                    opensBefore = today.opensBefore,
                    sessionPermitted = app.session.isPermitted(distractingPkg, now),
                ),
            )
            val mode = when (decision) {
                GateDecision.ALLOW -> null
                GateDecision.PAUSE -> InterventionMode.PAUSE
                GateDecision.OUT_OF_BUDGET -> InterventionMode.OUT_OF_BUDGET
                GateDecision.OUTSIDE_HOURS -> InterventionMode.OUTSIDE_HOURS
                GateDecision.OPENS_CAP -> InterventionMode.OPENS_CAP
            }
            if (mode != null) {
                if (mode != InterventionMode.PAUSE) app.session.revoke()
                info = InterventionInfo(
                    packageName = distractingPkg,
                    mode = mode,
                    opensToday = today.opens,
                    usedMinutes = today.usedMinutes,
                    budgetMinutes = settings.dailyBudgetMinutes,
                    waitSeconds = settings.waitFor(today.opensBefore),
                    allowedHoursText = settings.allowedHours?.let { "${it.start.format(HOURS)}–${it.end.format(HOURS)}" },
                )
            } else {
                allowedSession = !settings.isObserving(now)
            }
        }

        if (allowedSession) overlays.onAllowedTick(settings, today, now) else overlays.onOtherTick(now)

        if (policy.shouldIntervene(fg?.packageName, info != null, now)) {
            overlays.hideAll()
            InterventionActivity.launch(this, info!!)
        }

        if (now - lastKeepAliveCheckMs > KEEPALIVE_CHECK_MS) {
            lastKeepAliveCheckMs = now
            keepAlive.attach() // no-op if attached; picks up an overlay permission granted later
        }
        if (now - lastHealthCheckMs > HEALTH_CHECK_MS) {
            lastHealthCheckMs = now
            withContext(Dispatchers.IO) {
                app.health.check(detectorRunning = true)
                runCatching { NewAppNotifier.check(this@DetectorService) }
            }
            app.changes.applyDue(now) // loosening changes whose 24 hours are up
        }
        heartbeat(now)
    }

    private suspend fun maybeFinishObserving(now: Long) = ObserveWeek.finishIfDue(detourApp, now)

    /** The Settings guard's 60-second pause, if this screen is one it watches. */
    private fun settingsGuardInfo(packageName: String, className: String?, now: Long): InterventionInfo? {
        val app = detourApp
        val calibrating = app.recentScreens.calibrating
            ?.takeIf { (screen, since) -> now - since < CALIBRATION_WINDOW_MS && !app.settingsGuard.isCalibrated(screen) }
        val pause = app.settingsGuardPolicy.shouldPause(
            packageName = packageName,
            className = className,
            enabled = app.settingsGuard.enabled.value,
            targets = app.settingsGuard.targets.value,
            nowMs = now,
            suppressedKeys = setOfNotNull(calibrating?.first?.name),
            inSetupGrace = app.setup.isSetupWindowOpen(now),
        )
        return if (pause) InterventionInfo(packageName, InterventionMode.SETTINGS_GUARD, waitSeconds = SETTINGS_PAUSE_SECONDS) else null
    }

    /** On unlock: if you chose an alternative 10+ minutes ago, ask how it went. */
    private suspend fun offerCheckIn() {
        val pick = CheckInPicker.pick(detourApp.alternatives.recentChoices(), System.currentTimeMillis()) ?: return
        CheckInActivity.launch(this, pick.id)
    }

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Reads resumed/paused events newer than [afterMs]. Queries a little earlier to catch late writes. */
    private fun readEventsSince(afterMs: Long, nowMs: Long): List<UsageEvent> {
        if (!Permissions.hasUsageAccess(this)) return emptyList()
        val usm = getSystemService(UsageStatsManager::class.java)
        val result = usm.queryEvents(afterMs - QUERY_OVERLAP_MS, nowMs) ?: return emptyList()
        val out = ArrayList<UsageEvent>()
        val e = UsageEvents.Event()
        while (result.hasNextEvent()) {
            result.getNextEvent(e)
            if (e.timeStamp <= afterMs) continue
            val type = when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> UsageEvent.Type.RESUMED
                UsageEvents.Event.ACTIVITY_PAUSED -> UsageEvent.Type.PAUSED
                else -> continue
            }
            out += UsageEvent(type, e.packageName, e.className, e.timeStamp)
        }
        return out
    }

    private fun heartbeat(now: Long) {
        if (now - lastHeartbeatMs < HEARTBEAT_MS) return
        lastHeartbeatMs = now
        prefs.edit().putLong(KEY_HEARTBEAT, now).apply()
    }

    /** If the last run ended without a clean stop, the system killed it: log that as a lapse. */
    private fun recordStartAndDetectGap(now: Long) {
        val last = prefs.getLong(KEY_HEARTBEAT, 0)
        val clean = prefs.getBoolean(KEY_CLEAN_STOP, true)
        val gapMs = now - last
        if (last > 0 && !clean && gapMs > GAP_THRESHOLD_MS) {
            val minutes = gapMs / 60_000
            detourApp.appScope.launch {
                detourApp.database.lapses().insert(
                    LapseEventEntity(
                        atMs = last,
                        kind = "detector_stopped",
                        detail = "Detour's watcher wasn't running for about $minutes min.",
                    ),
                )
            }
        }
        prefs.edit().putBoolean(KEY_CLEAN_STOP, false).putLong(KEY_HEARTBEAT, now).apply()
        lastHeartbeatMs = now
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Detour running", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Required by Android while Detour watches for blocked apps. Silent."
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_detour)
            .setContentTitle("Detour is on")
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "DetectorService"
        private const val CHANNEL_ID = "detector"
        private const val NOTIFICATION_ID = 1
        private const val POLL_MS = 750L
        private const val SCREEN_OFF_HEARTBEAT_MS = 60_000L
        private const val HEARTBEAT_MS = 30_000L
        private const val GAP_THRESHOLD_MS = 3 * 60_000L
        private const val INITIAL_LOOKBACK_MS = 10 * 60_000L
        private const val QUERY_OVERLAP_MS = 2_000L
        private const val KEEPALIVE_CHECK_MS = 5_000L
        private const val HEALTH_CHECK_MS = 30_000L
        private const val CALIBRATION_WINDOW_MS = 3 * 60_000L
        const val SETTINGS_PAUSE_SECONDS = 60

        /** True while the detector is alive in this process (read by the health check). */
        @Volatile var isRunning = false
            private set
        private const val KEY_HEARTBEAT = "heartbeat"
        private const val KEY_CLEAN_STOP = "clean_stop"
        private val HOURS: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm")

        /** Starts the detector if the permissions it needs are granted. Safe to call repeatedly. */
        fun startIfPermitted(context: Context) {
            if (!Permissions.canDetect(context)) return
            context.startForegroundService(Intent(context, DetectorService::class.java))
        }
    }
}
