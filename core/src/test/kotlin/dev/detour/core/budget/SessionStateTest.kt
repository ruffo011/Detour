package dev.detour.core.budget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStateTest {
    private val s = 1_000L

    /** Mirrors the detector: touch first, then check — the order that exposed the bug. */
    private fun tick(state: SessionState, fg: String?, now: Long): Boolean {
        state.touch(fg, now)
        return fg != null && state.isPermitted(fg, now)
    }

    @Test
    fun staysPermittedWhileInFront() {
        val state = SessionState()
        state.permit("yt", 0, 10)
        for (t in 0L..600L step 1) assertTrue(tick(state, "yt", t * s))
    }

    @Test
    fun shortTripAwayKeepsTheSession() {
        val state = SessionState()
        state.permit("yt", 0, 10)
        tick(state, "yt", 10 * s)
        tick(state, "launcher", 20 * s)
        assertTrue(tick(state, "yt", 39 * s)) // away 29 s
    }

    @Test
    fun reopeningAfterMoreThan30sNeedsANewPause() {
        val state = SessionState()
        state.permit("yt", 0, 10)
        tick(state, "yt", 10 * s)
        tick(state, "launcher", 11 * s)
        // Back after 5 minutes: the touch must not revive the expired session.
        assertFalse(tick(state, "yt", 310 * s))
        assertFalse(tick(state, "yt", 311 * s))
    }

    @Test
    fun screenOffGapAlsoEndsTheSession() {
        val state = SessionState()
        state.permit("yt", 0, 10)
        tick(state, "yt", 5 * s)
        // No ticks at all while the screen was off, then YouTube is still in front on unlock.
        assertFalse(tick(state, "yt", 120 * s))
    }

    @Test
    fun permissionIsForOneAppOnly() {
        val state = SessionState()
        state.permit("yt", 0, 10)
        assertFalse(tick(state, "ig", 1 * s))
    }

    @Test
    fun revokeAndCheckInReset() {
        val state = SessionState()
        state.permit("yt", 0, 10)
        assertEquals(600 * s, state.checkInDueMs)
        state.revoke()
        assertFalse(state.isPermitted("yt", 1 * s))
        assertEquals(Long.MAX_VALUE, state.checkInDueMs)
    }

    @Test
    fun expiryClearsTheCheckInTimer() {
        val state = SessionState()
        state.permit("yt", 0, 10)
        tick(state, "launcher", 100 * s)
        assertEquals(Long.MAX_VALUE, state.checkInDueMs)
    }
}
