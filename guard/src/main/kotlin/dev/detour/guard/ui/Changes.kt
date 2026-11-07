package dev.detour.guard.ui

import android.content.Context
import android.widget.Toast
import dev.detour.core.settings.SettingsOp
import dev.detour.guard.data.SubmitResult
import dev.detour.guard.detourApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Submits a protected-setting change through the 24-hour queue and tells you what happened.
 * Tighter changes apply silently; looser ones get a short note that they'll wait.
 */
suspend fun submitChange(context: Context, op: SettingsOp, description: String): SubmitResult {
    val result = context.detourApp.changes.submit(op, description)
    if (result is SubmitResult.Queued) {
        withContext(Dispatchers.Main) {
            Toast.makeText(
                context,
                "That loosens a setting, so it applies in 24 hours. You can cancel it under Pending changes.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
    return result
}

/** "in 23 h 12 min" / "in 4 min" / "any moment now". */
fun timeUntil(targetMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val minutes = ((targetMs - nowMs) / 60_000).coerceAtLeast(0)
    return when {
        minutes == 0L -> "any moment now"
        minutes < 60 -> "in $minutes min"
        else -> "in ${minutes / 60} h ${minutes % 60} min"
    }
}
