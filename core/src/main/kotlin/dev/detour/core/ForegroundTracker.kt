package dev.detour.core

/** One relevant UsageStatsManager event, stripped of Android types. */
data class UsageEvent(
    val type: Type,
    val packageName: String,
    val className: String?,
    val timeMs: Long,
) {
    enum class Type { RESUMED, PAUSED }
}

/** The app (and screen within it) currently in the foreground. */
data class ForegroundApp(
    val packageName: String,
    val className: String?,
    /** When this package came to the foreground. */
    val sinceMs: Long,
)

/**
 * Turns a stream of activity resumed/paused events into "what is in the foreground now".
 * Feed it events in time order (UsageStatsManager.queryEvents returns them that way).
 */
class ForegroundTracker {
    var current: ForegroundApp? = null
        private set

    /** Applies events and returns true if the foreground package or screen changed. */
    fun onEvents(events: List<UsageEvent>): Boolean {
        val before = current
        for (e in events) {
            when (e.type) {
                UsageEvent.Type.RESUMED -> {
                    val cur = current
                    current = if (cur != null && cur.packageName == e.packageName) {
                        cur.copy(className = e.className)          // moved between screens of one app
                    } else {
                        ForegroundApp(e.packageName, e.className, e.timeMs)
                    }
                }
                UsageEvent.Type.PAUSED -> {
                    val cur = current
                    // Only the activity that is actually in front can clear it; a late pause from the
                    // previous app (which arrives after the next app resumed) must be ignored.
                    if (cur != null && cur.packageName == e.packageName && cur.className == e.className) {
                        current = null
                    }
                }
            }
        }
        return before?.packageName != current?.packageName || before?.className != current?.className
    }
}
