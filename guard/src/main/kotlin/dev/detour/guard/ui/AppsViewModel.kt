package dev.detour.guard.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.detour.core.AppCategory
import dev.detour.core.Resolution
import dev.detour.guard.DetourApp
import dev.detour.guard.system.InstalledApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AppRow(val app: InstalledApp, val resolution: Resolution)

class AppsViewModel(application: Application) : AndroidViewModel(application) {
    private val detour = application as DetourApp
    private val installed = MutableStateFlow<List<InstalledApp>?>(null)

    val rows: StateFlow<List<AppRow>?> = combine(installed, detour.classifications.choices) { apps, choices ->
        if (apps == null || choices == null) null
        else apps.map { AppRow(it, detour.catalog.resolve(it.packageName, choices)) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            detour.catalog.invalidate()
            installed.value = detour.catalog.launchableApps()
        }
    }

    fun setCategory(packageName: String, category: AppCategory) {
        viewModelScope.launch { detour.classifications.setChoice(packageName, category) }
    }
}
