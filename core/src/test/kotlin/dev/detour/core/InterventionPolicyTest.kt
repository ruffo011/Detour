package dev.detour.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterventionPolicyTest {
    @Test
    fun blockedArrivalTriggersImmediately() {
        val p = InterventionPolicy()
        assertTrue(p.shouldIntervene("fb", needsIntervention = true, nowMs = 0))
    }

    @Test
    fun staysQuietWhileBlockedAppRemainsInFrontUntilRetry() {
        val p = InterventionPolicy(retryAfterMs = 1_500)
        assertTrue(p.shouldIntervene("fb", true, 0))
        assertFalse(p.shouldIntervene("fb", true, 750))
        assertFalse(p.shouldIntervene("fb", true, 1_499))
        assertTrue(p.shouldIntervene("fb", true, 1_500))   // launch apparently failed: retry
    }

    @Test
    fun quickRelaunchLoopRetriggersEveryTime() {
        val p = InterventionPolicy()
        assertTrue(p.shouldIntervene("fb", true, 0))
        assertFalse(p.shouldIntervene("dev.detour.guard", false, 100)) // intervention in front
        assertFalse(p.shouldIntervene("launcher", false, 200))         // went home
        assertTrue(p.shouldIntervene("fb", true, 300))                 // reopened right away
        assertFalse(p.shouldIntervene("launcher", false, 400))
        assertTrue(p.shouldIntervene("fb", true, 500))
    }

    @Test
    fun switchingDirectlyBetweenTwoBlockedAppsTriggersForEach() {
        val p = InterventionPolicy()
        assertTrue(p.shouldIntervene("fb", true, 0))
        assertTrue(p.shouldIntervene("other", true, 100))
    }

    @Test
    fun allowedAppsNeverTrigger() {
        val p = InterventionPolicy()
        assertFalse(p.shouldIntervene("maps", false, 0))
        assertFalse(p.shouldIntervene(null, true, 0))
    }
}
