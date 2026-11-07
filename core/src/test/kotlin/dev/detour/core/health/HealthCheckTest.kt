package dev.detour.core.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthCheckTest {
    private val now = 1_000_000L
    private val goodFilter = FilterReport(running = true, lastHeartbeatMs = now - 10_000, privateDnsStrict = false, blockedToday = 3)
    private val healthy = HealthInputs(
        usageAccess = true, overlay = true, batteryUnrestricted = true, notifications = true,
        detectorRunning = true, filterInstalled = true, filter = goodFilter, nowMs = now,
    )

    @Test
    fun healthyHasNoIssues() {
        assertTrue(HealthCheck.issues(healthy).isEmpty())
    }

    @Test
    fun eachPermissionIsReported() {
        val issues = HealthCheck.issues(
            healthy.copy(usageAccess = false, overlay = false, batteryUnrestricted = false, notifications = false, detectorRunning = false),
        )
        assertEquals(
            listOf(
                HealthIssue.USAGE_ACCESS_OFF, HealthIssue.OVERLAY_OFF, HealthIssue.BATTERY_RESTRICTED,
                HealthIssue.NOTIFICATIONS_OFF, HealthIssue.DETECTOR_STOPPED,
            ),
            issues,
        )
    }

    @Test
    fun filterStates() {
        assertEquals(listOf(HealthIssue.FILTER_NOT_INSTALLED), HealthCheck.issues(healthy.copy(filterInstalled = false, filter = null)))
        assertEquals(listOf(HealthIssue.FILTER_UNREACHABLE), HealthCheck.issues(healthy.copy(filter = null)))
        assertEquals(listOf(HealthIssue.FILTER_OFF), HealthCheck.issues(healthy.copy(filter = goodFilter.copy(running = false))))
        assertEquals(
            listOf(HealthIssue.FILTER_UNRESPONSIVE),
            HealthCheck.issues(healthy.copy(filter = goodFilter.copy(lastHeartbeatMs = now - 151_000))),
        )
        assertTrue(HealthCheck.issues(healthy.copy(filter = goodFilter.copy(lastHeartbeatMs = now - 149_000))).isEmpty())
    }

    @Test
    fun privateDnsStrictIsABypass() {
        assertEquals(
            listOf(HealthIssue.PRIVATE_DNS_STRICT),
            HealthCheck.issues(healthy.copy(filter = goodFilter.copy(privateDnsStrict = true))),
        )
        // Reported even when the filter is off: it would bypass it once it's back on.
        assertEquals(
            listOf(HealthIssue.FILTER_OFF, HealthIssue.PRIVATE_DNS_STRICT),
            HealthCheck.issues(healthy.copy(filter = goodFilter.copy(running = false, privateDnsStrict = true))),
        )
    }

    @Test
    fun onlyNewIssuesAreLogged() {
        val prev = setOf(HealthIssue.FILTER_OFF)
        assertEquals(
            listOf(HealthIssue.OVERLAY_OFF),
            HealthCheck.newlyAppeared(prev, listOf(HealthIssue.FILTER_OFF, HealthIssue.OVERLAY_OFF)),
        )
        assertTrue(HealthCheck.newlyAppeared(prev, emptyList()).isEmpty())
    }

    @Test
    fun summaryIsOneLine() {
        assertEquals("", HealthCheck.summary(emptyList()))
        assertEquals("The Chrome filter is off.", HealthCheck.summary(listOf(HealthIssue.FILTER_OFF)))
        assertTrue(!HealthCheck.summary(HealthIssue.entries).contains('\n'))
    }
}
