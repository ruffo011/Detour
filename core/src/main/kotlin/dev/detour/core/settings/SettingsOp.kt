package dev.detour.core.settings

import dev.detour.core.AppCategory
import dev.detour.core.time.AllowedHours
import java.time.LocalTime

/**
 * One change to [ProtectedSettings]. Changes are stored as ops (not whole snapshots) so a change
 * that waits 24 hours applies on top of whatever happened meanwhile, without undoing it.
 * Ops serialize to a single tab-separated line for the database and backups.
 */
sealed class SettingsOp {
    abstract fun apply(s: ProtectedSettings): ProtectedSettings
    abstract fun encode(): String

    data class SetCategory(val packageName: String, val category: AppCategory) : SettingsOp() {
        override fun apply(s: ProtectedSettings) = s.copy(categories = s.categories + (packageName to category))
        override fun encode() = line("cat", packageName, category.name)
    }

    data class SetDailyBudget(val minutes: Int?) : SettingsOp() {
        override fun apply(s: ProtectedSettings) = s.copy(budget = s.budget.copy(dailyBudgetMinutes = minutes))
        override fun encode() = line("budget", minutes.orDash())
    }

    data class SetOpensCap(val cap: Int?) : SettingsOp() {
        override fun apply(s: ProtectedSettings) = s.copy(budget = s.budget.copy(opensCap = cap))
        override fun encode() = line("opens", cap.orDash())
    }

    data class SetAllowedHours(val hours: AllowedHours?) : SettingsOp() {
        override fun apply(s: ProtectedSettings) = s.copy(budget = s.budget.copy(allowedHours = hours))
        override fun encode() = if (hours == null) line("hours", "-") else
            line("hours", hours.start.toSecondOfDay().toString(), hours.end.toSecondOfDay().toString())
    }

    data class SetResetTime(val time: LocalTime) : SettingsOp() {
        override fun apply(s: ProtectedSettings) = s.copy(budget = s.budget.copy(resetTime = time))
        override fun encode() = line("reset", time.toSecondOfDay().toString())
    }

    data class SetWaits(val waits: List<Int>) : SettingsOp() {
        override fun apply(s: ProtectedSettings) = s.copy(budget = s.budget.copy(waitsSeconds = waits))
        override fun encode() = line("waits", waits.joinToString(","))
    }

    data class SetCheckInMinutes(val minutes: Int) : SettingsOp() {
        override fun apply(s: ProtectedSettings) = s.copy(budget = s.budget.copy(sessionCheckInMinutes = minutes))
        override fun encode() = line("checkin", minutes.toString())
    }

    data class SetReopenSeconds(val seconds: Int) : SettingsOp() {
        override fun apply(s: ProtectedSettings) = s.copy(budget = s.budget.copy(reopenAfterSeconds = seconds))
        override fun encode() = line("reopen", seconds.toString())
    }

    /** null = stop observing (limits start); a time = observe until then. */
    data class SetObserveUntil(val untilMs: Long?) : SettingsOp() {
        override fun apply(s: ProtectedSettings) = s.copy(budget = s.budget.copy(observeUntilMs = untilMs))
        override fun encode() = line("observe", untilMs?.toString() ?: "-")
    }

    data class SetBudgetConfirmed(val confirmed: Boolean) : SettingsOp() {
        override fun apply(s: ProtectedSettings) = s.copy(budget = s.budget.copy(budgetConfirmed = confirmed))
        override fun encode() = line("confirm", if (confirmed) "1" else "0")
    }

    data class SetGroupBlocked(val groupId: String, val blocked: Boolean) : SettingsOp() {
        override fun apply(s: ProtectedSettings) = s.editGroup(groupId) { it.copy(blocked = blocked) }
        override fun encode() = line("grp_blocked", groupId, if (blocked) "1" else "0")
    }

    data class AddDomain(val groupId: String, val domain: String) : SettingsOp() {
        override fun apply(s: ProtectedSettings) = s.editGroup(groupId) { it.copy(domains = it.domains + domain) }
        override fun encode() = line("dom_add", groupId, domain)
    }

    data class RemoveDomain(val groupId: String, val domain: String) : SettingsOp() {
        override fun apply(s: ProtectedSettings) = s.editGroup(groupId) { it.copy(domains = it.domains - domain) }
        override fun encode() = line("dom_del", groupId, domain)
    }

    /** Adds an empty group, switched on. Domains are added with [AddDomain]. */
    data class AddGroup(val groupId: String, val name: String) : SettingsOp() {
        override fun apply(s: ProtectedSettings) =
            if (s.blocklist.any { it.id == groupId }) s
            else s.copy(blocklist = s.blocklist + GroupState(groupId, name, emptySet(), blocked = true))
        override fun encode() = line("grp_add", groupId, name)
    }

    data class DeleteGroup(val groupId: String) : SettingsOp() {
        override fun apply(s: ProtectedSettings) = s.copy(blocklist = s.blocklist.filter { it.id != groupId })
        override fun encode() = line("grp_del", groupId)
    }

    data class SetAllowedBrowser(val packageName: String) : SettingsOp() {
        override fun apply(s: ProtectedSettings) = s.copy(allowedBrowser = packageName)
        override fun encode() = line("browser", packageName)
    }

    data class SetSettingsGuard(val enabled: Boolean) : SettingsOp() {
        override fun apply(s: ProtectedSettings) = s.copy(settingsGuardEnabled = enabled)
        override fun encode() = line("sguard", if (enabled) "1" else "0")
    }

    data class AddGuardTarget(val target: GuardTarget) : SettingsOp() {
        override fun apply(s: ProtectedSettings) = s.copy(guardTargets = s.guardTargets + target)
        override fun encode() = line("starget_add", target.key, target.packageName, target.className)
    }

    data class RemoveGuardTarget(val target: GuardTarget) : SettingsOp() {
        override fun apply(s: ProtectedSettings) = s.copy(guardTargets = s.guardTargets - target)
        override fun encode() = line("starget_del", target.key, target.packageName, target.className)
    }

    companion object {
        private fun line(vararg parts: String) = parts.joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') }
        private fun Int?.orDash() = this?.toString() ?: "-"
        private fun intOrNull(s: String) = if (s == "-") null else s.toInt()

        private fun ProtectedSettings.editGroup(id: String, f: (GroupState) -> GroupState) =
            copy(blocklist = blocklist.map { if (it.id == id) f(it) else it })

        /** Parses [encode]'s output; null for anything unrecognized. */
        fun decode(line: String): SettingsOp? = runCatching {
            val p = line.split('\t')
            when (p[0]) {
                "cat" -> SetCategory(p[1], AppCategory.valueOf(p[2]))
                "budget" -> SetDailyBudget(intOrNull(p[1]))
                "opens" -> SetOpensCap(intOrNull(p[1]))
                "hours" -> SetAllowedHours(
                    if (p[1] == "-") null
                    else AllowedHours(LocalTime.ofSecondOfDay(p[1].toLong()), LocalTime.ofSecondOfDay(p[2].toLong())),
                )
                "reset" -> SetResetTime(LocalTime.ofSecondOfDay(p[1].toLong()))
                "waits" -> SetWaits(p[1].split(',').filter { it.isNotBlank() }.map { it.trim().toInt() })
                "checkin" -> SetCheckInMinutes(p[1].toInt())
                "reopen" -> SetReopenSeconds(p[1].toInt())
                "observe" -> SetObserveUntil(if (p[1] == "-") null else p[1].toLong())
                "confirm" -> SetBudgetConfirmed(p[1] == "1")
                "grp_blocked" -> SetGroupBlocked(p[1], p[2] == "1")
                "dom_add" -> AddDomain(p[1], p[2])
                "dom_del" -> RemoveDomain(p[1], p[2])
                "grp_add" -> AddGroup(p[1], p[2])
                "grp_del" -> DeleteGroup(p[1])
                "browser" -> SetAllowedBrowser(p[1])
                "sguard" -> SetSettingsGuard(p[1] == "1")
                "starget_add" -> AddGuardTarget(GuardTarget(p[1], p[2], p[3]))
                "starget_del" -> RemoveGuardTarget(GuardTarget(p[1], p[2], p[3]))
                else -> null
            }
        }.getOrNull()
    }
}

/** A loosening change waiting for its 24 hours. */
data class PendingChange(
    val id: Long,
    val op: SettingsOp,
    val description: String,
    val createdAtMs: Long,
    val applyAtMs: Long,
) {
    fun isDue(nowMs: Long) = nowMs >= applyAtMs
}

object ChangeGate {
    const val LOOSEN_DELAY_MS = 24 * 60 * 60 * 1000L

    data class Decision(val kind: ChangeKind, val result: ProtectedSettings) {
        val immediate: Boolean get() = kind != ChangeKind.LOOSEN
    }

    fun decide(current: ProtectedSettings, op: SettingsOp): Decision {
        val result = op.apply(current)
        return Decision(classify(current, result), result)
    }
}

/**
 * Restoring a backup must not sneak in a looser setup. The backup is merged into the current
 * settings value by value (nothing present only in the current settings is removed), and each
 * difference becomes an op that goes through [ChangeGate] like any other change: tighter ones
 * apply now, looser ones wait 24 hours.
 */
object RestorePlanner {
    fun diff(current: ProtectedSettings, backup: ProtectedSettings): List<SettingsOp> = buildList {
        for ((pkg, cat) in backup.categories) {
            if (current.categories[pkg] != cat) add(SettingsOp.SetCategory(pkg, cat))
        }
        val c = current.budget
        val b = backup.budget
        if (c.dailyBudgetMinutes != b.dailyBudgetMinutes) add(SettingsOp.SetDailyBudget(b.dailyBudgetMinutes))
        if (c.opensCap != b.opensCap) add(SettingsOp.SetOpensCap(b.opensCap))
        if (c.allowedHours != b.allowedHours) add(SettingsOp.SetAllowedHours(b.allowedHours))
        if (c.resetTime != b.resetTime) add(SettingsOp.SetResetTime(b.resetTime))
        if (c.waitsSeconds != b.waitsSeconds) add(SettingsOp.SetWaits(b.waitsSeconds))
        if (c.sessionCheckInMinutes != b.sessionCheckInMinutes) add(SettingsOp.SetCheckInMinutes(b.sessionCheckInMinutes))
        if (c.reopenAfterSeconds != b.reopenAfterSeconds) add(SettingsOp.SetReopenSeconds(b.reopenAfterSeconds))
        if (c.observeUntilMs != b.observeUntilMs) add(SettingsOp.SetObserveUntil(b.observeUntilMs))
        if (c.budgetConfirmed != b.budgetConfirmed) add(SettingsOp.SetBudgetConfirmed(b.budgetConfirmed))

        val currentGroups = current.blocklist.associateBy { it.id }
        for (g in backup.blocklist) {
            val cur = currentGroups[g.id]
            if (cur == null) {
                add(SettingsOp.AddGroup(g.id, g.name))
                g.domains.forEach { add(SettingsOp.AddDomain(g.id, it)) }
                if (!g.blocked) add(SettingsOp.SetGroupBlocked(g.id, false))
            } else {
                (g.domains - cur.domains).forEach { add(SettingsOp.AddDomain(g.id, it)) }
                (cur.domains - g.domains).forEach { add(SettingsOp.RemoveDomain(g.id, it)) }
                if (cur.blocked != g.blocked) add(SettingsOp.SetGroupBlocked(g.id, g.blocked))
            }
        }
        if (current.allowedBrowser != backup.allowedBrowser) add(SettingsOp.SetAllowedBrowser(backup.allowedBrowser))
        if (current.settingsGuardEnabled != backup.settingsGuardEnabled) add(SettingsOp.SetSettingsGuard(backup.settingsGuardEnabled))
        (backup.guardTargets - current.guardTargets).forEach { add(SettingsOp.AddGuardTarget(it)) }
    }

    data class Plan(val immediate: List<SettingsOp>, val queued: List<SettingsOp>)

    /** Splits the diff into what applies now and what waits, evaluating each op in order. */
    fun plan(current: ProtectedSettings, backup: ProtectedSettings): Plan {
        val ops = diff(current, backup)
        val immediate = ArrayList<SettingsOp>()
        val queued = ArrayList<SettingsOp>()
        var state = current
        // Tightening first, so later ops are judged against the stricter state.
        for (op in ops.sortedBy { if (ChangeGate.decide(current, it).immediate) 0 else 1 }) {
            val d = ChangeGate.decide(state, op)
            if (d.immediate) {
                immediate += op
                state = d.result
            } else {
                queued += op
            }
        }
        return Plan(immediate, queued)
    }
}
