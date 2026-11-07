package dev.detour.guard

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.detour.guard.ui.AppsScreen
import dev.detour.guard.ui.AppsViewModel
import dev.detour.guard.ui.HomeScreen
import dev.detour.guard.ui.theme.DetourTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val hasNetwork = requestsNetworkPermission()
        setContent {
            DetourTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var showApps by rememberSaveable { mutableStateOf(false) }
                    if (showApps) {
                        BackHandler { showApps = false }
                        AppsScreen(viewModel<AppsViewModel>(), onBack = { showApps = false })
                    } else {
                        HomeScreen(hasNetworkPermission = hasNetwork, onOpenApps = { showApps = true })
                    }
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
        return info.requestedPermissions.orEmpty().any { it in NETWORK_PERMISSIONS }
    }

    companion object {
        private val NETWORK_PERMISSIONS = setOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.CHANGE_NETWORK_STATE",
        )
    }
}
