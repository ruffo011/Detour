package dev.detour.guard.guardrail

import dev.detour.core.settings.Calibration

/**
 * The last few screens that came to the front (package + activity class), kept in memory only,
 * for Settings-guard calibration. Never written to disk.
 */
class RecentScreens(private val capacity: Int = 40) {
    private val screens = ArrayDeque<Calibration.Screen>()

    /** Screen type being calibrated for the first time, and since when. */
    @Volatile var calibrating: Pair<GuardScreen, Long>? = null

    @Synchronized
    fun add(packageName: String, className: String?, atMs: Long) {
        if (className == null) return
        screens.addLast(Calibration.Screen(packageName, className, atMs))
        while (screens.size > capacity) screens.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<Calibration.Screen> = screens.toList()
}
