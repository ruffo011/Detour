package dev.detour.filter

import android.content.Context
import android.net.ConnectivityManager
import java.time.LocalDate

/**
 * Filter's status: the only thing it reports back to guard. No domain names are ever stored —
 * just a count of blocked lookups for today.
 */
object FilterStatus {
    @Volatile var running = false
    @Volatile var lastHeartbeatMs = 0L

    private const val PREFS = "filter_status"
    private const val K_DAY = "day"
    private const val K_COUNT = "blocked"

    @Synchronized
    fun recordBlocked(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = LocalDate.now().toString()
        val count = if (prefs.getString(K_DAY, null) == today) prefs.getInt(K_COUNT, 0) else 0
        prefs.edit().putString(K_DAY, today).putInt(K_COUNT, count + 1).apply()
    }

    fun blockedToday(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (prefs.getString(K_DAY, null) == LocalDate.now().toString()) prefs.getInt(K_COUNT, 0) else 0
    }

    /**
     * Android's Private DNS in strict mode ("Private DNS provider hostname") sends DNS over TLS
     * straight to that provider, bypassing the filter. Automatic mode is fine.
     */
    fun isPrivateDnsStrict(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return false
        return lp.isPrivateDnsActive && lp.privateDnsServerName != null
    }
}
