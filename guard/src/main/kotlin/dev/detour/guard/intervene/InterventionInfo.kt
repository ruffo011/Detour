package dev.detour.guard.intervene

import android.content.Intent

enum class InterventionMode {
    /** A Blocked app: alternatives or home. */
    BLOCKED,

    /** A new Distracting session with budget left: feeling, alternatives, wait, intention, continue. */
    PAUSE,

    /** Distracting budget used up: alternatives or home. */
    OUT_OF_BUDGET,

    /** Outside allowed hours: alternatives or home. */
    OUTSIDE_HOURS,

    /** Opens cap reached: alternatives or home. */
    OPENS_CAP,

    /** A Settings screen that can turn protection off: 60 s pause + reason, then allowed through. */
    SETTINGS_GUARD,
}

/** What the detector knew when it opened the intervention. Passed as intent extras. */
data class InterventionInfo(
    val packageName: String,
    val mode: InterventionMode,
    val opensToday: Int = 0,
    val usedMinutes: Int = 0,
    val budgetMinutes: Int? = null,
    val waitSeconds: Int = 0,
    /** e.g. "9:00–23:00" for OUTSIDE_HOURS. */
    val allowedHoursText: String? = null,
) {
    fun writeTo(intent: Intent): Intent = intent
        .putExtra(K_PKG, packageName)
        .putExtra(K_MODE, mode.name)
        .putExtra(K_OPENS, opensToday)
        .putExtra(K_USED, usedMinutes)
        .putExtra(K_BUDGET, budgetMinutes ?: -1)
        .putExtra(K_WAIT, waitSeconds)
        .putExtra(K_HOURS, allowedHoursText)

    /** "Opened 14 times today · 47 min of 90 used." */
    val feedbackLine: String
        get() {
            val opens = if (opensToday == 1) "Opened once today" else "Opened $opensToday times today"
            val used = if (budgetMinutes != null) "$usedMinutes min of $budgetMinutes used" else "$usedMinutes min used"
            return "$opens · $used."
        }

    companion object {
        private const val K_PKG = "pkg"
        private const val K_MODE = "mode"
        private const val K_OPENS = "opens"
        private const val K_USED = "used"
        private const val K_BUDGET = "budget"
        private const val K_WAIT = "wait"
        private const val K_HOURS = "hours"

        fun from(intent: Intent): InterventionInfo? {
            val pkg = intent.getStringExtra(K_PKG) ?: return null
            return InterventionInfo(
                packageName = pkg,
                mode = runCatching { InterventionMode.valueOf(intent.getStringExtra(K_MODE)!!) }.getOrDefault(InterventionMode.BLOCKED),
                opensToday = intent.getIntExtra(K_OPENS, 0),
                usedMinutes = intent.getIntExtra(K_USED, 0),
                budgetMinutes = intent.getIntExtra(K_BUDGET, -1).takeIf { it >= 0 },
                waitSeconds = intent.getIntExtra(K_WAIT, 0),
                allowedHoursText = intent.getStringExtra(K_HOURS),
            )
        }
    }
}
