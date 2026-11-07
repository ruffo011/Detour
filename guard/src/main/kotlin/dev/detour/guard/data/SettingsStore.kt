package dev.detour.guard.data

import android.content.Context
import dev.detour.core.budget.BaselineSuggestion
import dev.detour.core.budget.BudgetSettings
import dev.detour.core.time.AllowedHours
import dev.detour.core.time.DayClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalTime
import java.time.ZoneId

/**
 * Budget settings, kept in SharedPreferences. Milestone 5 routes loosening changes through the
 * 24-hour queue; until then [update] applies everything immediately.
 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("budget_settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<BudgetSettings> = _settings.asStateFlow()

    val current: BudgetSettings get() = _settings.value

    /** Today's clock, using the current timezone and your reset time. */
    fun clock(): DayClock = DayClock(ZoneId.systemDefault(), current.resetTime)

    fun update(transform: (BudgetSettings) -> BudgetSettings) {
        val next = transform(_settings.value)
        save(next)
        _settings.value = next
    }

    /** Starts the 7-day observe period the first time Detour runs with this feature. */
    fun startObservingIfNew(nowMs: Long) {
        if (prefs.contains(K_OBSERVE_UNTIL) || prefs.getBoolean(K_OBSERVE_DONE, false)) return
        val clock = clock()
        val until = clock.startOf(clock.dayOf(nowMs).plusDays(BudgetSettings.OBSERVE_DAYS))
        update { it.copy(observeUntilMs = until) }
    }

    private fun load(): BudgetSettings {
        val defaults = BudgetSettings()
        val hoursOn = prefs.getBoolean(K_HOURS_ON, true)
        return BudgetSettings(
            dailyBudgetMinutes = prefs.getInt(K_BUDGET, -1).takeIf { it >= 0 },
            opensCap = prefs.getInt(K_OPENS_CAP, -1).takeIf { it >= 0 },
            allowedHours = if (!hoursOn) null else AllowedHours(
                LocalTime.ofSecondOfDay(prefs.getInt(K_HOURS_START, 9 * 3600).toLong()),
                LocalTime.ofSecondOfDay(prefs.getInt(K_HOURS_END, 23 * 3600).toLong()),
            ),
            resetTime = LocalTime.ofSecondOfDay(prefs.getInt(K_RESET, DayClock.DEFAULT_RESET.toSecondOfDay()).toLong()),
            waitsSeconds = prefs.getString(K_WAITS, null)
                ?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.filter { it >= 0 }
                ?.takeIf { it.isNotEmpty() } ?: defaults.waitsSeconds,
            sessionCheckInMinutes = prefs.getInt(K_CHECKIN, defaults.sessionCheckInMinutes),
            observeUntilMs = prefs.getLong(K_OBSERVE_UNTIL, -1).takeIf { it >= 0 },
            budgetConfirmed = prefs.getBoolean(K_CONFIRMED, false),
            reopenAfterSeconds = prefs.getInt(K_REOPEN, defaults.reopenAfterSeconds),
        )
    }

    private fun save(s: BudgetSettings) {
        prefs.edit().apply {
            putInt(K_BUDGET, s.dailyBudgetMinutes ?: -1)
            putInt(K_OPENS_CAP, s.opensCap ?: -1)
            putBoolean(K_HOURS_ON, s.allowedHours != null)
            s.allowedHours?.let {
                putInt(K_HOURS_START, it.start.toSecondOfDay())
                putInt(K_HOURS_END, it.end.toSecondOfDay())
            }
            putInt(K_RESET, s.resetTime.toSecondOfDay())
            putString(K_WAITS, s.waitsSeconds.joinToString(","))
            putInt(K_CHECKIN, s.sessionCheckInMinutes)
            if (s.observeUntilMs != null) {
                putLong(K_OBSERVE_UNTIL, s.observeUntilMs!!)
            } else {
                remove(K_OBSERVE_UNTIL)
                if (prefs.contains(K_OBSERVE_UNTIL)) putBoolean(K_OBSERVE_DONE, true)
            }
            putBoolean(K_CONFIRMED, s.budgetConfirmed)
            putInt(K_REOPEN, s.reopenAfterSeconds)
        }.apply()
    }

    /** Marks observing as finished (so it never restarts) and applies the suggested budget. */
    fun finishObserving(baseline: BaselineSuggestion) {
        prefs.edit()
            .putBoolean(K_OBSERVE_DONE, true)
            .putInt(K_BASE_MIN, baseline.averageMinutes)
            .putInt(K_BASE_OPENS, baseline.averageOpens)
            .putInt(K_BASE_SUGGESTED, baseline.suggestedBudgetMinutes)
            .apply()
        update {
            it.copy(observeUntilMs = null, dailyBudgetMinutes = it.dailyBudgetMinutes ?: baseline.suggestedBudgetMinutes)
        }
    }

    /** What the observe week measured, once it has ended. */
    fun baseline(): BaselineSuggestion? {
        if (!prefs.contains(K_BASE_SUGGESTED)) return null
        return BaselineSuggestion(prefs.getInt(K_BASE_MIN, 0), prefs.getInt(K_BASE_OPENS, 0), prefs.getInt(K_BASE_SUGGESTED, 0))
    }

    companion object {
        private const val K_BUDGET = "budget_minutes"
        private const val K_OPENS_CAP = "opens_cap"
        private const val K_HOURS_ON = "hours_on"
        private const val K_HOURS_START = "hours_start"
        private const val K_HOURS_END = "hours_end"
        private const val K_RESET = "reset_time"
        private const val K_WAITS = "waits"
        private const val K_CHECKIN = "checkin_minutes"
        private const val K_OBSERVE_UNTIL = "observe_until"
        private const val K_OBSERVE_DONE = "observe_done"
        private const val K_CONFIRMED = "budget_confirmed"
        private const val K_REOPEN = "reopen_after_seconds"
        private const val K_BASE_MIN = "baseline_minutes"
        private const val K_BASE_OPENS = "baseline_opens"
        private const val K_BASE_SUGGESTED = "baseline_suggested"
    }
}
