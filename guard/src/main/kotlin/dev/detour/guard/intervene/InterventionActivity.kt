package dev.detour.guard.intervene

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.detour.guard.detourApp
import dev.detour.guard.ui.theme.DetourTheme

/**
 * Full-screen interruption shown over a Blocked app.
 *
 * Why an activity (not only an overlay window): an activity gets real focus, the keyboard
 * (needed for typed intentions in milestone 3), the back gesture, and proper lifecycle, and it
 * pauses the app underneath. The detector can start it from the background because guard holds
 * "Display over other apps" and keeps a tiny overlay visible (see OverlayKeepAlive).
 *
 * Leaving always goes to the home screen, never back into the app:
 * - back gesture / button -> home
 * - "Go to home screen" -> home
 * - home or recents -> this screen finishes itself, so it never lingers in recents
 * Its own task (taskAffinity + singleTask) means repeated launches reuse one instance
 * (onNewIntent) instead of stacking up, which avoids flicker in quick re-launch loops.
 */
class InterventionActivity : ComponentActivity() {
    private var targetPackage by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        targetPackage = intent.getStringExtra(EXTRA_PACKAGE)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = goHome()
            },
        )
        setContent {
            DetourTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val pkg = targetPackage
                    BlockedScreen(
                        appLabel = pkg?.let { detourApp.catalog.label(it) } ?: "This app",
                        onGoHome = ::goHome,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        targetPackage = intent.getStringExtra(EXTRA_PACKAGE)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) finish()
    }

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finish()
    }

    companion object {
        private const val EXTRA_PACKAGE = "package"

        fun launch(context: Context, packageName: String) {
            context.startActivity(
                Intent(context, InterventionActivity::class.java)
                    .putExtra(EXTRA_PACKAGE, packageName)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
                    ),
            )
        }
    }
}

@Composable
private fun BlockedScreen(appLabel: String, onGoHome: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            "$appLabel is blocked.",
            fontSize = 34.sp,
            lineHeight = 42.sp,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "You decided to keep this one closed. Take a breath, then head home.",
            fontSize = 20.sp,
            lineHeight = 30.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(48.dp))
        Button(onClick = onGoHome, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("Go to home screen", fontSize = 18.sp)
        }
    }
}
