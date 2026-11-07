package dev.detour.guard

import android.app.Application
import android.content.Context
import dev.detour.core.budget.SessionState
import dev.detour.core.settings.SettingsGuardPolicy
import dev.detour.guard.data.AlternativesRepository
import dev.detour.guard.data.BackupManager
import dev.detour.guard.data.BlocklistRepository
import dev.detour.guard.data.ChangeQueue
import dev.detour.guard.data.ClassificationRepository
import dev.detour.guard.data.DetourDatabase
import dev.detour.guard.data.SettingsStore
import dev.detour.guard.data.UsageTracker
import dev.detour.guard.guardrail.RecentScreens
import dev.detour.guard.guardrail.SettingsGuardStore
import dev.detour.guard.health.HealthMonitor
import dev.detour.guard.system.AppCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/** Simple manual dependency container: one of each for the whole process. */
class DetourApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val database by lazy { DetourDatabase.create(this) }
    val classifications by lazy { ClassificationRepository(database.classifications(), appScope) }
    val catalog by lazy { AppCatalog(this) }
    val settings by lazy { SettingsStore(this) }
    val usage by lazy { UsageTracker(database.usage(), settings) }
    val alternatives by lazy { AlternativesRepository(database.alternatives(), database.intentions()) }
    val session = SessionState(gapMs = { settings.current.reopenGapMs })
    val blocklist by lazy { BlocklistRepository(this, database.blocklist(), allowedBrowser = { catalog.allowedBrowser }) }
    val health by lazy { HealthMonitor(this) }
    val settingsGuard by lazy { SettingsGuardStore(this) }
    val recentScreens = RecentScreens()
    val settingsGuardPolicy = SettingsGuardPolicy()
    val changes by lazy { ChangeQueue(this, database.pending()) }
    val backup by lazy { BackupManager(this) }

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        settings.startObservingIfNew(System.currentTimeMillis())
        appScope.launch {
            alternatives.seedIfEmpty()
            blocklist.seedIfEmpty()
        }
        // Auto-backup a few seconds after anything in the backup changes.
        appScope.launch {
            merge(
                classifications.choices.map { },
                settings.settings.map { },
                blocklist.groups.map { },
                changes.pending.map { },
                alternatives.alternatives.map { },
                alternatives.history.map { },
                settingsGuard.enabled.map { },
                settingsGuard.targets.map { },
            ).debounce(3_000).collect { backup.exportNow() }
        }
    }
}

val Context.detourApp: DetourApp get() = applicationContext as DetourApp
