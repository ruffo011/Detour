package dev.detour.ipc

import android.net.Uri

/**
 * The contract between guard and filter. Only these names cross the app boundary, and every one
 * of them requires the signature-level [PERMISSION], so no other app can read or send them.
 *
 * guard -> filter: blocklist groups (domains + whether each group is currently blocked), the
 *   allowed browser, and a fallback resolver. Read by filter from [CONFIG_URI]; guard sends
 *   [ACTION_CONFIG_CHANGED] when it changes.
 * filter -> guard: status only, read by guard from [STATUS_URI].
 * Nothing personal (usage, check-ins, intentions, alternatives) ever goes to filter.
 */
object DetourIpc {
    /** Signature-level permission declared in this module's manifest. */
    const val PERMISSION = "dev.detour.permission.IPC"

    const val GUARD_PACKAGE = "dev.detour.guard"
    const val FILTER_PACKAGE = "dev.detour.filter"

    // guard -> filter -----------------------------------------------------------------------
    const val CONFIG_AUTHORITY = "dev.detour.guard.filterconfig"
    val CONFIG_URI: Uri = Uri.parse("content://$CONFIG_AUTHORITY/config")

    /** Single row, single column: the [dev.detour.dns.FilterConfig] text. */
    const val COLUMN_CONFIG = "config"

    const val ACTION_CONFIG_CHANGED = "dev.detour.action.FILTER_CONFIG_CHANGED"

    // filter -> guard -----------------------------------------------------------------------
    const val STATUS_AUTHORITY = "dev.detour.filter.status"
    val STATUS_URI: Uri = Uri.parse("content://$STATUS_AUTHORITY/status")

    const val COLUMN_RUNNING = "running"                    // 1 / 0
    const val COLUMN_LAST_HEARTBEAT = "last_heartbeat_ms"   // epoch ms, 0 if never
    const val COLUMN_PRIVATE_DNS_STRICT = "private_dns_strict" // 1 / 0
    const val COLUMN_BLOCKED_TODAY = "blocked_today"        // count only, never names

    val STATUS_COLUMNS = arrayOf(COLUMN_RUNNING, COLUMN_LAST_HEARTBEAT, COLUMN_PRIVATE_DNS_STRICT, COLUMN_BLOCKED_TODAY)
}
