package dev.detour.guard.intervene

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import dev.detour.core.alternatives.Outcome
import dev.detour.guard.detourApp
import dev.detour.guard.ui.theme.DetourTheme
import kotlinx.coroutines.launch

/**
 * "How was [walk]?" — shown once when you unlock the phone 10+ minutes after choosing an
 * alternative. One tap, skippable, never a notification.
 */
class CheckInActivity : ComponentActivity() {
    private var title by mutableStateOf<String?>(null)
    private var choiceId = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        choiceId = intent.getLongExtra(EXTRA_CHOICE, 0)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = answer(Outcome.SKIPPED)
            },
        )
        lifecycleScope.launch {
            val app = detourApp
            val choice = app.alternatives.choice(choiceId)
            if (choice == null || choice.outcome != null) {
                finish()
                return@launch
            }
            title = app.alternatives.byId(choice.alternativeId)?.title ?: "it"
        }
        setContent {
            DetourTheme {
                val t = title ?: return@DetourTheme
                Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    ) {
                        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text("How was “$t”?", fontSize = 22.sp, lineHeight = 30.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutcomeButton("better", Modifier.weight(1f)) { answer(Outcome.BETTER) }
                                OutcomeButton("same", Modifier.weight(1f)) { answer(Outcome.SAME) }
                                OutcomeButton("worse", Modifier.weight(1f)) { answer(Outcome.WORSE) }
                            }
                            OutcomeButton("didn't do it", Modifier.fillMaxWidth()) { answer(Outcome.DIDNT_DO) }
                            TextButton(onClick = { answer(Outcome.SKIPPED) }, modifier = Modifier.align(Alignment.End)) {
                                Text("Skip")
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Left without answering (e.g. Home): ask again next unlock rather than linger here.
        if (!isChangingConfigurations) finish()
    }

    private fun answer(outcome: Outcome) {
        val app = detourApp
        val id = choiceId
        app.appScope.launch { app.alternatives.setOutcome(id, outcome) }
        finish()
    }

    companion object {
        private const val EXTRA_CHOICE = "choice"

        fun launch(context: Context, choiceId: Long) {
            context.startActivity(
                Intent(context, CheckInActivity::class.java)
                    .putExtra(EXTRA_CHOICE, choiceId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS),
            )
        }
    }
}

@androidx.compose.runtime.Composable
private fun OutcomeButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier) { Text(label, fontSize = 16.sp) }
}
