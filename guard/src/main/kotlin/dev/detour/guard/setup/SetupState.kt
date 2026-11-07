package dev.detour.guard.setup

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Onboarding progress, the setup window, and the weekly-review prompt.
 *
 * The setup window is the first [SETUP_WINDOW_MS] after Detour first starts: while you're doing
 * the initial setup, every change applies immediately (even loosening ones), and the Settings
 * guard stays quiet. After that, the usual rules apply.
 */
class SetupState(context: Context) {
    private val prefs = context.getSharedPreferences("setup", Context.MODE_PRIVATE)

    val firstRunMs: Long = prefs.getLong(K_FIRST_RUN, 0).takeIf { it > 0 }
        ?: System.currentTimeMillis().also { prefs.edit().putLong(K_FIRST_RUN, it).apply() }

    private val _completed = MutableStateFlow(prefs.getBoolean(K_COMPLETED, false))
    val completed: StateFlow<Boolean> = _completed.asStateFlow()

    private val _checked = MutableStateFlow(prefs.getStringSet(K_CHECKED, emptySet())!!.toSet())

    /** Manually ticked items (things Detour can't detect, like Always-on VPN or Chrome's secure DNS). */
    val checked: StateFlow<Set<String>> = _checked.asStateFlow()

    private val _reviewViewedMs = MutableStateFlow(prefs.getLong(K_REVIEW_VIEWED, 0))
    val reviewViewedMs: StateFlow<Long> = _reviewViewedMs.asStateFlow()

    fun isSetupWindowOpen(nowMs: Long = System.currentTimeMillis()) = nowMs - firstRunMs < SETUP_WINDOW_MS

    fun setupWindowMinutesLeft(nowMs: Long = System.currentTimeMillis()) =
        ((firstRunMs + SETUP_WINDOW_MS - nowMs) / 60_000).coerceAtLeast(0)

    fun markCompleted() {
        prefs.edit().putBoolean(K_COMPLETED, true).apply()
        _completed.value = true
    }

    fun toggle(key: String) {
        val next = if (key in _checked.value) _checked.value - key else _checked.value + key
        prefs.edit().putStringSet(K_CHECKED, next).apply()
        _checked.value = next
    }

    fun markReviewViewed(nowMs: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(K_REVIEW_VIEWED, nowMs).apply()
        _reviewViewedMs.value = nowMs
    }

    companion object {
        const val SETUP_WINDOW_MS = 60 * 60_000L
        private const val K_FIRST_RUN = "first_run"
        private const val K_COMPLETED = "completed"
        private const val K_CHECKED = "checked"
        private const val K_REVIEW_VIEWED = "review_viewed"

        const val CHECK_ALWAYS_ON = "always_on_vpn"
        const val CHECK_CHROME_DNS = "chrome_secure_dns_off"
        const val CHECK_ALTERNATIVES = "alternatives_reviewed"
    }
}
