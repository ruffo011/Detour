package dev.detour.core.budget

import dev.detour.core.time.AllowedHours
import dev.detour.core.time.DayClock
import java.time.LocalTime

/** Everything that controls the shared Distracting budget. */
data class BudgetSettings(
    /** Shared daily minutes for all Distracting apps. Null while observing, before a suggestion. */
    val dailyBudgetMinutes: Int? = null,
    /** Optional limit on opens per day; null = off. */
    val opensCap: Int? = null,
    /** Optional window for spending the budget; null = any time. */
    val allowedHours: AllowedHours? = AllowedHours.DEFAULT,
    val resetTime: LocalTime = DayClock.DEFAULT_RESET,
    /** Non-skippable wait before each open, escalating with opens today. */
    val waitsSeconds: List<Int> = DEFAULT_WAITS,
    /** "Still doing what you came for?" after this many minutes in one session. */
    val sessionCheckInMinutes: Int = 10,
    /** Observe-only mode for the budget lasts until this time; null once it has ended. */
    val observeUntilMs: Long? = null,
    /** True once you've confirmed (or adjusted) the suggested budget after observing. */
    val budgetConfirmed: Boolean = false,
) {
    fun isObserving(nowMs: Long): Boolean = observeUntilMs != null && nowMs < observeUntilMs

    /** Wait before the next open, given how many opens already happened today. */
    fun waitFor(opensBefore: Int): Int {
        if (waitsSeconds.isEmpty()) return 0
        return waitsSeconds[opensBefore.coerceIn(0, waitsSeconds.lastIndex)]
    }

    companion object {
        val DEFAULT_WAITS = listOf(5, 15, 30, 60)
        const val OBSERVE_DAYS = 7L
        const val WARNING_MINUTES = 5
    }
}
