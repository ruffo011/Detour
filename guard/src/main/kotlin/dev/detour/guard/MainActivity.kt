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
import dev.detour.guard.ui.AlternativesScreen
import dev.detour.guard.ui.AppsScreen
import dev.detour.guard.ui.AppsViewModel
import dev.detour.guard.ui.BackupScreen
import dev.detour.guard.ui.BlocklistScreen
import dev.detour.guard.ui.PendingChangesScreen
import dev.detour.guard.ui.SettingsGuardScreen
import dev.detour.guard.ui.BudgetScreen
import dev.detour.guard.ui.HomeScreen
import dev.detour.guard.ui.theme.DetourTheme

private enum class Screen { HOME, APPS, BUDGET, ALTERNATIVES, BLOCKLIST, PENDING, SETTINGS_GUARD, BACKUP }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val hasNetwork = requestsNetworkPermission()
        setContent {
            DetourTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
                    val back = { screen = Screen.HOME }
                    if (screen != Screen.HOME) BackHandler(onBack = back)
                    when (screen) {
                        Screen.HOME -> HomeScreen(
                            hasNetworkPermission = hasNetwork,
                            onOpenApps = { screen = Screen.APPS },
                            onOpenBudget = { screen = Screen.BUDGET },
                            onOpenAlternatives = { screen = Screen.ALTERNATIVES },
                            onOpenBlocklist = { screen = Screen.BLOCKLIST },
                            onOpenPending = { screen = Screen.PENDING },
                            onOpenSettingsGuard = { screen = Screen.SETTINGS_GUARD },
                            onOpenBackup = { screen = Screen.BACKUP },
                        )
                        Screen.BLOCKLIST -> BlocklistScreen(onBack = back)
                        Screen.PENDING -> PendingChangesScreen(onBack = back)
                        Screen.SETTINGS_GUARD -> SettingsGuardScreen(onBack = back)
                        Screen.BACKUP -> BackupScreen(onBack = back)
                        Screen.APPS -> AppsScreen(viewModel<AppsViewModel>(), onBack = back)
                        Screen.BUDGET -> BudgetScreen(onBack = back)
                        Screen.ALTERNATIVES -> AlternativesScreen(onBack = back)
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
