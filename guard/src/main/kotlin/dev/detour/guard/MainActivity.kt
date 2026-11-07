package dev.detour.guard

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.detour.guard.ui.theme.DetourTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val hasNetwork = requestsNetworkPermission()
        setContent {
            DetourTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PlaceholderScreen(hasNetwork)
                }
            }
        }
    }

    /** Reads this app's own installed permissions, so you can see the guarantee on the phone. */
    private fun requestsNetworkPermission(): Boolean {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        }
        val requested = info.requestedPermissions.orEmpty()
        return requested.any { it in NETWORK_PERMISSIONS }
    }

    companion object {
        private val NETWORK_PERMISSIONS = setOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.CHANGE_NETWORK_STATE",
        )
    }
}

@Composable
private fun PlaceholderScreen(hasNetwork: Boolean) {
    Column(
        modifier = Modifier
            .safeDrawingPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Detour", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Detour is installed. Setup will be added in the next update.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            if (hasNetwork) "Warning: this build can access the network. It should not."
            else "This app has no internet access, so nothing you enter here can leave the phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = if (hasNetwork) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
