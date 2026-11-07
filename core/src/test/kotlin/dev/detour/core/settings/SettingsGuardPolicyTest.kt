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
        assertEquals("$settings.Settings\$VpnSettingsActivity", Calibration.pick(screens, 100)!!.className)
        assertNull(Calibration.pick(screens, 200))
    }
}
