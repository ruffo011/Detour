package dev.detour.guard.data

import dev.detour.core.budget.BaselineSuggester
import dev.detour.core.budget.BaselineSuggestion
import dev.detour.core.budget.BudgetSettings
import dev.detour.core.budget.UsageMath
import dev.detour.guard.DetourApp

/** Ending the observe-only week: measure your baseline and apply the suggested budget. */
object ObserveWeek {
    /** Called by the detector every tick; does nothing until the week is over. */
    suspend fun finishIfDue(app: DetourApp, nowMs: Long) {
        val until = app.settings.current.observeUntilMs ?: return
        if (nowMs >= until) finish(app, nowMs)
    }

    /**
     * Ends observing now (at the end of the week, or early if you choose). Uses every complete
     * day observed so far, or today alone if not even one day has passed.
     */
    suspend fun finish(app: DetourApp, nowMs: Long): BaselineSuggestion? {
        val until = app.settings.current.observeUntilMs ?: return null
        val clock = app.settings.clock()
        val firstDay = clock.dayOf(until - 1).minusDays(BudgetSettings.OBSERVE_DAYS - 1)
        val endDay = minOf(clock.dayOf(until - 1), clock.dayOf(nowMs).minusDays(1))
        val days = generateSequence(firstDay) { it.plusDays(1) }.takeWhile { it <= endDay }.toList()
            .ifEmpty { listOf(clock.dayOf(nowMs)) }
        val gapMs = app.settings.current.reopenGapMs
        val intervals = app.usage.intervalsSince(clock.startOf(days.first()) - 20 * 60_000L)
        val baseline = BaselineSuggester.suggest(days.map { UsageMath.totalsFor(it, clock, intervals, gapMs) })
        app.settings.finishObserving(baseline)
        return baseline
    }
}
