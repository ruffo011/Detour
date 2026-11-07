package dev.detour.core.budget

import java.time.LocalDate

/**
 * The Distracting session you've gone through the pause for. Shared between the detector
 * (which checks it) and the intervention screen (which grants it).
 *
 * The session lasts while the app stays in front, or is away for no more than
 * [UsageMath.NEW_SESSION_GAP_MS]. Once it has been away longer, it is over for good: coming back
 * means a new open, which goes through the pause again.
 */
class SessionState(private val gapMs: Long = UsageMath.NEW_SESSION_GAP_MS) {
    var permittedPackage: String? = null
        private set
    private var lastSeenMs = 0L

    /** When to ask "Still doing what you came for?". */
    var checkInDueMs = Long.MAX_VALUE

    /** The day the 5-minutes-left warning was last shown. */
    var warnedDay: LocalDate? = null

    fun permit(packageName: String, nowMs: Long, checkInMinutes: Int) {
        permittedPackage = packageName
        lastSeenMs = nowMs
        checkInDueMs = nowMs + checkInMinutes * 60_000L
    }

    fun isPermitted(packageName: String, nowMs: Long): Boolean {
        expireIfStale(nowMs)
        return packageName == permittedPackage
    }

    /**
     * Records that [foregroundPackage] is in front now. Only extends a session that is still
     * alive; it never revives one that already expired (that was the bug: returning after
     * minutes away used to refresh the session before it was checked).
     */
    fun touch(foregroundPackage: String?, nowMs: Long) {
        expireIfStale(nowMs)
        if (foregroundPackage != null && foregroundPackage == permittedPackage) lastSeenMs = nowMs
    }

    fun revoke() {
        permittedPackage = null
        checkInDueMs = Long.MAX_VALUE
    }

    private fun expireIfStale(nowMs: Long) {
        if (permittedPackage != null && nowMs - lastSeenMs > gapMs) revoke()
    }
}
