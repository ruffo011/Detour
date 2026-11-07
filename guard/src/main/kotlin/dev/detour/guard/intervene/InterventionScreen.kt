package dev.detour.guard.intervene

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.detour.core.alternatives.Alternative
import dev.detour.core.alternatives.AlternativeRanker
import dev.detour.core.alternatives.Feeling
import dev.detour.core.alternatives.OutcomeRecord
import dev.detour.core.alternatives.RankedAlternative
import kotlinx.coroutines.delay
import java.time.LocalTime

private const val MIN_INTENTION_CHARS = 10
private const val RIDE_IT_OUT_SECONDS = 60

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InterventionScreen(
    info: InterventionInfo,
    appLabel: String,
    alternatives: List<Alternative>,
    history: List<OutcomeRecord>,
    shownAtMs: Long,
    localTime: LocalTime,
    onChooseAlternative: (Alternative, Feeling) -> Unit,
    onStartRideItOut: (Alternative, Feeling) -> Unit,
    onContinue: (Feeling, String) -> Unit,
    onGoHome: () -> Unit,
) {
    var feeling by remember { mutableStateOf<Feeling?>(null) }
    var ridingOut by remember { mutableStateOf(false) }

    if (ridingOut) {
        RideItOut(onGoHome)
        return
    }

    val ranked = remember(feeling, alternatives, history) {
        feeling?.let { AlternativeRanker().rank(alternatives, it, AlternativeRanker.inferPlace(localTime), history) }.orEmpty()
    }
    val rideItOut = alternatives.firstOrNull { it.isRideItOut }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(title(info, appLabel), fontSize = 32.sp, lineHeight = 40.sp)
        Text(subtitle(info), fontSize = 19.sp, lineHeight = 28.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (info.mode != InterventionMode.BLOCKED) {
            Text(info.feedbackLine, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(4.dp))
        Text("What's going on?", fontSize = 22.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Feeling.entries.forEach { f ->
                FilterChip(
                    selected = feeling == f,
                    onClick = { feeling = f },
                    label = { Text(f.label, fontSize = 17.sp, modifier = Modifier.padding(vertical = 6.dp)) },
                )
            }
        }

        val f = feeling
        if (f != null) {
            Text("Something else you could do", fontSize = 20.sp, modifier = Modifier.padding(top = 8.dp))
            ranked.forEach { r -> AlternativeCard(r) { onChooseAlternative(r.alternative, f) } }
            if (rideItOut != null) {
                OutlinedButton(
                    onClick = {
                        onStartRideItOut(rideItOut, f)
                        ridingOut = true
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("Ride it out · 60 s", fontSize = 17.sp) }
            }
        }

        if (info.mode == InterventionMode.PAUSE) {
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            PauseSection(info, appLabel, shownAtMs, feeling, onContinue)
        }

        TextButton(onClick = onGoHome, modifier = Modifier.padding(top = 8.dp)) {
            Text("Go to home screen", fontSize = 17.sp)
        }
    }
}

@Composable
private fun PauseSection(
    info: InterventionInfo,
    appLabel: String,
    shownAtMs: Long,
    feeling: Feeling?,
    onContinue: (Feeling, String) -> Unit,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }
    var intention by remember { mutableStateOf("") }
    val remaining = (info.waitSeconds - ((now - shownAtMs) / 1000).toInt()).coerceAtLeast(0)
    val intentionOk = intention.trim().length >= MIN_INTENTION_CHARS
    val ready = feeling != null && remaining == 0 && intentionOk

    Text("Still want to open $appLabel?", fontSize = 20.sp)
    OutlinedTextField(
        value = intention,
        onValueChange = { intention = it },
        label = { Text("Why are you opening it?") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
    )
    val missing = listOfNotNull(
        "pick what's going on above".takeIf { feeling == null },
        "write at least $MIN_INTENTION_CHARS characters".takeIf { !intentionOk },
    )
    if (missing.isNotEmpty()) {
        Text(
            "To continue: ${missing.joinToString(" and ")}.",
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Button(
        onClick = { feeling?.let { onContinue(it, intention) } },
        enabled = ready,
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
        Text(if (remaining > 0) "Continue in $remaining s" else "Continue to $appLabel", fontSize = 17.sp)
    }
}

@Composable
private fun AlternativeCard(ranked: RankedAlternative, onClick: () -> Unit) {
    val alt = ranked.alternative
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(alt.title, fontSize = 19.sp)
            val meta = alt.durations.sortedBy { it.ordinal }.joinToString(" / ") { it.label }
            if (alt.note.isNotBlank()) Text(alt.note, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (meta.isNotEmpty()) Text(meta, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ranked.evidence?.let {
                Text(it.describe(alt.title), fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** The 60-second urge-surfing timer: a slowly breathing circle and one line of text. */
@Composable
private fun RideItOut(onGoHome: () -> Unit) {
    var secondsLeft by remember { mutableIntStateOf(RIDE_IT_OUT_SECONDS) }
    LaunchedEffect(Unit) {
        while (secondsLeft > 0) {
            delay(1_000)
            secondsLeft--
        }
    }
    val breath by rememberInfiniteTransition(label = "breath").animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4_000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathScale",
    )
    val color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(240.dp)) { drawCircle(color, radius = size.minDimension / 2 * breath) }
            Text(if (secondsLeft > 0) "$secondsLeft" else "", fontSize = 28.sp)
        }
        Spacer(Modifier.height(32.dp))
        Text(
            "Notice the urge. You don't have to act on it. It will pass.",
            fontSize = 22.sp,
            lineHeight = 32.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(40.dp))
        if (secondsLeft == 0) {
            Button(onClick = onGoHome, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Go to home screen", fontSize = 17.sp)
            }
        } else {
            TextButton(onClick = onGoHome) { Text("Go to home screen", fontSize = 16.sp) }
        }
    }
}

private fun title(info: InterventionInfo, appLabel: String) = when (info.mode) {
    InterventionMode.BLOCKED -> "$appLabel is blocked."
    InterventionMode.PAUSE -> "Before you open $appLabel"
    InterventionMode.OUT_OF_BUDGET -> "Today's time is used up."
    InterventionMode.OUTSIDE_HOURS -> "Not right now."
    InterventionMode.OPENS_CAP -> "That's today's opens."
}

private fun subtitle(info: InterventionInfo) = when (info.mode) {
    InterventionMode.BLOCKED -> "You decided to keep this one closed."
    InterventionMode.PAUSE -> "Take a moment first."
    InterventionMode.OUT_OF_BUDGET -> "Distracting apps are done until the day resets."
    InterventionMode.OUTSIDE_HOURS ->
        "Distracting apps are available ${info.allowedHoursText ?: "during your allowed hours"}."
    InterventionMode.OPENS_CAP -> "You set a limit on how many times a day these apps open."
}
