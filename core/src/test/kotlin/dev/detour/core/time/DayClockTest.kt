package dev.detour.core.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class DayClockTest {
    private val ny = ZoneId.of("America/New_York")
    private val clock = DayClock(ny)

    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0, zone: ZoneId = ny) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun lateNightBelongsToPreviousDay() {
        assertEquals(LocalDate.of(2026, 6, 10), clock.dayOf(ms(2026, 6, 11, 1, 30)))
        assertEquals(LocalDate.of(2026, 6, 10), clock.dayOf(ms(2026, 6, 11, 3, 59)))
        assertEquals(LocalDate.of(2026, 6, 11), clock.dayOf(ms(2026, 6, 11, 4, 0)))
        assertEquals(LocalDate.of(2026, 6, 11), clock.dayOf(ms(2026, 6, 11, 23, 59)))
    }

    @Test
    fun dayStartsAtResetTime() {
        assertEquals(ms(2026, 6, 11, 4), clock.startOf(LocalDate.of(2026, 6, 11)))
        assertEquals(ms(2026, 6, 12, 4), clock.endOf(LocalDate.of(2026, 6, 11)))
    }

    @Test
    fun springForwardDayIs23Hours() {
        // DST starts 2026-03-08 02:00 in New York.
        val day = LocalDate.of(2026, 3, 7)
        assertEquals(23L * 3_600_000, clock.endOf(day) - clock.startOf(day))
    }

    @Test
    fun fallBackDayIs25Hours() {
        // DST ends 2026-11-01 02:00 in New York.
        val day = LocalDate.of(2026, 10, 31)
        assertEquals(25L * 3_600_000, clock.endOf(day) - clock.startOf(day))
        // 1:30 AM happens twice that night; both belong to Oct 31.
        assertEquals(day, clock.dayOf(ms(2026, 11, 1, 1, 30)))
    }

    @Test
    fun resetTimeInsideSkippedHourMovesForward() {
        val c = DayClock(ny, LocalTime.of(2, 30))
        // 02:30 doesn't exist on 2026-03-08; the day starts at 03:30 EDT instead.
        assertEquals(ms(2026, 3, 8, 3, 30), c.startOf(LocalDate.of(2026, 3, 8)))
    }

    @Test
    fun timezoneChangeUsesTheNewZone() {
        val instant = ms(2026, 6, 11, 5, 0) // 05:00 in New York = 02:00 in Los Angeles
        assertEquals(LocalDate.of(2026, 6, 11), DayClock(ny).dayOf(instant))
        assertEquals(LocalDate.of(2026, 6, 10), DayClock(ZoneId.of("America/Los_Angeles")).dayOf(instant))
    }

    @Test
    fun allowedHours() {
        val h = AllowedHours.DEFAULT // 9:00-23:00
        assertFalse(h.contains(LocalTime.of(8, 59)))
        assertTrue(h.contains(LocalTime.of(9, 0)))
        assertTrue(h.contains(LocalTime.of(22, 59)))
        assertFalse(h.contains(LocalTime.of(23, 0)))
        assertEquals(14 * 60, h.lengthMinutes)

        val wrap = AllowedHours(LocalTime.of(22, 0), LocalTime.of(2, 0))
        assertTrue(wrap.contains(LocalTime.of(23, 0)))
        assertTrue(wrap.contains(LocalTime.of(1, 0)))
        assertFalse(wrap.contains(LocalTime.of(12, 0)))
        assertEquals(240, wrap.lengthMinutes)

        assertTrue(AllowedHours(LocalTime.NOON, LocalTime.NOON).contains(LocalTime.MIDNIGHT))
    }
}
