package dev.detour.guard.data

import dev.detour.core.budget.UsageInterval
import dev.detour.core.budget.UsageMath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

/** Today's Distracting usage so far. */
data class TodayUsage(
    val day: LocalDate,
    val usedMs: Long,
    /** Opens today, including the current session. */
    val opens: Int,
    /** Opens today before the current session (drives the escalating wait and the opens cap). */
    val opensBefore: Int,
) {
    val usedMinutes: Int get() = (usedMs / 60_000).toInt()
}

/**
 * Records foreground time of Distracting apps and keeps today's totals in memory.
 * Driven by DetectorService on the main thread; the open interval is written to the database
 * when it ends and every [CHECKPOINT_MS] meanwhile, so a crash loses at most that much.
 */
class UsageTracker(private val dao: UsageDao, private val settings: SettingsStore) {
    private data class Open(val id: Long, val packageName: String, val startMs: Long, var endMs: Long, var savedAtMs: Long)

    private var loadedDay: LocalDate? = null
    private var closed: List<UsageInterval> = emptyList()
    private var open: Open? = null

    private val _today = MutableStateFlow<TodayUsage?>(null)
    val today: StateFlow<TodayUsage?> = _today.asStateFlow()

    /** Call every tick with the Distracting app in front (or null). Returns the fresh totals. */
    suspend fun update(distractingPackage: String?, nowMs: Long): TodayUsage {
        val clock = settings.clock()
        val day = clock.dayOf(nowMs)
        if (day != loadedDay) {
            open?.let { save(it.apply { endMs = nowMs }) }
            // Load a minute before the day starts too, so a session continuing across 4 AM
            // isn't counted as a new open.
            val fromMs = clock.startOf(day) - UsageMath.NEW_SESSION_GAP_MS * 2
            closed = dao.since(fromMs).filter { it.id != open?.id }.map { it.toModel() }
            loadedDay = day
        }

        val cur = open
        if (cur != null && cur.packageName != distractingPackage) {
            cur.endMs = nowMs
            save(cur)
            closed = closed + UsageInterval(cur.packageName, cur.startMs, cur.endMs)
            open = null
        }
        if (distractingPackage != null) {
            val o = open ?: run {
                val id = dao.insert(UsageIntervalEntity(packageName = distractingPackage, startMs = nowMs, endMs = nowMs))
                Open(id, distractingPackage, nowMs, nowMs, nowMs).also { open = it }
            }
            o.endMs = nowMs
            if (nowMs - o.savedAtMs >= CHECKPOINT_MS) save(o)
        }

        return compute(nowMs).also { _today.value = it }
    }

    /** Closes the open interval (screen off, service stopping). */
    suspend fun closeOpen(nowMs: Long) {
        update(null, nowMs)
    }

    /** Intervals since [fromMs] (for the observe-week baseline). */
    suspend fun intervalsSince(fromMs: Long): List<UsageInterval> = dao.since(fromMs).map { it.toModel() }

    private fun compute(nowMs: Long): TodayUsage {
        val clock = settings.clock()
        val day = clock.dayOf(nowMs)
        val from = clock.startOf(day)
        val o = open
        val all = if (o != null) closed + UsageInterval(o.packageName, o.startMs, maxOf(o.endMs, o.startMs)) else closed
        val starts = UsageMath.sessionStarts(all).filter { it.startMs in from..nowMs }
        val currentStart = o?.let { cur ->
            UsageMath.sessionStarts(all).filter { it.packageName == cur.packageName && it.startMs <= cur.startMs }
                .maxByOrNull { it.startMs }?.startMs
        }
        val opensBefore = if (currentStart == null) starts.size else starts.count { it.startMs < currentStart }
        return TodayUsage(day, UsageMath.usedMs(all, from, nowMs + 1), starts.size, opensBefore)
    }

    private suspend fun save(o: Open) {
        dao.update(UsageIntervalEntity(o.id, o.packageName, o.startMs, o.endMs))
        o.savedAtMs = o.endMs
    }

    private fun UsageIntervalEntity.toModel() = UsageInterval(packageName, startMs, maxOf(startMs, endMs))

    companion object {
        private const val CHECKPOINT_MS = 30_000L
    }
}

/**
 * The Distracting session you've gone through the pause for. Shared between the detector
 * (which checks it) and the intervention screen (which grants it). Main thread only.
 */
class SessionState {
    var permittedPackage: String? = null
        private set
    private var lastSeenMs = 0L

    /** When to ask "Still doing what you came for?". */
    var checkInDueMs = Long.MAX_VALUE

    /** The day the 5-minutes-left warning was last shown. */
    var warnedDay: LocalDate? = null

    fun permit(packageName: String, nowMs: Long, checkInMinutes: Int) {
        permittedPackage = packageName
        lastSeenMs = nowMs
        checkInDueMs = nowMs + checkInMinutes * 60_000L
    }

    /** The session lasts while the app is in front, or away for no more than 30 s. */
    fun isPermitted(packageName: String, nowMs: Long): Boolean =
        packageName == permittedPackage && nowMs - lastSeenMs <= UsageMath.NEW_SESSION_GAP_MS

    fun touch(foregroundPackage: String?, nowMs: Long) {
        if (foregroundPackage != null && foregroundPackage == permittedPackage) lastSeenMs = nowMs
    }

    fun revoke() {
        permittedPackage = null
        checkInDueMs = Long.MAX_VALUE
    }
}
