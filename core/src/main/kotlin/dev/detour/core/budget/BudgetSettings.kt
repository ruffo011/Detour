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
    /**
     * How long an app must be away before coming back counts as a new open (and needs the pause
     * again). Longer is looser: more coming and going without a pause.
     */
    val reopenAfterSeconds: Int = DEFAULT_REOPEN_SECONDS,
) {
    val reopenGapMs: Long get() = reopenAfterSeconds * 1_000L

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
        const val DEFAULT_REOPEN_SECONDS = 30

        /** Choices offered in Budget & hours. */
        val REOPEN_CHOICES_SECONDS = listOf(5, 10, 15, 20, 30, 45, 60, 90, 120, 180, 300, 600)
    }
}
