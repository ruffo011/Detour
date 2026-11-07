package dev.detour.core.review

import dev.detour.core.alternatives.Feeling
import dev.detour.core.alternatives.Outcome
import dev.detour.core.budget.BudgetSettings
import dev.detour.core.budget.UsageInterval
import dev.detour.core.settings.ChangeKind
import dev.detour.core.settings.ProtectedSettings
import dev.detour.core.settings.SettingsOp
import dev.detour.core.settings.classify
import dev.detour.core.setup.DeviceStepsCatalog
import dev.detour.core.time.AllowedHours
import dev.detour.core.time.DayClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class WeeklyReviewTest {
    private val zone = ZoneId.of("America/Chicago")
    private val clock = DayClock(zone)
    private val end = LocalDate.of(2026, 11, 20)
    private val min = 60_000L

    private fun at(d: LocalDate, h: Int, m: Int = 0) = LocalDateTime.of(d, LocalTime.of(h, m)).atZone(zone).toInstant().toEpochMilli()

    /** Every day: one 20-minute session at 18:00 and one 10-minute session at 20:00. */
    private val intervals = (0..6).flatMap { back ->
        val d = end.minusDays(back.toLong())
        listOf(UsageInterval("yt", at(d, 18), at(d, 18, 20)), UsageInterval("ig", at(d, 20), at(d, 20, 10)))
    }
    private val week = WeeklyReview.days(intervals, clock, end)
    private val now = at(end, 23)

    @Test
    fun weekHasSevenDaysOldestFirst() {
        assertEquals(7, week.size)
        assertEquals(end.minusDays(6), week.first().day)
        assertEquals(end, week.last().day)
        week.forEach {
            assertEquals(30 * min, it.totals.usedMs)
            assertEquals(2, it.totals.opens)
        }
    }

    @Test
    fun suggestionsAreAlwaysTighter() {
        val settings = BudgetSettings(dailyBudgetMinutes = 90, allowedHours = AllowedHours.DEFAULT)
        val s = WeeklyReview.suggestions(settings, week, intervals, clock, now)
        assertTrue(s.isNotEmpty())
        val base = ProtectedSettings(budget = settings)
        s.forEach { assertEquals(it.text, ChangeKind.TIGHTEN, classify(base, it.op.apply(base))) }
    }

    @Test
    fun suggestsLowerBudgetWhenWellUnder() {
        val s = WeeklyReview.suggestions(BudgetSettings(dailyBudgetMinutes = 90, allowedHours = null), week, intervals, clock, now)
        assertTrue(s.any { it.op == SettingsOp.SetDailyBudget(35) }) // 30 * 1.1 = 33 -> 35
    }

    @Test
    fun noBudgetSuggestionWhenCloseToIt() {
        val s = WeeklyReview.suggestions(BudgetSettings(dailyBudgetMinutes = 35, allowedHours = null), week, intervals, clock, now)
        assertFalse(s.any { it.op is SettingsOp.SetDailyBudget })
    }

    @Test
    fun trimsAllowedHoursToActualUse() {
        val s = WeeklyReview.suggestions(BudgetSettings(dailyBudgetMinutes = 35, allowedHours = AllowedHours.DEFAULT), week, intervals, clock, now)
        // Used 18:00-20:10 -> 17:00-22:00 with an hour of slack each side.
        assertTrue(s.any { it.op == SettingsOp.SetAllowedHours(AllowedHours(LocalTime.of(17, 0), LocalTime.of(22, 0))) })
    }

    @Test
    fun noSuggestionsWhileObservingOrWithoutData() {
        val observing = BudgetSettings(observeUntilMs = now + 1)
        assertEquals(emptyList<Suggestion>(), WeeklyReview.suggestions(observing, week, intervals, clock, now))
        assertEquals(emptyList<Suggestion>(), WeeklyReview.suggestions(BudgetSettings(dailyBudgetMinutes = 90), week.take(3), intervals, clock, now))
    }

    @Test
    fun opensCapOnlyWhenNoneSet() {
        val busy = week.map { it.copy(totals = it.totals.copy(opens = 6)) }
        assertTrue(WeeklyReview.suggestions(BudgetSettings(dailyBudgetMinutes = 30), busy, intervals, clock, now).any { it.op == SettingsOp.SetOpensCap(6) })
        assertFalse(WeeklyReview.suggestions(BudgetSettings(dailyBudgetMinutes = 30, opensCap = 8), busy, intervals, clock, now).any { it.op is SettingsOp.SetOpensCap })
    }

    @Test
    fun talliesPerFeeling() {
        val rows = listOf(
            ChoiceRow("Take a walk", Feeling.ANXIOUS, Outcome.BETTER),
            ChoiceRow("Take a walk", Feeling.ANXIOUS, Outcome.BETTER),
            ChoiceRow("Take a walk", Feeling.ANXIOUS, null),
            ChoiceRow("10 slow breaths", Feeling.ANXIOUS, Outcome.SKIPPED),
            ChoiceRow("Read a few pages", Feeling.BORED, Outcome.SAME),
        )
        val t = WeeklyReview.tallies(rows)
        assertEquals(listOf(Feeling.ANXIOUS, Feeling.BORED), t.keys.toList())
        val walk = t.getValue(Feeling.ANXIOUS).first()
        assertEquals("Take a walk", walk.title)
        assertEquals(3, walk.chosen)
        assertEquals(2, walk.outcomes[Outcome.BETTER])
        assertEquals(1, walk.unanswered)
        assertEquals(1, t.getValue(Feeling.ANXIOUS)[1].unanswered) // skipped counts as no answer
    }

    @Test
    fun promptOnceAWeekAfterTheFirstWeek() {
        val week = 7 * 24 * 60 * 60 * 1000L
        assertFalse(WeeklyReview.isDue(firstRunMs = 0, lastViewedMs = 0, nowMs = week - 1))
        assertTrue(WeeklyReview.isDue(firstRunMs = 0, lastViewedMs = 0, nowMs = week))
        assertFalse(WeeklyReview.isDue(firstRunMs = 0, lastViewedMs = week, nowMs = week + 1000))
        assertTrue(WeeklyReview.isDue(firstRunMs = 0, lastViewedMs = week, nowMs = 2 * week))
    }

    @Test
    fun deviceSteps() {
        assertTrue(DeviceStepsCatalog.forManufacturer("Google", "Pixel 8").battery.contains("App battery usage"))
        assertEquals("Pixel 8", DeviceStepsCatalog.forManufacturer("Google", "Pixel 8").deviceName)
        assertTrue(DeviceStepsCatalog.forManufacturer("samsung").battery.contains("Never sleeping apps"))
        assertTrue(DeviceStepsCatalog.forManufacturer("Xiaomi").battery.isNotBlank())
    }
}
