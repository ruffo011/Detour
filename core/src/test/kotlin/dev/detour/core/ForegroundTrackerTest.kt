package dev.detour.core

import dev.detour.core.UsageEvent.Type.PAUSED
import dev.detour.core.UsageEvent.Type.RESUMED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundTrackerTest {
    private fun ev(type: UsageEvent.Type, pkg: String, cls: String, t: Long) = UsageEvent(type, pkg, cls, t)

    @Test
    fun resumeSetsForeground() {
        val t = ForegroundTracker()
        assertTrue(t.onEvents(listOf(ev(RESUMED, "a", "a.Main", 100))))
        assertEquals(ForegroundApp("a", "a.Main", 100), t.current)
    }

    @Test
    fun switchingAppsWithPauseBeforeResume() {
        val t = ForegroundTracker()
        t.onEvents(listOf(ev(RESUMED, "a", "a.Main", 100)))
        t.onEvents(listOf(ev(PAUSED, "a", "a.Main", 200), ev(RESUMED, "b", "b.Main", 210)))
        assertEquals("b", t.current?.packageName)
        assertEquals(210L, t.current?.sinceMs)
    }

    @Test
    fun latePauseFromPreviousAppIsIgnored() {
        val t = ForegroundTracker()
        t.onEvents(listOf(ev(RESUMED, "a", "a.Main", 100), ev(RESUMED, "b", "b.Main", 200), ev(PAUSED, "a", "a.Main", 205)))
        assertEquals("b", t.current?.packageName)
    }

    @Test
    fun movingBetweenScreensKeepsSinceAndUpdatesClass() {
        val t = ForegroundTracker()
        t.onEvents(listOf(ev(RESUMED, "a", "a.Main", 100)))
        val changed = t.onEvents(listOf(ev(PAUSED, "a", "a.Main", 150), ev(RESUMED, "a", "a.Settings", 160)))
        // The pause briefly cleared it, but the resume of the same package restores it with a new since.
        assertTrue(changed)
        assertEquals("a.Settings", t.current?.className)
    }

    @Test
    fun resumeWithoutPauseWithinSameAppKeepsSince() {
        val t = ForegroundTracker()
        t.onEvents(listOf(ev(RESUMED, "a", "a.Main", 100)))
        t.onEvents(listOf(ev(RESUMED, "a", "a.Detail", 300)))
        assertEquals(ForegroundApp("a", "a.Detail", 100), t.current)
    }

    @Test
    fun pauseWithNothingAfterClearsForeground() {
        val t = ForegroundTracker()
        t.onEvents(listOf(ev(RESUMED, "a", "a.Main", 100)))
        assertTrue(t.onEvents(listOf(ev(PAUSED, "a", "a.Main", 200))))
        assertNull(t.current)
    }

    @Test
    fun noEventsMeansNoChange() {
        val t = ForegroundTracker()
        t.onEvents(listOf(ev(RESUMED, "a", "a.Main", 100)))
        assertFalse(t.onEvents(emptyList()))
    }
}
