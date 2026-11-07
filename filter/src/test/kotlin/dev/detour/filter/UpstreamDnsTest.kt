package dev.detour.filter

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress

class UpstreamDnsTest {
    private fun ip(s: String): InetAddress = InetAddress.getByName(s)

    @Test
    fun neverForwardsToOurOwnFakeServers() {
        // What the phone reported once the VPN was up: the VPN's own DNS servers.
        val reported = listOf(ip("192.0.2.53"), ip("fd00:de70:f17e::53"))
        assertEquals(emptyList<InetAddress>(), UpstreamDns.sanitize(reported))
    }

    @Test
    fun keepsRealServersInOrder() {
        val reported = listOf(
            ip("2001:4888:3a:ff00:304:d::"), ip("192.0.2.53"), ip("198.224.149.135"),
            ip("198.224.149.135"), ip("127.0.0.1"), ip("0.0.0.0"),
        )
        assertEquals(listOf(ip("2001:4888:3a:ff00:304:d::"), ip("198.224.149.135")), UpstreamDns.sanitize(reported))
    }
}
