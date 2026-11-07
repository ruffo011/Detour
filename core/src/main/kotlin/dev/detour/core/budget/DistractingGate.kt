package dev.detour.core.budget

import java.time.LocalTime

enum class GateDecision {
    /** Let it run (observing, or you already went through the pause for this session). */
    ALLOW,

    /** New session with budget left: show the pause (feeling, alternatives, wait, intention). */
    PAUSE,

    /** Budget used up: only alternatives or home. */
    OUT_OF_BUDGET,

    /** Outside allowed hours: only alternatives or home. */
    OUTSIDE_HOURS,

    /** Opens cap reached: only alternatives or home. */
    OPENS_CAP,
}

/** What the gate needs to know about right now. */
data class GateInput(
    val nowMs: Long,
    val localTime: LocalTime,
    val usedMs: Long,
    /** Opens today before the current session. */
    val opensBefore: Int,
    /** You completed the pause for this app and the session hasn't ended. */
    val sessionPermitted: Boolean,
)

object DistractingGate {
    fun decide(settings: BudgetSettings, input: GateInput): GateDecision {
        if (settings.isObserving(input.nowMs)) return GateDecision.ALLOW

        val hours = settings.allowedHours
        if (hours != null && !hours.contains(input.localTime)) return GateDecision.OUTSIDE_HOURS

        val budget = settings.dailyBudgetMinutes
        if (budget != null && input.usedMs >= budget * 60_000L) return GateDecision.OUT_OF_BUDGET

        if (input.sessionPermitted) return GateDecision.ALLOW

        val cap = settings.opensCap
        if (cap != null && input.opensBefore >= cap) return GateDecision.OPENS_CAP

        return GateDecision.PAUSE
    }

    fun remainingMs(settings: BudgetSettings, usedMs: Long): Long? =
        settings.dailyBudgetMinutes?.let { (it * 60_000L - usedMs).coerceAtLeast(0) }
}
