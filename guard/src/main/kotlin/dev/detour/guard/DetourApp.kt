package dev.detour.guard

import android.app.Application
import android.content.Context
import dev.detour.guard.data.ClassificationRepository
import dev.detour.guard.data.DetourDatabase
import dev.detour.guard.system.AppCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Simple manual dependency container: one database and repository for the whole process. */
class DetourApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val database by lazy { DetourDatabase.create(this) }
    val classifications by lazy { ClassificationRepository(database.classifications(), appScope) }
    val catalog by lazy { AppCatalog(this) }
}

val Context.detourApp: DetourApp get() = applicationContext as DetourApp
