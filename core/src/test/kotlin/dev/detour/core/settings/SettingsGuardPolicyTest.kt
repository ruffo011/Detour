package dev.detour.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsGuardPolicyTest {
    private val settings = "com.android.settings"
    private val vpn = GuardTarget("VPN", settings, "$settings.Settings\$VpnSettingsActivity")
    private val targets = setOf(vpn)
    private val p = SettingsGuardPolicy(permitMs = 300_000)

    @Test
    fun pausesOnlyGuardedScreens() {
        assertTrue(p.shouldPause(settings, vpn.className, true, targets, 0))
        assertFalse(p.shouldPause(settings, "$settings.Settings\$WifiSettingsActivity", true, targets, 0))
        assertFalse(p.shouldPause("com.other", vpn.className, true, targets, 0))
        assertFalse(p.shouldPause(settings, null, true, targets, 0))
    }

    @Test
    fun offMeansNoPause() = assertFalse(p.shouldPause(settings, vpn.className, false, targets, 0))

    @Test
    fun afterWaitingThatAppPassesForFiveMinutes() {
        p.permit(settings, 1_000)
        assertFalse(p.shouldPause(settings, vpn.className, true, targets, 2_000))
        assertFalse(p.shouldPause(settings, vpn.className, true, targets, 300_999))
        assertTrue(p.shouldPause(settings, vpn.className, true, targets, 301_000))
    }

    @Test
    fun calibrationAndSetupGraceSuppress() {
        assertFalse(p.shouldPause(settings, vpn.className, true, targets, 0, suppressedKeys = setOf("VPN")))
        assertTrue(p.shouldPause(settings, vpn.className, true, targets, 0, suppressedKeys = setOf("OTHER")))
        assertFalse(p.shouldPause(settings, vpn.className, true, targets, 0, inSetupGrace = true))
    }

    @Test
    fun calibrationPicksFirstRealScreenAfterStart() {
        val screens = listOf(
            Calibration.Screen(settings, "Old", 50),
            Calibration.Screen("dev.detour.guard", "Main", 100),
            Calibration.Screen("com.android.systemui", "Shade", 110),
            Calibration.Screen(settings, "$settings.Settings\$VpnSettingsActivity", 120),
            Calibration.Screen(settings, "$settings.SubSettings", 130),
        )
        assertEquals("$settings.Settings\$VpnSettingsActivity", Calibration.pick(screens, 100, "VPN")!!.className)
        assertNull(Calibration.pick(screens, 200, "VPN"))
    }

    private val chromeMain = "org.chromium.chrome.browser.ChromeTabbedActivity"
    private val chromeSettings = "org.chromium.chrome.browser.settings.SettingsActivity"

    @Test
    fun chromeCalibrationSkipsTheBrowsingScreen() {
        // Tapping "Open" launches Chrome's main screen first; only its Settings screen may be recorded.
        val screens = listOf(
            Calibration.Screen("com.android.chrome", chromeMain, 100),
            Calibration.Screen("com.android.chrome", chromeSettings, 150),
        )
        assertEquals(chromeSettings, Calibration.pick(screens, 100, Calibration.CHROME_SETTINGS_KEY)!!.className)
        // Opened Chrome but never went into Settings: nothing is recorded.
        assertNull(Calibration.pick(screens.take(1), 100, Calibration.CHROME_SETTINGS_KEY))
    }

    @Test
    fun invalidTargetsAreRejected() {
        assertFalse(Calibration.isValid(GuardTarget(Calibration.CHROME_SETTINGS_KEY, "com.android.chrome", chromeMain)))
        assertTrue(Calibration.isValid(GuardTarget(Calibration.CHROME_SETTINGS_KEY, "com.android.chrome", chromeSettings)))
        assertTrue(Calibration.isValid(GuardTarget("VPN", settings, "$settings.Settings\$VpnSettingsActivity")))
        assertTrue(Calibration.isValid(GuardTarget("OVERLAY", "com.google.android.permissioncontroller", "x.Y")))
        // A system-page calibration that wandered into another app must not guard that app.
        assertFalse(Calibration.isValid(GuardTarget("VPN", "com.android.chrome", chromeMain)))
        assertFalse(Calibration.isValid(GuardTarget("GUARD_APP_INFO", "com.google.android.youtube", "Main")))
    }

    @Test
    fun aGuardedChromeMainScreenWouldPauseEveryOpen() {
        // The bug this prevents: with Chrome's main screen as a target, every open of Chrome paused.
        val bad = setOf(GuardTarget(Calibration.CHROME_SETTINGS_KEY, "com.android.chrome", chromeMain))
        assertTrue(p.shouldPause("com.android.chrome", chromeMain, true, bad, 0))
        val cleaned = bad.filter { Calibration.isValid(it) }.toSet()
        assertFalse(p.shouldPause("com.android.chrome", chromeMain, true, cleaned, 0))
    }
}
