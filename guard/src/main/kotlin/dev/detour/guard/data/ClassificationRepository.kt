package dev.detour.guard.data

import dev.detour.core.AppCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class ClassificationRepository(private val dao: ClassificationDao, scope: CoroutineScope) {
    /** The user's choices, keyed by package. Null until the database has been read once. */
    val choices: StateFlow<Map<String, AppCategory>?> = dao.observeAll()
        .map { rows ->
            rows.mapNotNull { row ->
                runCatching { AppCategory.valueOf(row.category) }.getOrNull()?.let { row.packageName to it }
            }.toMap()
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** Stores a choice directly. Only ChangeQueue calls this (it applies the 24-hour rule). */
    suspend fun setChoice(packageName: String, category: AppCategory) {
        dao.upsert(AppClassificationEntity(packageName, category.name, System.currentTimeMillis()))
    }
}
