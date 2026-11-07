package dev.detour.guard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.detour.core.settings.SettingsOp
import dev.detour.dns.BlocklistGroup
import dev.detour.guard.data.BlocklistRepository
import dev.detour.guard.detourApp
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun BlocklistScreen(onBack: () -> Unit) {
    val app = LocalContext.current.detourApp
    val groups by app.blocklist.groups.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val change: (SettingsOp, String) -> Unit = { op, d -> scope.launch { submitChange(context, op, d) } }

    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Back") }
            Text("Browser blocklist", style = MaterialTheme.typography.titleLarge)
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(
                    "Sites that won't load in Chrome while Detour Filter is on. A domain also covers everything " +
                        "under it (youtube.com covers m.youtube.com). Blocking applies right away; unblocking or " +
                        "removing a domain waits 24 hours.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(groups, key = { it.id }) { group ->
                GroupCard(
                    group = group,
                    onBlocked = { change(SettingsOp.SetGroupBlocked(group.id, it), "${group.name} ${if (it) "blocked" else "unblocked"}") },
                    onAdd = { input, done ->
                        val domain = BlocklistRepository.normalizeDomain(input)
                        if (domain != null) change(SettingsOp.AddDomain(group.id, domain), "Block $domain")
                        done(domain != null)
                    },
                    onRemove = { change(SettingsOp.RemoveDomain(group.id, it), "Unblock $it (${group.name})") },
                    onDelete = { change(SettingsOp.DeleteGroup(group.id), "Delete group ${group.name}") },
                )
            }
            item {
                OutlinedButton(
                    onClick = { change(SettingsOp.AddGroup("custom-" + UUID.randomUUID().toString().take(8), "My sites"), "New group") },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Add a group")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupCard(
    group: BlocklistGroup,
    onBlocked: (Boolean) -> Unit,
    onAdd: (String, (Boolean) -> Unit) -> Unit,
    onRemove: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var newDomain by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(group.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (group.blocked) "Blocked in Chrome" else "Not blocked right now",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = group.blocked, onCheckedChange = onBlocked)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                group.domains.forEach { d -> AssistChip(onClick = { onRemove(d) }, label = { Text("$d  ×") }) }
            }
            val submit = {
                if (newDomain.isNotBlank()) {
                    onAdd(newDomain) { ok ->
                        invalid = !ok
                        if (ok) newDomain = ""
                    }
                }
            }
            OutlinedTextField(
                value = newDomain,
                onValueChange = {
                    newDomain = it
                    invalid = false
                },
                label = { Text("Add a domain, e.g. tiktok.com") },
                isError = invalid,
                supportingText = if (invalid) ({ Text("That doesn't look like a domain") }) else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Row {
                TextButton(onClick = submit) { Text("Add") }
                if (group.id.startsWith("custom-")) TextButton(onClick = onDelete) { Text("Delete group") }
            }
        }
    }
}
