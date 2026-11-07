package dev.detour.core.health

/** Something that leaves protection partly off. Messages are neutral: what, not blame. */
enum class HealthIssue(val message: String) {
    USAGE_ACCESS_OFF("Usage access is off, so Detour can't see which app is open"),
    OVERLAY_OFF("Display over other apps is off, so Detour can't show its pause screen"),
    BATTERY_RESTRICTED("Battery use is restricted, so Android may stop Detour"),
    NOTIFICATIONS_OFF("Notifications are off, so Detour can't tell you when protection is off"),
    DETECTOR_STOPPED("Detour's watcher isn't running"),
    FILTER_NOT_INSTALLED("Detour Filter isn't installed"),
    FILTER_UNREACHABLE("Detour can't talk to Detour Filter (are both built with the same key?)"),
    FILTER_OFF("The Chrome filter is off"),
    FILTER_UNRESPONSIVE("The Chrome filter stopped responding"),
    PRIVATE_DNS_STRICT("Private DNS is set to a specific provider, which lets Chrome skip the filter"),
}

/** What the filter reported, or null if it couldn't be asked. */
data class FilterReport(
    val running: Boolean,
    val lastHeartbeatMs: Long,
    val privateDnsStrict: Boolean,
    val blockedToday: Int,
)

data class HealthInputs(
    val usageAccess: Boolean,
    val overlay: Boolean,
    val batteryUnrestricted: Boolean,
    val notifications: Boolean,
    val detectorRunning: Boolean,
    val filterInstalled: Boolean,
    /** Null if filter is installed but its status couldn't be read. */
    val filter: FilterReport?,
    val nowMs: Long,
)

object HealthCheck {
    /** Filter heartbeats every 30 s; allow a few missed beats before calling it unresponsive. */
    const val FILTER_HEARTBEAT_STALE_MS = 150_000L

    fun issues(i: HealthInputs): List<HealthIssue> = buildList {
        if (!i.usageAccess) add(HealthIssue.USAGE_ACCESS_OFF)
        if (!i.overlay) add(HealthIssue.OVERLAY_OFF)
        if (!i.batteryUnrestricted) add(HealthIssue.BATTERY_RESTRICTED)
        if (!i.notifications) add(HealthIssue.NOTIFICATIONS_OFF)
        if (!i.detectorRunning) add(HealthIssue.DETECTOR_STOPPED)
        when {
            !i.filterInstalled -> add(HealthIssue.FILTER_NOT_INSTALLED)
            i.filter == null -> add(HealthIssue.FILTER_UNREACHABLE)
            !i.filter.running -> add(HealthIssue.FILTER_OFF)
            i.nowMs - i.filter.lastHeartbeatMs > FILTER_HEARTBEAT_STALE_MS -> add(HealthIssue.FILTER_UNRESPONSIVE)
        }
        if (i.filter?.privateDnsStrict == true) add(HealthIssue.PRIVATE_DNS_STRICT)
    }

    /** Issues present now that weren't before: each one is logged once as a lapse. */
    fun newlyAppeared(previous: Set<HealthIssue>, current: Collection<HealthIssue>): List<HealthIssue> =
        current.filter { it !in previous }

    /** One-line summary for the notification. */
    fun summary(issues: List<HealthIssue>): String = when (issues.size) {
        0 -> ""
        1 -> issues.single().message + "."
        else -> issues.joinToString("; ") { it.message } + "."
    }
}
