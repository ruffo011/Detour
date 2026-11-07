package dev.detour.guard.filter

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import dev.detour.core.health.FilterReport
import dev.detour.guard.detourApp
import dev.detour.ipc.DetourIpc
import kotlinx.coroutines.runBlocking

/**
 * guard -> filter: serves the blocklist config. Exported, but readable only with the
 * signature-level permission, i.e. only by filter signed with the same key.
 * It returns the blocklist and browser choice — never usage data or anything personal.
 */
class FilterConfigProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val app = context!!.detourApp
        val text = runBlocking { app.blocklist.buildConfig().encode() }
        return MatrixCursor(arrayOf(DetourIpc.COLUMN_CONFIG)).apply { addRow(arrayOf(text)) }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

/** filter -> guard: reads filter's status (running, heartbeat, Private DNS strict, blocked count). */
object FilterStatusReader {
    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(DetourIpc.FILTER_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** Null if filter isn't installed or refuses (e.g. signed with a different key). */
    fun read(context: Context): FilterReport? = try {
        context.contentResolver.query(DetourIpc.STATUS_URI, null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null
            FilterReport(
                running = c.getInt(c.getColumnIndexOrThrow(DetourIpc.COLUMN_RUNNING)) == 1,
                lastHeartbeatMs = c.getLong(c.getColumnIndexOrThrow(DetourIpc.COLUMN_LAST_HEARTBEAT)),
                privateDnsStrict = c.getInt(c.getColumnIndexOrThrow(DetourIpc.COLUMN_PRIVATE_DNS_STRICT)) == 1,
                blockedToday = c.getInt(c.getColumnIndexOrThrow(DetourIpc.COLUMN_BLOCKED_TODAY)),
            )
        }
    } catch (_: Exception) {
        null
    }

    fun launchIntent(context: Context) = context.packageManager.getLaunchIntentForPackage(DetourIpc.FILTER_PACKAGE)
}
