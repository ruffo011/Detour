package dev.detour.core.budget

import dev.detour.core.time.DayClock
import java.time.LocalDate

/** A stretch of time one Distracting app was in the foreground. */
data class UsageInterval(val packageName: String, val startMs: Long, val endMs: Long) {
    init {
        require(endMs >= startMs) { "interval ends before it starts" }
    }
}

data class DayTotals(val usedMs: Long, val opens: Int) {
    val usedMinutes: Double get() = usedMs / 60_000.0
}

/**
 * Pure calculations over usage intervals. All Distracting apps share one budget, so time is
 * summed across apps.
 */
object UsageMath {
    /** An app resumed after more than this long away counts as a new open. */
    const val NEW_SESSION_GAP_MS = 30_000L

    /** Total foreground time within [fromMs, toMs). Overlaps are merged, never double-counted. */
    fun usedMs(intervals: List<UsageInterval>, fromMs: Long, toMs: Long): Long {
        val clipped = intervals.mapNotNull {
            val s = maxOf(it.startMs, fromMs)
            val e = minOf(it.endMs, toMs)
            if (e > s) s to e else null
        }.sortedBy { it.first }
        var total = 0L
        var curStart = Long.MIN_VALUE
        var curEnd = Long.MIN_VALUE
        for ((s, e) in clipped) {
            if (s > curEnd) {
                if (curEnd > curStart) total += curEnd - curStart
                curStart = s
                curEnd = e
            } else if (e > curEnd) {
                curEnd = e
            }
        }
        if (curEnd > curStart) total += curEnd - curStart
        return total
    }

    /**
     * Start times of sessions: an interval starts a new session unless the same app was in the
     * foreground less than [gapMs] before it (e.g. a quick trip to the notification shade).
     */
    fun sessionStarts(intervals: List<UsageInterval>, gapMs: Long = NEW_SESSION_GAP_MS): List<UsageInterval> {
        val starts = ArrayList<UsageInterval>()
        intervals.groupBy { it.packageName }.values.forEach { perApp ->
            var lastEnd = Long.MIN_VALUE
            for (iv in perApp.sortedBy { it.startMs }) {
                if (lastEnd == Long.MIN_VALUE || iv.startMs - lastEnd > gapMs) starts += iv
                lastEnd = maxOf(lastEnd, iv.endMs)
            }
        }
        return starts.sortedBy { it.startMs }
    }

    /** Number of opens (session starts) within [fromMs, toMs). */
    fun opens(intervals: List<UsageInterval>, fromMs: Long, toMs: Long, gapMs: Long = NEW_SESSION_GAP_MS): Int =
        sessionStarts(intervals, gapMs).count { it.startMs in fromMs until toMs }

    fun totalsFor(day: LocalDate, clock: DayClock, intervals: List<UsageInterval>): DayTotals {
        val from = clock.startOf(day)
        val to = clock.endOf(day)
        return DayTotals(usedMs(intervals, from, to), opens(intervals, from, to))
    }
}
