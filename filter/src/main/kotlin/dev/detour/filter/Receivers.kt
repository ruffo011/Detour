package dev.detour.filter

import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.net.VpnService
import dev.detour.ipc.DetourIpc

/** guard says the blocklist changed. Only guard can send it (signature permission). */
class ConfigChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DetourIpc.ACTION_CONFIG_CHANGED) return
        if (FilterStatus.running) {
            FilterVpnService.start(context, FilterVpnService.ACTION_RELOAD)
        } else {
            // Not running: just refresh the cache so it's current when it starts.
            val pending = goAsync()
            Thread {
                try {
                    ConfigRepository(context).load()
                } finally {
                    pending.finish()
                }
            }.start()
        }
    }
}

/** Starts the filter after reboot if VPN consent was already given (Always-on VPN also does this). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (VpnService.prepare(context) == null) FilterVpnService.start(context)
    }
}

/**
 * filter -> guard: status only. Readable only by apps holding the signature permission (guard).
 * Querying it starts this process if needed, so "running = 0" also covers "process was dead".
 */
class StatusProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val ctx = context!!
        return MatrixCursor(DetourIpc.STATUS_COLUMNS).apply {
            addRow(
                arrayOf<Any>(
                    if (FilterStatus.running) 1 else 0,
                    FilterStatus.lastHeartbeatMs,
                    if (FilterStatus.isPrivateDnsStrict(ctx)) 1 else 0,
                    FilterStatus.blockedToday(ctx),
                ),
            )
        }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
