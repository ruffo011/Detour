package dev.detour.guard.guardrail

import android.content.Context
import dev.detour.core.settings.GuardTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The screens where you'd go to turn protection off, and how to open each one for calibration. */
enum class GuardScreen(val label: String, val hint: String? = null) {
    GUARD_APP_INFO("Detour's App info page"),
    FILTER_APP_INFO("Detour Filter's App info page"),
    USAGE_ACCESS("Usage access settings"),
    OVERLAY("Display over other apps settings"),
    VPN("VPN settings"),
    PRIVATE_DNS("Private DNS (Network & internet)", "Guarding this also pauses the Network & internet page, which has Wi-Fi."),
    BATTERY("Battery optimization settings"),
    CHROME_SETTINGS("Chrome's settings", "Open Chrome yourself, tap ⋮ → Settings, then come back."),
}

/**
 * The Settings guard: which screens get a 60-second pause, and whether it's on.
 * Changes go through the 24-hour queue like every other protected setting.
 */
class SettingsGuardStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings_guard", Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean(K_ENABLED, true))
    private val _targets = MutableStateFlow(loadTargets())
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    val targets: StateFlow<Set<GuardTarget>> = _targets.asStateFlow()

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(K_ENABLED, on).apply()
        _enabled.value = on
    }

    fun add(target: GuardTarget) = saveTargets(_targets.value + target)
    fun remove(target: GuardTarget) = saveTargets(_targets.value - target)

    /** True if this screen type has a target recorded by calibration on this phone. */
    fun isCalibrated(screen: GuardScreen): Boolean =
        _targets.value.any { it.key == screen.name && it !in DEFAULT_TARGETS }

    private fun loadTargets(): Set<GuardTarget> {
        val saved = prefs.getStringSet(K_TARGETS, null) ?: return DEFAULT_TARGETS
        return saved.mapNotNull { s ->
            val p = s.split('|')
            if (p.size == 3) GuardTarget(p[0], p[1], p[2]) else null
        }.toSet()
    }

    private fun saveTargets(t: Set<GuardTarget>) {
        prefs.edit().putStringSet(K_TARGETS, t.map { "${it.key}|${it.packageName}|${it.className}" }.toSet()).apply()
        _targets.value = t
    }

    companion object {
        private const val K_ENABLED = "enabled"
        private const val K_TARGETS = "targets"
        private const val SETTINGS = "com.android.settings"

        /**
         * Known AOSP / Pixel and Samsung activity names, used until calibration records the
         * real ones on this phone. App info pages vary too much to ship a default.
         */
        val DEFAULT_TARGETS: Set<GuardTarget> = setOf(
            GuardTarget(GuardScreen.USAGE_ACCESS.name, SETTINGS, "$SETTINGS.Settings\$UsageAccessSettingsActivity"),
            GuardTarget(GuardScreen.USAGE_ACCESS.name, SETTINGS, "$SETTINGS.Settings\$AppUsageAccessSettingsActivity"),
            GuardTarget(GuardScreen.OVERLAY.name, SETTINGS, "$SETTINGS.Settings\$OverlaySettingsActivity"),
            GuardTarget(GuardScreen.OVERLAY.name, SETTINGS, "$SETTINGS.Settings\$AppDrawOverlaySettingsActivity"),
            GuardTarget(GuardScreen.VPN.name, SETTINGS, "$SETTINGS.Settings\$VpnSettingsActivity"),
            GuardTarget(GuardScreen.BATTERY.name, SETTINGS, "$SETTINGS.Settings\$HighPowerApplicationsActivity"),
            GuardTarget(GuardScreen.CHROME_SETTINGS.name, "com.android.chrome", "org.chromium.chrome.browser.settings.SettingsActivity"),
            // Samsung One UI uses the same Settings$... names for these pages.
        )
    }
}
