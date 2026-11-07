package dev.detour.core

/**
 * Decides when to (re)launch the intervention screen, avoiding both flicker and gaps:
 *
 * - An app that needs intervention coming to the foreground triggers it immediately,
 *   including the second, third, ... time in quick succession (re-launch loops).
 * - While the same app stays in front (e.g. the launch didn't take), it retries at most once
 *   every [retryAfterMs], so a failure can't become a tight crash/launch loop.
 * - Anything else in front (home screen, the intervention itself) resets the state.
 */
class InterventionPolicy(private val retryAfterMs: Long = 1_500) {
    private var lastPackage: String? = null
    private var lastLaunchAtMs: Long = 0

    fun shouldIntervene(foregroundPackage: String?, needsIntervention: Boolean, nowMs: Long): Boolean {
        if (foregroundPackage == null || !needsIntervention) {
            lastPackage = null
            return false
        }
        val isNewArrival = foregroundPackage != lastPackage
        if (isNewArrival || nowMs - lastLaunchAtMs >= retryAfterMs) {
            lastPackage = foregroundPackage
            lastLaunchAtMs = nowMs
            return true
        }
        return false
    }
}
