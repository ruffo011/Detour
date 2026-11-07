package dev.detour.filter

import android.content.Context
import android.util.Log
import dev.detour.dns.DefaultBlocklist
import dev.detour.dns.FilterConfig
import dev.detour.ipc.DetourIpc
import java.io.File

/**
 * Gets the blocklist from guard (signature-protected ContentProvider) and caches it, so the
 * filter keeps working if guard is killed or briefly unavailable. Falls back to the built-in
 * defaults only if it has never heard from guard.
 */
class ConfigRepository(private val context: Context) {
    private val cacheFile = File(context.filesDir, "filter-config.txt")

    fun load(): FilterConfig {
        fetchFromGuard()?.let { text ->
            FilterConfig.decode(text)?.let { config ->
                runCatching { cacheFile.writeText(text) }
                return config
            }
        }
        return cached()
    }

    fun cached(): FilterConfig =
        runCatching { FilterConfig.decode(cacheFile.readText()) }.getOrNull() ?: DefaultBlocklist.CONFIG

    private fun fetchFromGuard(): String? = try {
        context.contentResolver.query(DetourIpc.CONFIG_URI, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(DetourIpc.COLUMN_CONFIG)) else null
        }
    } catch (e: Exception) {
        // guard not installed, or not signed with the same key (then the permission is denied).
        Log.w("ConfigRepository", "Could not read config from guard: ${e.javaClass.simpleName}")
        null
    }
}
