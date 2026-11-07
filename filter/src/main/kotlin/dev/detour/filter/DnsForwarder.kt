package dev.detour.filter

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.VpnService
import android.util.Log
import dev.detour.dns.DnsQuery
import dev.detour.dns.DnsResponses
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Tracks the DNS servers of the real (non-VPN) network, so allowed lookups go to the same
 * resolver the phone would use anyway. Updates on network changes.
 */
class UpstreamDns(private val cm: ConnectivityManager) {
    @Volatile var servers: List<InetAddress> = emptyList()
        private set

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
            servers = lp.dnsServers
        }

        override fun onLost(network: Network) {
            servers = emptyList()
        }
    }

    fun start() {
        // filter itself is not inside its own VPN, so its default network is the real one.
        servers = cm.getLinkProperties(cm.activeNetwork)?.dnsServers.orEmpty()
        cm.registerDefaultNetworkCallback(callback)
    }

    fun stop() {
        runCatching { cm.unregisterNetworkCallback(callback) }
    }
}

/**
 * Sends an allowed query to the upstream resolver over sockets excluded from the VPN
 * ([VpnService.protect]) and returns the answer. Handles timeouts (tries the next server) and
 * truncated answers (retries over TCP; returns the full answer if it fits the client's UDP size).
 * Domain names are never logged.
 */
class DnsForwarder(
    private val vpn: VpnService,
    private val upstream: () -> List<InetAddress>,
    private val fallback: () -> List<String>,
) {
    fun resolve(query: DnsQuery): ByteArray? {
        val servers = (upstream() + fallback().mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() })
            .distinct().take(MAX_SERVERS)
        for (server in servers) {
            val udp = runCatching { udp(server, query) }.getOrNull() ?: continue
            if (!DnsResponses.isTruncated(udp)) return udp
            val tcp = runCatching { tcp(server, query) }.getOrNull()
            return if (tcp != null && tcp.size <= DnsResponses.maxUdpSize(query)) tcp else udp
        }
        Log.w(TAG, "No upstream resolver answered (${servers.size} tried)")
        return null
    }

    private fun udp(server: InetAddress, query: DnsQuery): ByteArray? {
        DatagramSocket().use { socket ->
            vpn.protect(socket)
            socket.soTimeout = TIMEOUT_MS
            socket.send(DatagramPacket(query.raw, query.raw.size, InetSocketAddress(server, 53)))
            val buf = ByteArray(4096)
            val deadline = System.currentTimeMillis() + TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buf, buf.size)
                socket.receive(packet)
                // Ignore stray datagrams that don't answer this query.
                if (packet.length >= 2 && ((buf[0].toInt() and 0xFF) shl 8 or (buf[1].toInt() and 0xFF)) == query.id) {
                    return buf.copyOf(packet.length)
                }
            }
            return null
        }
    }

    private fun tcp(server: InetAddress, query: DnsQuery): ByteArray {
        Socket().use { socket ->
            vpn.protect(socket)
            socket.connect(InetSocketAddress(server, 53), TIMEOUT_MS)
            socket.soTimeout = TIMEOUT_MS
            val out = DataOutputStream(socket.getOutputStream())
            out.writeShort(query.raw.size)
            out.write(query.raw)
            out.flush()
            val input = DataInputStream(socket.getInputStream())
            val len = input.readUnsignedShort()
            return ByteArray(len).also { input.readFully(it) }
        }
    }

    companion object {
        private const val TAG = "DnsForwarder"
        private const val TIMEOUT_MS = 2_500
        private const val MAX_SERVERS = 3
    }
}
