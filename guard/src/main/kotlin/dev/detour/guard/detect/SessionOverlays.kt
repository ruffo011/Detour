package dev.detour.guard.detect

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.detour.core.budget.BudgetSettings
import dev.detour.core.budget.DistractingGate
import dev.detour.core.budget.SessionState
import dev.detour.guard.data.TodayUsage
import dev.detour.guard.ui.theme.DetourTheme

/**
 * The two small overlays during an allowed Distracting session:
 * - "Still doing what you came for?" every N minutes (default 10)
 * - "5 minutes left" once a day when the budget is nearly used
 */
class SessionOverlays(
    private val context: Context,
    private val session: SessionState,
    private val goHome: () -> Unit,
) {
    private val checkIn = ComposeOverlay(context)
    private val warning = ComposeOverlay(context)
    private var warningHideAtMs = 0L

    /** Call each tick while an allowed (not observed) Distracting session is in front. */
    fun onAllowedTick(settings: BudgetSettings, today: TodayUsage, nowMs: Long) {
        if (nowMs >= session.checkInDueMs && !checkIn.isShowing) showCheckIn(settings)

        val remaining = DistractingGate.remainingMs(settings, today.usedMs)
        if (remaining != null && remaining in 1..BudgetSettings.WARNING_MINUTES * 60_000L && session.warnedDay != today.day) {
            session.warnedDay = today.day
            val minutes = ((remaining + 59_999) / 60_000).toInt()
            warning.show { Notice("$minutes minute${if (minutes == 1) "" else "s"} of today's time left.") }
            warningHideAtMs = nowMs + 6_000
        }
        hideExpiredWarning(nowMs)
    }

    /** Call each tick when no allowed Distracting session is in front. */
    fun onOtherTick(nowMs: Long) {
        checkIn.hide()
        hideExpiredWarning(nowMs, force = true)
    }

    fun hideAll() {
        checkIn.hide()
        warning.hide()
    }

    private fun hideExpiredWarning(nowMs: Long, force: Boolean = false) {
        if (warning.isShowing && (force || nowMs >= warningHideAtMs)) warning.hide()
    }

    private fun showCheckIn(settings: BudgetSettings) {
        checkIn.show {
            CheckInCard(
                onKeepGoing = {
                    session.checkInDueMs = System.currentTimeMillis() + settings.sessionCheckInMinutes * 60_000L
                    checkIn.hide()
                },
                onGoHome = {
                    session.revoke()
                    checkIn.hide()
                    goHome()
                },
            )
        }
    }
}

@androidx.compose.runtime.Composable
private fun CheckInCard(onKeepGoing: () -> Unit, onGoHome: () -> Unit) {
    DetourTheme {
        Card(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Still doing what you came for?", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onKeepGoing, modifier = Modifier.weight(1f)) { Text("Yes, keep going") }
                    Button(onClick = onGoHome, modifier = Modifier.weight(1f)) { Text("No, take me home") }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun Notice(text: String) {
    DetourTheme {
        Card(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        ) {
            Text(text, fontSize = 18.sp, modifier = Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurface)
        }
    }
}
