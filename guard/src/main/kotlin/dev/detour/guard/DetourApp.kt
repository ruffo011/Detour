package dev.detour.guard

import android.app.Application
import android.content.Context
import dev.detour.guard.data.AlternativesRepository
import dev.detour.guard.data.ClassificationRepository
import dev.detour.guard.data.DetourDatabase
import dev.detour.core.budget.SessionState
import dev.detour.guard.data.SettingsStore
import dev.detour.guard.data.UsageTracker
import dev.detour.guard.system.AppCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    val session = SessionState()

    override fun onCreate() {
        super.onCreate()
        settings.startObservingIfNew(System.currentTimeMillis())
        appScope.launch { alternatives.seedIfEmpty() }
    }
}

val Context.detourApp: DetourApp get() = applicationContext as DetourApp
