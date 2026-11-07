package dev.detour.guard.setup

import android.content.Context
import dev.detour.guard.detourApp
import dev.detour.guard.filter.FilterStatusReader
import dev.detour.guard.guardrail.GuardScreen
import dev.detour.guard.system.Permissions

/** One step of the setup checklist and whether it's done. */
data class SetupStep(val number: Int, val title: String, val done: Boolean)

/** Live status of every setup step, so a half-finished setup always shows what's missing. */
object SetupProgress {
    fun steps(context: Context): List<SetupStep> {
        val app = context.detourApp
        val checked = app.setup.checked.value
        val filter = if (FilterStatusReader.isInstalled(context)) FilterStatusReader.read(context) else null
        val choices = app.classifications.choices.value.orEmpty()
        val needsDecision = app.catalog.launchableApps().count { app.catalog.resolve(it.packageName, choices).needsDecision }
        // App info pages have no built-in default, so they're the ones that need calibrating.
        val calibrated = app.settingsGuard.isCalibrated(GuardScreen.GUARD_APP_INFO) &&
            app.settingsGuard.isCalibrated(GuardScreen.FILTER_APP_INFO)
        return listOf(
            SetupStep(1, "Permissions",
                Permissions.hasUsageAccess(context) && Permissions.hasOverlay(context) &&
                    Permissions.hasNotifications(context) && Permissions.isBatteryUnrestricted(context)),
            SetupStep(2, "Detour Filter for Chrome",
                filter?.running == true && filter.privateDnsStrict.not() &&
                    SetupState.CHECK_ALWAYS_ON in checked && SetupState.CHECK_CHROME_DNS in checked),
            SetupStep(3, "Classify apps and choose your browser", needsDecision == 0),
            SetupStep(4, "Your alternatives", SetupState.CHECK_ALTERNATIVES in checked),
            SetupStep(5, "Settings guard calibration", calibrated),
            SetupStep(6, "Backup folder", app.backup.status.value.folderUri != null),
        )
    }
}
