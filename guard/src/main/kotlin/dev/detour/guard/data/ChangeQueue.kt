package dev.detour.guard.data

import dev.detour.core.AppCategory
import dev.detour.core.settings.ChangeGate
import dev.detour.core.settings.GroupState
import dev.detour.core.settings.PendingChange
import dev.detour.core.settings.ProtectedSettings
import dev.detour.core.settings.SettingsOp
import dev.detour.guard.DetourApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What happened to a submitted change. */
sealed class SubmitResult {
    object Applied : SubmitResult()
    data class Queued(val applyAtMs: Long) : SubmitResult()
}

/**
 * The only way the UI changes a protected setting. Each change is classified against the
 * current settings with [dev.detour.core.settings.classify]: tighter or neutral changes apply
 * immediately, looser ones wait 24 hours in the "Pending changes" list (cancellable).
 */
class ChangeQueue(private val app: DetourApp, private val dao: PendingDao) {
    private val mutex = Mutex()

    val pending: Flow<List<PendingChange>> = dao.observeAll().map { rows -> rows.mapNotNull { it.toModel() } }

    suspend fun submit(op: SettingsOp, description: String): SubmitResult = withContext(Dispatchers.Default) { submitLocked(op, description) }

    private suspend fun submitLocked(op: SettingsOp, description: String): SubmitResult = mutex.withLock {
        val decision = ChangeGate.decide(snapshot(), op)
        if (decision.immediate) {
            applyDirect(op)
            SubmitResult.Applied
        } else {
            val now = System.currentTimeMillis()
            val applyAt = now + ChangeGate.LOOSEN_DELAY_MS
            dao.insert(PendingChangeEntity(op = op.encode(), description = description, createdAtMs = now, applyAtMs = applyAt))
            SubmitResult.Queued(applyAt)
        }
    }

    /** Puts back a pending change from a backup, keeping its original timestamps. */
    suspend fun restorePending(change: PendingChange) {
        dao.insert(
            PendingChangeEntity(
                op = change.op.encode(),
                description = change.description,
                createdAtMs = change.createdAtMs,
                applyAtMs = change.applyAtMs,
            ),
        )
    }

    suspend fun queueRaw(op: SettingsOp, description: String) {
        val now = System.currentTimeMillis()
        dao.insert(PendingChangeEntity(op = op.encode(), description = description, createdAtMs = now, applyAtMs = now + ChangeGate.LOOSEN_DELAY_MS))
    }

    suspend fun cancel(id: Long) = dao.delete(id)

    suspend fun allPending(): List<PendingChange> = dao.all().mapNotNull { it.toModel() }

    /** Applies every change whose 24 hours are up. Called by the detector and when the app opens. */
    suspend fun applyDue(nowMs: Long = System.currentTimeMillis()) = withContext(Dispatchers.Default) {
        mutex.withLock {
            for (row in dao.all()) {
                if (row.applyAtMs > nowMs) continue
                SettingsOp.decode(row.op)?.let { applyDirect(it) }
                dao.delete(row.id)
            }
        }
    }

    /** The current protected settings, with *effective* categories for every installed app. */
    suspend fun snapshot(): ProtectedSettings {
        val choices = app.classifications.choices.value.orEmpty()
        val effective = app.catalog.launchableApps().associate { it.packageName to app.catalog.resolve(it.packageName, choices).category }
        return ProtectedSettings(
            categories = effective + choices.filterKeys { it !in effective },
            budget = app.settings.current,
            blocklist = app.blocklist.all().map { GroupState(it.id, it.name, it.domains.toSet(), it.blocked) },
            allowedBrowser = app.catalog.allowedBrowser,
            settingsGuardEnabled = app.settingsGuard.enabled.value,
            guardTargets = app.settingsGuard.targets.value,
        )
    }

    /** Writes one op to wherever that setting lives. Never call from UI code: use [submit]. */
    private suspend fun applyDirect(op: SettingsOp) {
        when (op) {
            is SettingsOp.SetCategory -> app.classifications.setChoice(op.packageName, op.category)
            is SettingsOp.SetDailyBudget,
            is SettingsOp.SetOpensCap,
            is SettingsOp.SetAllowedHours,
            is SettingsOp.SetResetTime,
            is SettingsOp.SetWaits,
            is SettingsOp.SetCheckInMinutes,
            is SettingsOp.SetReopenSeconds,
            is SettingsOp.SetObserveUntil,
            is SettingsOp.SetBudgetConfirmed,
            -> app.settings.update { op.apply(ProtectedSettings(budget = it)).budget }
            is SettingsOp.SetGroupBlocked -> app.blocklist.setBlocked(op.groupId, op.blocked)
            is SettingsOp.AddDomain -> app.blocklist.addDomain(op.groupId, op.domain)
            is SettingsOp.RemoveDomain -> app.blocklist.removeDomain(op.groupId, op.domain)
            is SettingsOp.AddGroup -> app.blocklist.addGroup(op.groupId, op.name)
            is SettingsOp.DeleteGroup -> app.blocklist.deleteGroup(op.groupId)
            is SettingsOp.SetAllowedBrowser -> {
                app.catalog.setAllowedBrowser(op.packageName)
                app.blocklist.notifyFilter()
            }
            is SettingsOp.SetSettingsGuard -> app.settingsGuard.setEnabled(op.enabled)
            is SettingsOp.AddGuardTarget -> app.settingsGuard.add(op.target)
            is SettingsOp.RemoveGuardTarget -> app.settingsGuard.remove(op.target)
        }
    }

    private fun PendingChangeEntity.toModel(): PendingChange? =
        SettingsOp.decode(op)?.let { PendingChange(id, it, description, createdAtMs, applyAtMs) }
}

/** Human-readable names for categories in change descriptions. */
fun AppCategory.label(): String = when (this) {
    AppCategory.ALLOWED -> "Allowed"
    AppCategory.DISTRACTING -> "Distracting"
    AppCategory.BLOCKED -> "Blocked"
}
