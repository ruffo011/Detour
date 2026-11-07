package dev.detour.core.review

import dev.detour.core.alternatives.Feeling
import dev.detour.core.alternatives.Outcome
import dev.detour.core.budget.BudgetSettings
import dev.detour.core.budget.DayTotals
import dev.detour.core.budget.UsageInterval
import dev.detour.core.budget.UsageMath
import dev.detour.core.settings.SettingsOp
import dev.detour.core.time.AllowedHours
import dev.detour.core.time.DayClock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.ceil
import kotlin.math.roundToInt

data class DaySummary(val day: LocalDate, val totals: DayTotals)

/** How one alternative went for one feeling this week. */
data class AlternativeTally(
    val title: String,
    val chosen: Int,
    val outcomes: Map<Outcome, Int>,
) {
    val unanswered: Int get() = chosen - outcomes.values.sum()
}

/** A choice made this week, joined with its alternative's title. */
data class ChoiceRow(val title: String, val feeling: Feeling, val outcome: Outcome?)

/** A tightening step the data supports. Accepting applies [op] immediately (it's always tighter). */
data class Suggestion(val text: String, val op: SettingsOp)

object WeeklyReview {
    /** The last [days] complete-or-current Detour days ending with [endDay]. */
    fun days(
        intervals: List<UsageInterval>,
        clock: DayClock,
        endDay: LocalDate,
        gapMs: Long = UsageMath.NEW_SESSION_GAP_MS,
        days: Int = 7,
    ): List<DaySummary> = (days - 1 downTo 0).map { back ->
        val d = endDay.minusDays(back.toLong())
        DaySummary(d, UsageMath.totalsFor(d, clock, intervals, gapMs))
    }

    /** Alternatives chosen this week, per feeling, with their outcomes. Skipped check-ins count as no answer. */
    fun tallies(choices: List<ChoiceRow>): Map<Feeling, List<AlternativeTally>> =
        choices.groupBy { it.feeling }.mapValues { (_, rows) ->
            rows.groupBy { it.title }.map { (title, r) ->
                AlternativeTally(
                    title = title,
                    chosen = r.size,
                    outcomes = r.mapNotNull { it.outcome }.filter { it != Outcome.SKIPPED }.groupingBy { it }.eachCount(),
                )
            }.sortedByDescending { it.chosen }
        }.toSortedMap(compareBy { it.ordinal })

    /**
     * Tightening steps the week's data supports. Never suggests anything looser, and nothing
     * while observing (there's no budget yet) or with fewer than [minDays] days that had any data.
     */
    fun suggestions(
        settings: BudgetSettings,
        week: List<DaySummary>,
        intervals: List<UsageInterval>,
        clock: DayClock,
        nowMs: Long,
        minDays: Int = 5,
    ): List<Suggestion> {
        if (settings.isObserving(nowMs)) return emptyList()
        if (week.size < minDays) return emptyList()
        val out = ArrayList<Suggestion>()
        val avgMin = week.map { it.totals.usedMinutes }.average()
        val maxOpens = week.maxOf { it.totals.opens }
        val avgOpens = week.map { it.totals.opens }.average()

        // 1. Budget well above what you actually use.
        val budget = settings.dailyBudgetMinutes
        if (budget != null && avgMin < budget * 0.75) {
            val target = (ceil(avgMin * 1.1 / 5.0) * 5).toInt().coerceAtLeast(10)
            if (target < budget) {
                out += Suggestion(
                    "You used about ${avgMin.roundToInt()} min a day, well under your $budget min. Lower the daily time to $target min?",
                    SettingsOp.SetDailyBudget(target),
                )
            }
        }

        // 2. No opens limit yet, and opens are fairly steady: cap at your busiest day.
        if (settings.opensCap == null && avgOpens >= 3 && maxOpens > 0) {
            out += Suggestion(
                "You opened Distracting apps about ${avgOpens.roundToInt()} times a day (at most $maxOpens). Set a limit of $maxOpens opens a day?",
                SettingsOp.SetOpensCap(maxOpens),
            )
        }

        // 3. Allowed hours with whole unused hours at either end.
        settings.allowedHours?.let { hours -> trimHours(hours, intervals, clock, week)?.let { out += it } }

        return out
    }

    /** Suggests shrinking the allowed window to the hours you actually used this week (+ 1 h slack). */
    internal fun trimHours(hours: AllowedHours, intervals: List<UsageInterval>, clock: DayClock, week: List<DaySummary>): Suggestion? {
        if (hours.start >= hours.end) return null // wrapping windows: leave alone
        val from = clock.startOf(week.first().day)
        val to = clock.endOf(week.last().day)
        // Local hours of the day in which any Distracting app was in the foreground (sampled every 5 min).
        val usedHours = HashSet<Int>()
        for (iv in intervals) {
            var t = maxOf(iv.startMs, from)
            val end = minOf(iv.endMs, to)
            while (t < end) {
                usedHours += Instant.ofEpochMilli(t).atZone(clock.zone).hour
                t += 5 * 60_000L
            }
        }
        if (usedHours.isEmpty()) return null
        val firstUsed = usedHours.min()
        val lastUsed = usedHours.max()
        val newStart = maxOf(hours.start, LocalTime.of((firstUsed - 1).coerceAtLeast(0), 0))
        val newEnd = if (lastUsed + 2 >= 24) hours.end else minOf(hours.end, LocalTime.of(lastUsed + 2, 0))
        if (newStart == hours.start && newEnd == hours.end) return null
        if (!newStart.isBefore(newEnd)) return null
        val trimmed = AllowedHours(newStart, newEnd)
        if (trimmed.lengthMinutes >= hours.lengthMinutes - 60) return null // only worth it for an hour or more
        return Suggestion(
            "You didn't use Distracting apps outside about ${fmt(newStart)}–${fmt(newEnd)}. Shrink allowed hours to that?",
            SettingsOp.SetAllowedHours(trimmed),
        )
    }

    private fun fmt(t: LocalTime) = "%d:%02d".format(t.hour, t.minute)

    /** Show the gentle "your weekly review is ready" prompt? Once a week, after the first full week. */
    fun isDue(firstRunMs: Long, lastViewedMs: Long, nowMs: Long): Boolean {
        val week = 7 * 24 * 60 * 60 * 1000L
        if (nowMs - firstRunMs < week) return false
        return nowMs - lastViewedMs >= week
    }
}
