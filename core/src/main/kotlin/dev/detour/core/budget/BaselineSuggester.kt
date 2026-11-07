package dev.detour.core.budget

import kotlin.math.floor
import kotlin.math.roundToInt

data class BaselineSuggestion(
    val averageMinutes: Int,
    val averageOpens: Int,
    val suggestedBudgetMinutes: Int,
)

/**
 * After the observe-only week: suggests a starting budget slightly below your real average
 * (85%, rounded down to 5 minutes, at least [MIN_BUDGET]).
 */
object BaselineSuggester {
    const val FACTOR = 0.85
    const val MIN_BUDGET = 10

    fun suggest(days: List<DayTotals>): BaselineSuggestion {
        if (days.isEmpty()) return BaselineSuggestion(0, 0, MIN_BUDGET)
        val avgMinutes = days.map { it.usedMinutes }.average()
        val avgOpens = days.map { it.opens }.average()
        val suggested = (floor(avgMinutes * FACTOR / 5.0) * 5).toInt().coerceAtLeast(MIN_BUDGET)
        return BaselineSuggestion(avgMinutes.roundToInt(), avgOpens.roundToInt(), suggested)
    }
}
