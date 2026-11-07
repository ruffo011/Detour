package dev.detour.filter

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Minimal status screen. All configuration happens in guard. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    StatusScreen()
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun StatusScreen() {
    val context = LocalContext.current
    var running by remember { mutableStateOf(FilterStatus.running) }
    var blocked by remember { mutableIntStateOf(0) }
    var strict by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            running = FilterStatus.running
            blocked = FilterStatus.blockedToday(context)
            strict = FilterStatus.isPrivateDnsStrict(context)
            delay(1_500)
        }
    }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (VpnService.prepare(context) == null) FilterVpnService.start(context)
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Detour Filter", style = MaterialTheme.typography.headlineMedium)
        Text(
            if (running) "On. Blocked sites won't load in Chrome. Blocked lookups today: $blocked."
            else "Off. Chrome isn't filtered right now.",
            style = MaterialTheme.typography.bodyLarge,
        )
        if (!running) {
            Button(
                onClick = {
                    val prepare = VpnService.prepare(context)
                    if (prepare == null) FilterVpnService.start(context) else consent.launch(prepare)
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Turn on the filter") }
        }
        if (strict) {
            Text(
                "Private DNS is set to a specific provider, which lets Chrome skip the filter. " +
                    "Set it to Automatic or Off: Settings → Network & internet → Private DNS.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Text("Keep it on", style = MaterialTheme.typography.titleMedium)
        Text(
            "Turn on Always-on VPN so Android restarts the filter by itself: Settings → Network & internet → " +
                "VPN → ⚙ next to Detour Filter → Always-on VPN.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }) {
            Text("Open VPN settings")
        }
        Text(
            "In Chrome, turn off Settings → Privacy and security → Use secure DNS. Otherwise Chrome may look up " +
                "sites in a way the filter can't see.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Text(
            "Only Chrome's address lookups pass through this app; page content never does. It keeps no record " +
                "of the sites you visit, only a count of blocked lookups for today. The blocklist is set in Detour.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
