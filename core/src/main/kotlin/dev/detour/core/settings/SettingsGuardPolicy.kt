package dev.detour.core.settings

/**
 * Decides when a Settings screen gets the 60-second pause. Friction, not a lock: after you wait
 * and say why, that app's screens pass for [permitMs] so you can actually make the change.
 */
class SettingsGuardPolicy(private val permitMs: Long = 5 * 60_000L) {
    private var permittedPackage: String? = null
    private var permittedUntilMs = 0L

    /** The target matching this screen, if it's one the guard watches. */
    fun match(packageName: String, className: String?, targets: Set<GuardTarget>): GuardTarget? =
        if (className == null) null
        else targets.firstOrNull { it.packageName == packageName && it.className == className }

    /**
     * @param suppressedKeys screen types currently being calibrated for the first time (opening a
     *   page so Detour can learn its name shouldn't trigger a 60-second pause).
     * @param inSetupGrace true during the first hour after Detour starts watching, so granting
     *   permissions during setup isn't interrupted.
     */
    fun shouldPause(
        packageName: String,
        className: String?,
        enabled: Boolean,
        targets: Set<GuardTarget>,
        nowMs: Long,
        suppressedKeys: Set<String> = emptySet(),
        inSetupGrace: Boolean = false,
    ): Boolean {
        if (!enabled || inSetupGrace) return false
        val target = match(packageName, className, targets) ?: return false
        if (target.key in suppressedKeys) return false
        return !(packageName == permittedPackage && nowMs < permittedUntilMs)
    }

    fun permit(packageName: String, nowMs: Long) {
        permittedPackage = packageName
        permittedUntilMs = nowMs + permitMs
    }
}

/**
 * Calibration: you open a protected page once, and Detour records which (package, activity) came
 * to the front. Picks the first screen that isn't Detour itself, the launcher, or system UI.
 */
object Calibration {
    data class Screen(val packageName: String, val className: String, val atMs: Long)

    /** Screen-type key for Chrome's settings (matches the guard app's GuardScreen name). */
    const val CHROME_SETTINGS_KEY = "CHROME_SETTINGS"

    private val IGNORED = setOf("dev.detour.guard", "com.android.systemui", "com.google.android.apps.nexuslauncher", "android")

    /**
     * Whether a recorded screen can be a guard target for [key]. This stops calibration from ever
     * guarding an app's ordinary screens: for Chrome, only a settings screen counts (opening Chrome
     * first shows its main browsing screen, which must never be guarded); for system pages, only
     * screens of the Settings app or the permission controller count.
     */
    fun isValid(key: String, packageName: String, className: String): Boolean = when (key) {
        CHROME_SETTINGS_KEY -> className.contains("settings", ignoreCase = true) || className.contains("preferences", ignoreCase = true)
        else -> packageName.contains("settings", ignoreCase = true) || packageName.contains("permissioncontroller", ignoreCase = true)
    }

    fun isValid(target: GuardTarget) = isValid(target.key, target.packageName, target.className)

    /** The first screen since [sinceMs] that is a valid target for [key]. */
    fun pick(screens: List<Screen>, sinceMs: Long, key: String, ignoredPackages: Set<String> = emptySet()): Screen? =
        screens.filter {
            it.atMs >= sinceMs && it.packageName !in IGNORED && it.packageName !in ignoredPackages &&
                isValid(key, it.packageName, it.className)
        }.minByOrNull { it.atMs }
}
