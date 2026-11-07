package dev.detour.guard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.detour.core.alternatives.Feeling
import dev.detour.core.alternatives.Outcome
import dev.detour.core.review.AlternativeTally
import dev.detour.core.review.ChoiceRow
import dev.detour.core.review.DaySummary
import dev.detour.core.review.Suggestion
import dev.detour.core.review.WeeklyReview
import dev.detour.guard.DetourApp
import dev.detour.guard.data.LapseEventEntity
import dev.detour.guard.detourApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

private const val WEEK_MS = 7 * 24 * 60 * 60 * 1000L

private data class IntentionRow(val app: String, val text: String, val atMs: Long)

private data class ReviewData(
    val days: List<DaySummary>,
    val budgetMinutes: Int?,
    val tallies: Map<Feeling, List<AlternativeTally>>,
    val intentions: List<IntentionRow>,
    val suggestions: List<Suggestion>,
)

private suspend fun loadReview(app: DetourApp): ReviewData = withContext(Dispatchers.IO) {
    val now = System.currentTimeMillis()
    val settings = app.settings.current
    val clock = app.settings.clock()
    val endDay = clock.dayOf(now)
    val from = clock.startOf(endDay.minusDays(6))
    val intervals = app.usage.intervalsSince(from - 20 * 60_000L)
    val days = WeeklyReview.days(intervals, clock, endDay, settings.reopenGapMs)

    val titles = app.database.alternatives().all().associate { it.id to it.title }
    val choices = app.database.alternatives().choicesSince(from).mapNotNull { c ->
        val feeling = runCatching { Feeling.valueOf(c.feeling) }.getOrNull() ?: return@mapNotNull null
        ChoiceRow(titles[c.alternativeId] ?: "(deleted alternative)", feeling, c.outcome?.let { runCatching { Outcome.valueOf(it) }.getOrNull() })
    }

    val intentions = app.database.intentions().since(from).map {
        val label = if (it.packageName.startsWith("settings:")) "Settings change" else app.catalog.label(it.packageName)
        IntentionRow(label, it.text, it.atMs)
    }

    // Only days with data count towards suggestions (the week may have started recently).
    val daysWithData = days.filter { it.day >= clock.dayOf(app.setup.firstRunMs) }
    ReviewData(
        days = days,
        budgetMinutes = settings.dailyBudgetMinutes,
        tallies = WeeklyReview.tallies(choices),
        intentions = intentions,
        suggestions = WeeklyReview.suggestions(settings, daysWithData, intervals, clock, now),
    )
}

@Composable
fun WeeklyReviewScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.detourApp
    val scope = rememberCoroutineScope()
    var reloadKey by remember { mutableIntStateOf(0) }
    val data by produceState<ReviewData?>(null, reloadKey) { value = loadReview(app) }
    val lapses by remember { app.database.lapses().observeSince(System.currentTimeMillis() - WEEK_MS) }
        .collectAsStateWithLifecycle(emptyList())
    LaunchedEffect(Unit) { app.setup.markReviewViewed() }

    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Back") }
            Text("Your week", style = MaterialTheme.typography.titleLarge)
        }
        val d = data
        if (d == null) {
            Text("Gathering the week…", Modifier.padding(24.dp))
            return@Column
        }
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Section("Time in Distracting apps") { UsageBars(d.days, d.budgetMinutes) }

            if (d.suggestions.isNotEmpty()) {
                Section("Possible next steps") {
                    Text(
                        "Based on this week. Each one is stricter, so it applies right away if you accept it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    d.suggestions.forEach { s ->
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(s.text, style = MaterialTheme.typography.bodyMedium)
                                Button(onClick = {
                                    scope.launch {
                                        submitChange(context, s.op, s.text)
                                        reloadKey++
                                    }
                                }) { Text("Apply") }
                            }
                        }
                    }
                }
            }

            Section("What you tried instead") {
                if (d.tallies.isEmpty()) Muted("No alternatives chosen this week.")
                d.tallies.forEach { (feeling, rows) ->
                    Text("When ${feeling.label}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                    rows.forEach { t ->
                        val parts = Outcome.entries.filter { it != Outcome.SKIPPED }.mapNotNull { o -> t.outcomes[o]?.let { "$it ${o.label}" } } +
                            listOfNotNull(t.unanswered.takeIf { it > 0 }?.let { "$it no answer" })
                        Text("${t.title}: chosen ${t.chosen}× · ${parts.joinToString(", ")}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            Section("What you opened apps for") {
                if (d.intentions.isEmpty()) Muted("No intentions typed this week.")
                d.intentions.forEach { i ->
                    Column {
                        Text("“${i.text}”", style = MaterialTheme.typography.bodyMedium)
                        Muted("${i.app} · ${formatWhen(i.atMs)}")
                    }
                }
            }

            Section("When protection was partly off") {
                if (lapses.isEmpty()) Muted("Nothing this week.")
                lapses.forEach { l -> LapseRow(l) { note -> scope.launch { app.database.lapses().setNote(l.id, note.ifBlank { null }) } } }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
        HorizontalDivider(Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun Muted(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** One horizontal bar per day; a thin mark shows the budget. Calm colors, no "good/bad" coloring. */
@Composable
private fun UsageBars(days: List<DaySummary>, budgetMinutes: Int?) {
    val maxMinutes = maxOf(days.maxOf { it.totals.usedMinutes }, (budgetMinutes ?: 0).toDouble(), 1.0)
    val locale = LocalConfiguration.current.locales[0]
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        days.forEach { day ->
            val minutes = day.totals.usedMinutes
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(day.day.dayOfWeek.getDisplayName(TextStyle.SHORT, locale), modifier = Modifier.width(44.dp), style = MaterialTheme.typography.bodyMedium)
                Box(Modifier.weight(1f).height(18.dp)) {
                    Box(
                        Modifier.fillMaxHeight().fillMaxWidth((minutes / maxMinutes).toFloat().coerceIn(0f, 1f))
                            .clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
                    )
                    if (budgetMinutes != null) {
                        // A box as wide as the budget, with a thin mark at its right edge.
                        Box(Modifier.fillMaxHeight().fillMaxWidth((budgetMinutes / maxMinutes).toFloat().coerceIn(0f, 1f))) {
                            Box(
                                Modifier.align(Alignment.CenterEnd).width(2.dp).fillMaxHeight()
                                    .background(MaterialTheme.colorScheme.onSurfaceVariant),
                            )
                        }
                    }
                }
                Text(
                    "${minutes.toInt()} min · ${day.totals.opens}",
                    modifier = Modifier.width(96.dp).padding(start = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Muted(if (budgetMinutes != null) "Bars: minutes per day (the thin line is your $budgetMinutes min). Number after · is opens." else "Bars: minutes per day. Number after · is opens.")
    }
}

@Composable
private fun LapseRow(lapse: LapseEventEntity, onNote: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var text by remember(lapse.note) { mutableStateOf(lapse.note.orEmpty()) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(lapse.detail, style = MaterialTheme.typography.bodyMedium)
        Muted(formatWhen(lapse.atMs))
        if (editing) {
            OutlinedTextField(text, { text = it }, label = { Text("What was going on? (optional)") }, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = {
                onNote(text)
                editing = false
            }) { Text("Save note") }
        } else {
            lapse.note?.let { Text("Note: $it", style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = { editing = true }) { Text(if (lapse.note == null) "Add a note" else "Edit note") }
        }
    }
}

private val WHEN = DateTimeFormatter.ofPattern("EEE d MMM, H:mm")
private fun formatWhen(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(WHEN)
