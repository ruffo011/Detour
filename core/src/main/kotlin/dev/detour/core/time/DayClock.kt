package dev.detour.core.time

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Splits time into "Detour days" that start at [resetTime] (default 4:00 AM) in [zone], so
 * late-night use belongs to the previous day instead of getting a fresh budget at midnight.
 *
 * Create a new one per calculation with the current [ZoneId.systemDefault] so a timezone change
 * takes effect immediately. DST is handled by java.time: a day containing a clock change is
 * simply 23 or 25 hours long, and a reset time that falls into a skipped hour moves forward.
 */
class DayClock(
    val zone: ZoneId,
    val resetTime: LocalTime = DEFAULT_RESET,
) {
    /** The Detour day that [epochMs] belongs to. */
    fun dayOf(epochMs: Long): LocalDate {
        val local = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDateTime()
        return if (local.toLocalTime() < resetTime) local.toLocalDate().minusDays(1) else local.toLocalDate()
    }

    /** When [day] starts, in epoch milliseconds. */
    fun startOf(day: LocalDate): Long = ZonedDateTime.of(day, resetTime, zone).toInstant().toEpochMilli()

    /** When [day] ends (= when the next day starts). */
    fun endOf(day: LocalDate): Long = startOf(day.plusDays(1))

    fun startOfDayContaining(epochMs: Long): Long = startOf(dayOf(epochMs))

    fun localTimeAt(epochMs: Long): LocalTime = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalTime()

    companion object {
        val DEFAULT_RESET: LocalTime = LocalTime.of(4, 0)
    }
}
