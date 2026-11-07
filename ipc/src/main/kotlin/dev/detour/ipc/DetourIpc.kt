package dev.detour.ipc

/**
 * The contract between guard and filter. Only these names cross the app boundary.
 *
 * guard -> filter: blocklist groups (domains + whether each group is currently blocked).
 * filter -> guard: status only (running, heartbeat, Private DNS strict mode, blocked count today).
 * Nothing personal (usage, check-ins, intentions, alternatives) ever goes to filter.
 */
object DetourIpc {
    /** Signature-level permission declared in this module's manifest. */
    const val PERMISSION = "dev.detour.permission.IPC"

    const val GUARD_PACKAGE = "dev.detour.guard"
    const val FILTER_PACKAGE = "dev.detour.filter"
}
