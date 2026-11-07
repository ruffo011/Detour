package dev.detour.guard.detect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the detector after a reboot and after Detour itself is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> DetectorService.startIfPermitted(context)
        }
    }
}
