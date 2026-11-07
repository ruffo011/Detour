package dev.detour.guard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.detour.core.budget.BudgetSettings
import dev.detour.core.time.AllowedHours
import dev.detour.guard.data.ObserveWeek
import dev.detour.guard.detourApp
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME = DateTimeFormatter.ofPattern("H:mm")
private val DATE = DateTimeFormatter.ofPattern("EEEE, MMM d")

@Composable
fun BudgetScreen(onBack: () -> Unit) {
    val app = LocalContext.current.detourApp
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val update: ((BudgetSettings) -> BudgetSettings) -> Unit = { app.settings.update(it) }
    val now = System.currentTimeMillis()

    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Back") }
            Text("Budget & hours", style = MaterialTheme.typography.titleLarge)
        }
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val until = settings.observeUntilMs
            if (until != null && settings.isObserving(now)) {
                InfoCard(
                    "Observing until ${Instant.ofEpochMilli(until).atZone(ZoneId.systemDefault()).format(DATE)} " +
                        "at ${settings.resetTime.format(TIME)}. Distracting apps aren't limited yet. Detour is " +
                        "measuring a normal week so it can suggest a starting budget.",
                ) {
                    OutlinedButton(onClick = { scope.launch { ObserveWeek.finish(app, System.currentTimeMillis()) } }) {
                        Text("Stop observing and set a budget now")
                    }
                }
            }

            Text(
                "Changes apply right away for now. A later update makes loosening changes wait 24 hours.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Section("Daily time for Distracting apps", "Shared by all of them, so running out on one doesn't move you to another.") {
                Stepper(
                    value = settings.dailyBudgetMinutes?.let { "$it min" } ?: "Not set yet",
                    onMinus = { update { s -> s.copy(dailyBudgetMinutes = ((s.dailyBudgetMinutes ?: 60) - 5).coerceAtLeast(5), budgetConfirmed = true) } },
                    onPlus = { update { s -> s.copy(dailyBudgetMinutes = ((s.dailyBudgetMinutes ?: 55) + 5).coerceAtMost(600), budgetConfirmed = true) } },
                )
            }

            Section("Allowed hours", "The time can only be spent inside this window.") {
                val hours = settings.allowedHours
                SwitchRow("Limit to allowed hours", hours != null) { on ->
                    update { it.copy(allowedHours = if (on) AllowedHours.DEFAULT else null) }
                }
                if (hours != null) {
                    Stepper(
                        label = "From",
                        value = hours.start.format(TIME),
                        onMinus = { update { it.copy(allowedHours = hours.copy(start = hours.start.minusMinutes(30))) } },
                        onPlus = { update { it.copy(allowedHours = hours.copy(start = hours.start.plusMinutes(30))) } },
                    )
                    Stepper(
                        label = "Until",
                        value = hours.end.format(TIME),
                        onMinus = { update { it.copy(allowedHours = hours.copy(end = hours.end.minusMinutes(30))) } },
                        onPlus = { update { it.copy(allowedHours = hours.copy(end = hours.end.plusMinutes(30))) } },
                    )
                }
            }

            Section(
                "Opens per day",
                "Optional. An open is the app coming back after more than ${formatSeconds(settings.reopenAfterSeconds)} " +
                    "away, including times you went home from the pause.",
            ) {
                val cap = settings.opensCap
                SwitchRow("Limit opens", cap != null) { on -> update { it.copy(opensCap = if (on) 10 else null) } }
                if (cap != null) {
                    Stepper(
                        value = "$cap opens",
                        onMinus = { update { it.copy(opensCap = (cap - 1).coerceAtLeast(1)) } },
                        onPlus = { update { it.copy(opensCap = cap + 1) } },
                    )
                }
            }

            Section("Wait before each open", "Seconds, getting longer with each open today. The last number repeats.") {
                WaitsField(settings.waitsSeconds) { waits -> update { it.copy(waitsSeconds = waits) } }
            }

            Section(
                "Counts as a new open after",
                "How long an app has to be away before coming back needs the pause again. Shorter trips " +
                    "(the home screen, notifications, a quick reply) continue the same session.",
            ) {
                val choices = BudgetSettings.REOPEN_CHOICES_SECONDS
                val current = settings.reopenAfterSeconds
                Stepper(
                    value = formatSeconds(current),
                    onMinus = {
                        val next = choices.lastOrNull { it < current } ?: choices.first()
                        update { it.copy(reopenAfterSeconds = next) }
                    },
                    onPlus = {
                        val next = choices.firstOrNull { it > current } ?: choices.last()
                        update { it.copy(reopenAfterSeconds = next) }
                    },
                )
            }

            Section("Check-in during use", "“Still doing what you came for?” after this long in one session.") {
                Stepper(
                    value = "${settings.sessionCheckInMinutes} min",
                    onMinus = { update { it.copy(sessionCheckInMinutes = (it.sessionCheckInMinutes - 1).coerceAtLeast(1)) } },
                    onPlus = { update { it.copy(sessionCheckInMinutes = (it.sessionCheckInMinutes + 1).coerceAtMost(120)) } },
                )
            }

            Section("New day starts at", "So late-night use doesn't get a fresh budget at midnight.") {
                Stepper(
                    value = settings.resetTime.format(TIME),
                    onMinus = { update { it.copy(resetTime = it.resetTime.minusMinutes(30)) } },
                    onPlus = { update { it.copy(resetTime = it.resetTime.plusMinutes(30)) } },
                )
            }
        }
    }
}

@Composable
private fun WaitsField(waits: List<Int>, onValid: (List<Int>) -> Unit) {
    var text by remember(waits) { mutableStateOf(waits.joinToString(", ")) }
    val parsed = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { it.toIntOrNull() }
    val valid = parsed.isNotEmpty() && parsed.all { it != null && it in 0..600 }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            val p = it.split(',').map { s -> s.trim() }.filter { s -> s.isNotEmpty() }.map { s -> s.toIntOrNull() }
            if (p.isNotEmpty() && p.all { v -> v != null && v in 0..600 }) onValid(p.filterNotNull())
        },
        isError = !valid,
        supportingText = { Text(if (valid) "e.g. 5, 15, 30, 60" else "Numbers from 0 to 600, separated by commas") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Section(title: String, explanation: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(explanation, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@Composable
private fun InfoCard(text: String, action: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            action()
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** 30 -> "30 s", 120 -> "2 min", 90 -> "1 min 30 s". */
private fun formatSeconds(seconds: Int): String = when {
    seconds < 60 -> "$seconds s"
    seconds % 60 == 0 -> "${seconds / 60} min"
    else -> "${seconds / 60} min ${seconds % 60} s"
}

@Composable
fun Stepper(value: String, onMinus: () -> Unit, onPlus: () -> Unit, label: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (label != null) Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(64.dp))
        OutlinedButton(onClick = onMinus) { Text("−") }
        Text(value, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp))
        OutlinedButton(onClick = onPlus) { Text("+") }
    }
}
