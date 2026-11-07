package dev.detour.core.budget

import dev.detour.core.time.AllowedHours
import dev.detour.core.time.DayClock
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class BudgetTest {
    private val ny = ZoneId.of("America/New_York")
    private val clock = DayClock(ny)
    private val min = 60_000L

    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0, s: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi, s).atZone(ny).toInstant().toEpochMilli()

    private fun iv(pkg: String, start: Long, end: Long) = UsageInterval(pkg, start, end)

    // ---- usage accounting ------------------------------------------------------------------

    @Test
    fun timeIsSharedAcrossApps() {
        val t = ms(2026, 6, 11, 12)
        val ivs = listOf(iv("yt", t, t + 10 * min), iv("ig", t + 20 * min, t + 35 * min))
        assertEquals(25 * min, UsageMath.usedMs(ivs, clock.startOf(LocalDate.of(2026, 6, 11)), t + 60 * min))
    }

    @Test
    fun overlappingIntervalsAreNotDoubleCounted() {
        val ivs = listOf(iv("yt", 0, 10 * min), iv("yt", 5 * min, 12 * min), iv("ig", 11 * min, 13 * min))
        assertEquals(13 * min, UsageMath.usedMs(ivs, 0, 100 * min))
    }

    @Test
    fun intervalSpanning4amIsSplitBetweenDays() {
        // 3:30 -> 4:20 AM: 30 min belong to the old day, 20 min to the new one.
        val ivs = listOf(iv("yt", ms(2026, 6, 11, 3, 30), ms(2026, 6, 11, 4, 20)))
        assertEquals(30 * min, UsageMath.totalsFor(LocalDate.of(2026, 6, 10), clock, ivs).usedMs)
        assertEquals(20 * min, UsageMath.totalsFor(LocalDate.of(2026, 6, 11), clock, ivs).usedMs)
    }

    @Test
    fun midnightDoesNotResetTheBudget() {
        val ivs = listOf(iv("yt", ms(2026, 6, 10, 23, 30), ms(2026, 6, 11, 0, 30)))
        assertEquals(60 * min, UsageMath.totalsFor(LocalDate.of(2026, 6, 10), clock, ivs).usedMs)
        assertEquals(0L, UsageMath.totalsFor(LocalDate.of(2026, 6, 11), clock, ivs).usedMs)
    }

    @Test
    fun accountingAcrossDstFallBack() {
        // 01:00 EDT -> 01:00 EST (the repeated hour) = 2 real hours, all on Oct 31's Detour day.
        val start = LocalDateTime.of(2026, 11, 1, 1, 0).atZone(ny).withEarlierOffsetAtOverlap().toInstant().toEpochMilli()
        val end = LocalDateTime.of(2026, 11, 1, 1, 0).atZone(ny).withLaterOffsetAtOverlap().toInstant().toEpochMilli()
        val ivs = listOf(iv("yt", start, end))
        assertEquals(60 * min, UsageMath.totalsFor(LocalDate.of(2026, 10, 31), clock, ivs).usedMs)
    }

    // ---- sessions / opens ------------------------------------------------------------------

    @Test
    fun shortTripAwayIsTheSameSession() {
        val ivs = listOf(iv("yt", 0, 60_000), iv("yt", 60_000 + 29_000, 200_000))
        assertEquals(1, UsageMath.opens(ivs, 0, Long.MAX_VALUE))
    }

    @Test
    fun moreThan30sAwayIsANewOpen() {
        val ivs = listOf(iv("yt", 0, 60_000), iv("yt", 60_000 + 31_000, 200_000))
        assertEquals(2, UsageMath.opens(ivs, 0, Long.MAX_VALUE))
    }

    @Test
    fun opensArePerApp() {
        val ivs = listOf(iv("yt", 0, 10_000), iv("ig", 11_000, 20_000), iv("yt", 21_000, 30_000))
        // yt resumed 11 s after it left: same yt session. ig is its own open.
        assertEquals(2, UsageMath.opens(ivs, 0, Long.MAX_VALUE))
    }

    @Test
    fun opensCountedOnTheDayTheSessionStarted() {
        val ivs = listOf(
            iv("yt", ms(2026, 6, 11, 3, 55), ms(2026, 6, 11, 4, 5)),        // started before 4 AM
            iv("yt", ms(2026, 6, 11, 4, 5, 10), ms(2026, 6, 11, 4, 10)),    // 10 s later: same session
            iv("yt", ms(2026, 6, 11, 9), ms(2026, 6, 11, 9, 5)),            // new open
        )
        assertEquals(1, UsageMath.totalsFor(LocalDate.of(2026, 6, 10), clock, ivs).opens)
        assertEquals(1, UsageMath.totalsFor(LocalDate.of(2026, 6, 11), clock, ivs).opens)
    }

    // ---- gate -------------------------------------------------------------------------------

    private val settings = BudgetSettings(dailyBudgetMinutes = 60, allowedHours = AllowedHours.DEFAULT)
    private fun input(time: LocalTime = LocalTime.NOON, usedMin: Int = 0, opensBefore: Int = 0, permitted: Boolean = false) =
        GateInput(nowMs = 1_000, localTime = time, usedMs = usedMin * min, opensBefore = opensBefore, sessionPermitted = permitted)

    @Test
    fun newSessionWithBudgetPauses() {
        assertEquals(GateDecision.PAUSE, DistractingGate.decide(settings, input()))
    }

    @Test
    fun permittedSessionIsAllowed() {
        assertEquals(GateDecision.ALLOW, DistractingGate.decide(settings, input(permitted = true)))
    }

    @Test
    fun budgetExhaustedEndsEvenAPermittedSession() {
        assertEquals(GateDecision.OUT_OF_BUDGET, DistractingGate.decide(settings, input(usedMin = 60, permitted = true)))
    }

    @Test
    fun outsideHoursBlocks() {
        assertEquals(GateDecision.OUTSIDE_HOURS, DistractingGate.decide(settings, input(time = LocalTime.of(23, 30))))
        assertEquals(GateDecision.OUTSIDE_HOURS, DistractingGate.decide(settings, input(time = LocalTime.of(7, 0), permitted = true)))
        assertEquals(GateDecision.PAUSE, DistractingGate.decide(settings.copy(allowedHours = null), input(time = LocalTime.of(23, 30))))
    }

    @Test
    fun opensCapOnlyAffectsNewSessions() {
        val capped = settings.copy(opensCap = 5)
        assertEquals(GateDecision.PAUSE, DistractingGate.decide(capped, input(opensBefore = 4)))
        assertEquals(GateDecision.OPENS_CAP, DistractingGate.decide(capped, input(opensBefore = 5)))
        assertEquals(GateDecision.ALLOW, DistractingGate.decide(capped, input(opensBefore = 5, permitted = true)))
    }

    @Test
    fun observingAllowsEverything() {
        val observing = settings.copy(observeUntilMs = 2_000, dailyBudgetMinutes = null)
        assertEquals(GateDecision.ALLOW, DistractingGate.decide(observing, input(time = LocalTime.of(2, 0), usedMin = 500)))
        assertEquals(GateDecision.PAUSE, DistractingGate.decide(observing.copy(observeUntilMs = 1_000, dailyBudgetMinutes = 60), input()))
    }

    @Test
    fun waitsEscalateAndCap() {
        val s = BudgetSettings()
        assertEquals(listOf(5, 15, 30, 60, 60, 60), (0..5).map { s.waitFor(it) })
        assertEquals(0, s.copy(waitsSeconds = emptyList()).waitFor(3))
    }

    @Test
    fun remaining() {
        assertEquals(5 * min, DistractingGate.remainingMs(settings, 55 * min))
        assertEquals(0L, DistractingGate.remainingMs(settings, 70 * min))
    }

    // ---- baseline ---------------------------------------------------------------------------

    @Test
    fun baselineSuggestsSlightlyBelowAverage() {
        val days = listOf(80, 70, 90, 60, 100, 75, 85).map { DayTotals(it * min, 15) }
        val s = BaselineSuggester.suggest(days)
        assertEquals(80, s.averageMinutes)
        assertEquals(15, s.averageOpens)
        assertEquals(65, s.suggestedBudgetMinutes) // 80 * 0.85 = 68 -> rounded down to 65
    }

    @Test
    fun baselineHasAFloor() {
        assertEquals(10, BaselineSuggester.suggest(listOf(DayTotals(3 * min, 1))).suggestedBudgetMinutes)
        assertEquals(10, BaselineSuggester.suggest(emptyList()).suggestedBudgetMinutes)
    }
}
