package dev.detour.core.time

import java.time.LocalTime

/**
 * The window in which the Distracting budget may be spent, e.g. 9:00–23:00.
 * [end] is exclusive. A window that ends before it starts wraps past midnight (22:00–02:00).
 * Equal start and end means "all day".
 */
data class AllowedHours(val start: LocalTime, val end: LocalTime) {
    fun contains(time: LocalTime): Boolean = when {
        start == end -> true
        start < end -> time >= start && time < end
        else -> time >= start || time < end
    }

    /** Minutes covered per day, used to compare windows (smaller = stricter). */
    val lengthMinutes: Int
        get() {
            val s = start.toSecondOfDay() / 60
            val e = end.toSecondOfDay() / 60
            return when {
                s == e -> 24 * 60
                s < e -> e - s
                else -> 24 * 60 - s + e
            }
        }

    companion object {
        val DEFAULT = AllowedHours(LocalTime.of(9, 0), LocalTime.of(23, 0))
    }
}
