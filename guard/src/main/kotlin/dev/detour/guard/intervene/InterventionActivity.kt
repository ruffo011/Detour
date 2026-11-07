package dev.detour.guard.intervene

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dev.detour.core.alternatives.Alternative
import dev.detour.core.alternatives.Feeling
import dev.detour.guard.detourApp
import dev.detour.guard.ui.theme.DetourTheme
import kotlinx.coroutines.launch

/**
 * Full-screen interruption shown over a Blocked app, or a Distracting app that needs a pause
 * or is out of budget / outside allowed hours.
 *
 * Why an activity (not only an overlay window): an activity gets real focus, the keyboard
 * (for the typed intention), the back gesture, and proper lifecycle, and it pauses the app
 * underneath. The detector can start it from the background because guard holds
 * "Display over other apps" and keeps a tiny overlay visible (see OverlayKeepAlive).
 *
 * Leaving always goes to the home screen, never back into the app — except "Continue to [app]"
 * after the full pause. Its own task (taskAffinity + singleTask) means repeated launches reuse one
 * instance (onNewIntent) instead of stacking up, which avoids flicker in quick re-launch loops.
 */
class InterventionActivity : ComponentActivity() {
    private var info by mutableStateOf<InterventionInfo?>(null)
    private var shownAtMs by mutableLongStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readIntent(intent)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = goHome()
            },
        )
        val app = detourApp
        setContent {
            DetourTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val current = info ?: return@Surface
                    val alternatives by app.alternatives.alternatives.collectAsStateWithLifecycle(emptyList())
                    val history by app.alternatives.history.collectAsStateWithLifecycle(emptyList())
                    // A new launch (different app or a fresh attempt) starts the flow from scratch.
                    key(current, shownAtMs) {
                        InterventionScreen(
                            info = current,
                            appLabel = app.catalog.label(current.packageName),
                            alternatives = alternatives,
                            history = history,
                            shownAtMs = shownAtMs,
                            localTime = app.settings.clock().localTimeAt(System.currentTimeMillis()),
                            onChooseAlternative = ::chooseAlternative,
                            onStartRideItOut = { alt, feeling -> record(alt, feeling) },
                            onContinue = ::continueToApp,
                            onGoHome = ::goHome,
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) finish()
    }

    private fun readIntent(intent: Intent) {
        info = InterventionInfo.from(intent)
        shownAtMs = System.currentTimeMillis()
    }

    private fun record(alt: Alternative, feeling: Feeling) {
        val pkg = info?.packageName
        detourApp.appScope.launch { detourApp.alternatives.recordChoice(alt.id, feeling, pkg) }
    }

    private fun chooseAlternative(alt: Alternative, feeling: Feeling) {
        record(alt, feeling)
        goHome()
    }

    private fun continueToApp(feeling: Feeling, intention: String) {
        val current = info ?: return goHome()
        val app = detourApp
        if (current.mode == InterventionMode.SETTINGS_GUARD) {
            // Let that app's settings screens through for a few minutes, and return to the page.
            app.appScope.launch { app.alternatives.saveIntention("settings:${current.packageName}", intention, null) }
            app.settingsGuardPolicy.permit(current.packageName, System.currentTimeMillis())
            finish()
            return
        }
        app.appScope.launch { app.alternatives.saveIntention(current.packageName, intention, feeling) }
        app.session.permit(current.packageName, System.currentTimeMillis(), app.settings.current.sessionCheckInMinutes)
        val launch = packageManager.getLaunchIntentForPackage(current.packageName)
        if (launch != null) {
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
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
        fun launch(context: Context, info: InterventionInfo) {
            context.startActivity(
                info.writeTo(Intent(context, InterventionActivity::class.java))
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
                    ),
            )
        }
    }
}
