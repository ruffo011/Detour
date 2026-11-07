package dev.detour.filter

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks the DNS servers of the real (non-VPN) networks, so allowed lookups go to the same
 * resolver the phone would use anyway.
 *
 * Only networks *without* the VPN transport are considered: once our VPN is up, a plain
 * "default network" listener can report the VPN's own fake DNS servers, and forwarding to
 * those loops back into nowhere (that was the "no site loads" bug).
 */
class UpstreamDns(private val cm: ConnectivityManager) {
    private data class Info(val dns: List<InetAddress>, val validated: Boolean)

    private val networks = ConcurrentHashMap<Network, Info>()

    @Volatile var servers: List<InetAddress> = emptyList()
        private set

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
            val lp = cm.getLinkProperties(network)
            networks[network] = Info(lp?.dnsServers.orEmpty(), caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            recompute()
        }

        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
            val existing = networks[network] ?: return // only networks that passed the capability check
            networks[network] = existing.copy(dns = lp.dnsServers)
            recompute()
        }

        override fun onLost(network: Network) {
            networks.remove(network)
            recompute()
        }
    }

    fun start() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        cm.registerNetworkCallback(request, callback)
    }

    fun stop() {
        runCatching { cm.unregisterNetworkCallback(callback) }
    }

    private fun recompute() {
        // Prefer the system's current default if it's a real network, then validated networks.
        val active = cm.activeNetwork
        val ordered = networks.entries.sortedWith(
            compareByDescending<Map.Entry<Network, Info>> { it.key == active }.thenByDescending { it.value.validated },
        )
        servers = sanitize(ordered.flatMap { it.value.dns })
    }

    companion object {
        private val OWN_FAKE_SERVERS = setOf(
            InetAddress.getByName(FilterVpnService.DNS_V4),
            InetAddress.getByName(FilterVpnService.DNS_V6),
        )

        /** Never forward to our own fake addresses, whatever a network reports. */
        fun sanitize(servers: List<InetAddress>): List<InetAddress> =
            servers.filter { it !in OWN_FAKE_SERVERS && !it.isAnyLocalAddress && !it.isLoopbackAddress }.distinct()
    }
}
