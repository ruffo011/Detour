package dev.detour.guard.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.detour.core.AppCategory
import dev.detour.core.CategoryReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class Section(val title: String, val rows: List<AppRow>)

@Composable
fun AppsScreen(viewModel: AppsViewModel, onBack: () -> Unit) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Back") }
            Text("App categories", style = MaterialTheme.typography.titleLarge)
        }
        val all = rows
        if (all == null) {
            Text("Loading apps…", Modifier.padding(24.dp))
            return@Column
        }
        val sections = listOf(
            Section("Needs a decision", all.filter { it.resolution.needsDecision }),
            Section("Blocked", all.filter { it.resolution.editable && !it.resolution.needsDecision && it.resolution.category == AppCategory.BLOCKED }),
            Section("Distracting", all.filter { it.resolution.editable && !it.resolution.needsDecision && it.resolution.category == AppCategory.DISTRACTING }),
            Section("Allowed", all.filter { it.resolution.editable && !it.resolution.needsDecision && it.resolution.category == AppCategory.ALLOWED }),
            Section("Set by Detour", all.filter { !it.resolution.editable }),
        ).filter { it.rows.isNotEmpty() }

        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
                Text(
                    "Blocked apps can't be opened. Distracting apps share one daily time budget. Allowed apps " +
                        "are never interrupted. Stricter choices apply right away; looser ones take 24 hours.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            for (section in sections) {
                item(key = "header-${section.title}") {
                    Text(
                        section.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 4.dp),
                    )
                }
                items(section.rows, key = { "${section.title}-${it.app.packageName}" }) { row ->
                    AppRowItem(row, onSelect = { viewModel.setCategory(row.app, it) })
                    HorizontalDivider(Modifier.padding(horizontal = 24.dp))
                }
            }
        }
    }
}

@Composable
private fun AppRowItem(row: AppRow, onSelect: (AppCategory) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(row.app.packageName)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(row.app.label, style = MaterialTheme.typography.bodyLarge)
                reasonText(row)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (row.resolution.editable) {
            Row(Modifier.padding(start = 52.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (cat in AppCategory.entries) {
                    FilterChip(
                        selected = !row.resolution.needsDecision && row.resolution.category == cat,
                        onClick = { onSelect(cat) },
                        label = { Text(cat.displayName()) },
                    )
                }
            }
        }
    }
}

private fun reasonText(row: AppRow): String? = when (row.resolution.reason) {
    CategoryReason.ESSENTIAL -> "Always allowed (essential)"
    CategoryReason.ALLOWED_BROWSER -> "Your browser. Goes through Detour Filter"
    CategoryReason.OTHER_BROWSER -> "Blocked: only one browser is allowed, so all browsing is filtered"
    CategoryReason.SUGGESTED -> "Suggested"
    CategoryReason.UNCLASSIFIED -> "Treated as Distracting until you choose"
    CategoryReason.NEW_APP -> "New: Allowed for now, Distracting ${timeUntil(row.resolution.decideByMs ?: 0)} unless you choose"
    CategoryReason.USER_CHOICE, CategoryReason.NOT_LAUNCHABLE -> null
}

private fun AppCategory.displayName() = when (this) {
    AppCategory.ALLOWED -> "Allowed"
    AppCategory.DISTRACTING -> "Distracting"
    AppCategory.BLOCKED -> "Blocked"
}

@Composable
private fun AppIcon(packageName: String) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap()
            }.getOrNull()
        }
    }
    Box(Modifier.size(40.dp)) {
        icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(40.dp)) }
    }
}
