package dev.detour.core.settings

import dev.detour.core.AppCategory
import dev.detour.core.budget.BudgetSettings
import dev.detour.core.time.AllowedHours

/** A browser blocklist group as far as tighten/loosen is concerned. */
data class GroupState(
    val id: String,
    val name: String,
    val domains: Set<String>,
    val blocked: Boolean,
) {
    /** A group only protects anything when it's switched on and has domains. */
    val effective: Boolean get() = blocked && domains.isNotEmpty()
}

/** A screen the Settings guard watches, identified by its (package, activity class). */
data class GuardTarget(val key: String, val packageName: String, val className: String)

/**
 * Every setting that changes how strict Detour is. All changes to these go through
 * [classify]: tighter applies at once, looser waits 24 hours.
 */
data class ProtectedSettings(
    /** Effective category per app (the user's choice, or the default/suggestion if none). */
    val categories: Map<String, AppCategory> = emptyMap(),
    val budget: BudgetSettings = BudgetSettings(),
    val blocklist: List<GroupState> = emptyList(),
    val allowedBrowser: String = "com.android.chrome",
    val settingsGuardEnabled: Boolean = true,
    val guardTargets: Set<GuardTarget> = emptySet(),
)

enum class ChangeKind { TIGHTEN, LOOSEN, NEUTRAL }

/**
 * Decides whether going from [old] to [new] is tighter, looser, or neither. This is the one
 * place that decides it, so no setting can bypass the 24-hour queue.
 *
 * Rules: if anything got looser, the whole change is LOOSEN (even if something else got tighter);
 * otherwise TIGHTEN if anything got tighter; otherwise NEUTRAL. Anything ambiguous counts as LOOSEN.
 */
fun classify(old: ProtectedSettings, new: ProtectedSettings): ChangeKind {
    val parts = buildList {
        addAll(classifyCategories(old.categories, new.categories))
        addAll(classifyBudget(old.budget, new.budget))
        addAll(classifyBlocklist(old.blocklist, new.blocklist))
        if (old.allowedBrowser != new.allowedBrowser) add(ChangeKind.LOOSEN)
        add(flag(old.settingsGuardEnabled, new.settingsGuardEnabled))
        add(set(old.guardTargets, new.guardTargets))
    }
    return combine(parts)
}

internal fun combine(parts: Collection<ChangeKind>): ChangeKind = when {
    ChangeKind.LOOSEN in parts -> ChangeKind.LOOSEN
    ChangeKind.TIGHTEN in parts -> ChangeKind.TIGHTEN
    else -> ChangeKind.NEUTRAL
}

/** On -> off is looser; off -> on is tighter. */
private fun flag(old: Boolean, new: Boolean) = when {
    old == new -> ChangeKind.NEUTRAL
    new -> ChangeKind.TIGHTEN
    else -> ChangeKind.LOOSEN
}

/** Removing protective items is looser; adding them is tighter. */
private fun <T> set(old: Set<T>, new: Set<T>): ChangeKind = when {
    (old - new).isNotEmpty() -> ChangeKind.LOOSEN
    (new - old).isNotEmpty() -> ChangeKind.TIGHTEN
    else -> ChangeKind.NEUTRAL
}

/** Lower is stricter (budget, waits reversed elsewhere); null means "no limit". */
private fun limit(old: Int?, new: Int?): ChangeKind = when {
    old == new -> ChangeKind.NEUTRAL
    new == null -> ChangeKind.LOOSEN
    old == null -> ChangeKind.TIGHTEN
    new < old -> ChangeKind.TIGHTEN
    else -> ChangeKind.LOOSEN
}

private fun classifyCategories(old: Map<String, AppCategory>, new: Map<String, AppCategory>): List<ChangeKind> =
    (old.keys + new.keys).map { pkg ->
        val o = old[pkg]
        val n = new[pkg]
        when {
            o == n -> ChangeKind.NEUTRAL
            o != null && n != null -> when {
                n.isStricterThan(o) -> ChangeKind.TIGHTEN
                else -> ChangeKind.LOOSEN
            }
            // Unknown on one side: only "towards Allowed" or "away from a restriction" is risky.
            o == null -> if (n == AppCategory.ALLOWED) ChangeKind.LOOSEN else ChangeKind.TIGHTEN
            else -> if (o == AppCategory.ALLOWED) ChangeKind.NEUTRAL else ChangeKind.LOOSEN
        }
    }

private fun classifyBudget(old: BudgetSettings, new: BudgetSettings): List<ChangeKind> = listOf(
    limit(old.dailyBudgetMinutes, new.dailyBudgetMinutes),
    limit(old.opensCap, new.opensCap),
    hours(old.allowedHours, new.allowedHours),
    if (old.resetTime != new.resetTime) ChangeKind.LOOSEN else ChangeKind.NEUTRAL,
    waits(old, new),
    // Check in more often / count reopens sooner = stricter.
    limit(old.sessionCheckInMinutes, new.sessionCheckInMinutes),
    limit(old.reopenAfterSeconds, new.reopenAfterSeconds),
    observing(old.observeUntilMs, new.observeUntilMs),
    // budgetConfirmed is cosmetic.
)

/** Shrinking the window (new inside old) is tighter; anything that adds allowed time is looser. */
private fun hours(old: AllowedHours?, new: AllowedHours?): ChangeKind {
    if (old == new) return ChangeKind.NEUTRAL
    val o = minutesOf(old)
    val n = minutesOf(new)
    return when {
        n.any { it !in o } -> ChangeKind.LOOSEN
        n.size < o.size -> ChangeKind.TIGHTEN
        else -> ChangeKind.NEUTRAL
    }
}

private fun minutesOf(h: AllowedHours?): Set<Int> {
    if (h == null) return (0 until 24 * 60).toSet()
    return (0 until 24 * 60).filter { h.contains(java.time.LocalTime.of(it / 60, it % 60)) }.toSet()
}

/** Every wait at least as long = tighter; any wait shorter = looser. */
private fun waits(old: BudgetSettings, new: BudgetSettings): ChangeKind {
    if (old.waitsSeconds == new.waitsSeconds) return ChangeKind.NEUTRAL
    val n = maxOf(old.waitsSeconds.size, new.waitsSeconds.size) + 1
    val diffs = (0 until n).map { new.waitFor(it) - old.waitFor(it) }
    return when {
        diffs.any { it < 0 } -> ChangeKind.LOOSEN
        diffs.any { it > 0 } -> ChangeKind.TIGHTEN
        else -> ChangeKind.NEUTRAL
    }
}

/** Ending observe-only mode (limits start) is tighter; starting or extending it is looser. */
private fun observing(old: Long?, new: Long?): ChangeKind = when {
    old == new -> ChangeKind.NEUTRAL
    new == null -> ChangeKind.TIGHTEN
    old == null -> ChangeKind.LOOSEN
    new < old -> ChangeKind.TIGHTEN
    else -> ChangeKind.LOOSEN
}

private fun classifyBlocklist(old: List<GroupState>, new: List<GroupState>): List<ChangeKind> {
    val oldById = old.associateBy { it.id }
    val newById = new.associateBy { it.id }
    return (oldById.keys + newById.keys).map { id ->
        val o = oldById[id]
        val n = newById[id]
        when {
            o == null -> if (n!!.effective) ChangeKind.TIGHTEN else ChangeKind.NEUTRAL
            n == null -> if (o.effective) ChangeKind.LOOSEN else ChangeKind.NEUTRAL
            else -> combine(
                listOf(
                    when {
                        o.blocked && !n.blocked && o.domains.isNotEmpty() -> ChangeKind.LOOSEN
                        !o.blocked && n.blocked && n.domains.isNotEmpty() -> ChangeKind.TIGHTEN
                        else -> ChangeKind.NEUTRAL
                    },
                    // Domain edits only matter while the group is blocked.
                    if (o.blocked && n.blocked) set(o.domains, n.domains) else ChangeKind.NEUTRAL,
                ),
            )
        }
    }
}
