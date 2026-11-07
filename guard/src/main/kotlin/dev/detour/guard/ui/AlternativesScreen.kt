package dev.detour.guard.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.detour.core.alternatives.Alternative
import dev.detour.core.alternatives.Duration
import dev.detour.core.alternatives.Feeling
import dev.detour.core.alternatives.Place
import dev.detour.guard.detourApp
import kotlinx.coroutines.launch

private val NEW_ALTERNATIVE = Alternative(0, "", feelings = emptySet(), durations = setOf(Duration.FIFTEEN_MIN))

@Composable
fun AlternativesScreen(onBack: () -> Unit) {
    val app = LocalContext.current.detourApp
    val alternatives by app.alternatives.alternatives.collectAsStateWithLifecycle(emptyList())
    var editing by remember { mutableStateOf<Alternative?>(null) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Back") }
            Text("Alternatives", style = MaterialTheme.typography.titleLarge)
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "Things you'd rather do. When an app is interrupted, Detour offers the ones that match " +
                        "how you feel, starting with what has helped you before.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Button(onClick = { editing = NEW_ALTERNATIVE }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text("Add an alternative")
                }
            }
            items(alternatives, key = { it.id }) { alt ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !alt.isRideItOut) { editing = alt },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(alt.title, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (alt.isRideItOut) "Built in · a 60-second timer, always offered"
                            else describe(alt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    editing?.let { alt ->
        EditDialog(
            initial = alt,
            onSave = {
                scope.launch { app.alternatives.save(it) }
                editing = null
            },
            onDelete = if (alt.id != 0L) {
                {
                    scope.launch { app.alternatives.delete(alt.id) }
                    editing = null
                }
            } else null,
            onDismiss = { editing = null },
        )
    }
}

private fun describe(alt: Alternative): String {
    val feelings = Feeling.entries.filter { it in alt.feelings }.joinToString(", ") { it.label }.ifEmpty { "any feeling" }
    val durations = Duration.entries.filter { it in alt.durations }.joinToString(" / ") { it.label }
    val places = if (alt.places.isEmpty()) "anywhere" else Place.entries.filter { it in alt.places }.joinToString(", ") { it.label }
    return listOf(feelings, durations, places).filter { it.isNotEmpty() }.joinToString(" · ")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditDialog(
    initial: Alternative,
    onSave: (Alternative) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(initial.title) }
    var note by remember { mutableStateOf(initial.note) }
    var feelings by remember { mutableStateOf(initial.feelings) }
    var durations by remember { mutableStateOf(initial.durations) }
    var places by remember { mutableStateOf(initial.places) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == 0L) "New alternative" else "Edit alternative") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("What") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth())
                Text("When I feel", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Feeling.entries.forEach { f ->
                        FilterChip(f in feelings, { feelings = feelings.toggle(f) }, label = { Text(f.label) })
                    }
                }
                Text("Takes about", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Duration.entries.forEach { d ->
                        FilterChip(d in durations, { durations = durations.toggle(d) }, label = { Text(d.label) })
                    }
                }
                Text("Where (none = anywhere)", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Place.entries.forEach { p ->
                        FilterChip(p in places, { places = places.toggle(p) }, label = { Text(p.label) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(initial.copy(title = title, note = note, feelings = feelings, durations = durations, places = places)) },
                enabled = title.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

private fun <T> Set<T>.toggle(item: T): Set<T> = if (item in this) this - item else this + item
