package dev.detour.filter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import dev.detour.dns.DnsPolicy
import dev.detour.dns.DnsQuery
import dev.detour.dns.DnsResponses
import dev.detour.dns.DnsVerdict
import dev.detour.dns.FilterConfig
import dev.detour.dns.MalformedDnsException
import dev.detour.dns.UdpPacket
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * A local, DNS-only VPN that applies to the one allowed browser (Chrome by default).
 *
 * The tunnel's DNS server is a fake address in a reserved documentation/ULA range, and the only
 * routes into the tunnel are that address (IPv4 and IPv6). So only the browser's DNS lookups
 * enter this app; all page traffic goes out normally and never passes through here.
 *
 * Blocked names get NXDOMAIN locally. Everything else is forwarded to the real network's
 * resolver over protected sockets. Names are handled in memory only and never stored or logged.
 */
class FilterVpnService : VpnService() {
    private var tunnel: ParcelFileDescriptor? = null
    private var reader: Thread? = null
    private var output: FileOutputStream? = null
    private lateinit var upstream: UpstreamDns
    private lateinit var forwarder: DnsForwarder
    private lateinit var configRepo: ConfigRepository
    private var heartbeat: ScheduledExecutorService? = null
    private val workers = ThreadPoolExecutor(2, 8, 30, TimeUnit.SECONDS, java.util.concurrent.LinkedBlockingQueue(256))

    @Volatile private var config: FilterConfig? = null
    @Volatile private var policy: DnsPolicy? = null
    private var tunnelBrowser: String? = null

    override fun onCreate() {
        super.onCreate()
        configRepo = ConfigRepository(this)
        upstream = UpstreamDns(getSystemService(ConnectivityManager::class.java))
        forwarder = DnsForwarder(this, { upstream.servers }, { config?.fallbackDns.orEmpty() })
        upstream.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> Thread { applyConfig(configRepo.load()) }.start()
        }
        return START_STICKY
    }

    /** Uses a new config; rebuilds the tunnel only if the allowed browser changed. */
    @Synchronized
    private fun applyConfig(newConfig: FilterConfig) {
        config = newConfig
        policy = DnsPolicy(newConfig)
        if (tunnel == null || tunnelBrowser != newConfig.allowedBrowser) establish(newConfig.allowedBrowser)
    }

    @Synchronized
    private fun establish(browser: String) {
        val builder = Builder()
            .setSession("Detour Filter")
            .addAddress(TUN_V4, 32)
            .addAddress(TUN_V6, 128)
            .addDnsServer(DNS_V4)
            .addDnsServer(DNS_V6)
            .addRoute(DNS_V4, 32)
            .addRoute(DNS_V6, 128)
            .setMtu(MTU)
            .setBlocking(true)
            .setMetered(false)
        try {
            builder.addAllowedApplication(browser)
        } catch (_: PackageManager.NameNotFoundException) {
            // Never leave the allowed list empty: that would put *every* app in the VPN.
            Log.w(TAG, "Allowed browser not installed; tunnel limited to this app")
            builder.addAllowedApplication(packageName)
        }
        val newTunnel = try {
            builder.establish()
        } catch (e: Exception) {
            Log.w(TAG, "establish failed", e)
            null
        } ?: run {
            // Not prepared (no VPN consent) or revoked.
            FilterStatus.running = false
            stopSelf()
            return
        }

        closeTunnel()
        tunnel = newTunnel
        tunnelBrowser = browser
        output = FileOutputStream(newTunnel.fileDescriptor)
        val input = FileInputStream(newTunnel.fileDescriptor)
        reader = Thread({ readLoop(input) }, "dns-tunnel").also { it.start() }
        FilterStatus.running = true
        FilterStatus.lastHeartbeatMs = System.currentTimeMillis()
        if (heartbeat == null) {
            heartbeat = Executors.newSingleThreadScheduledExecutor().also {
                it.scheduleWithFixedDelay({ FilterStatus.lastHeartbeatMs = System.currentTimeMillis() }, 30, 30, TimeUnit.SECONDS)
            }
        }
    }

    private fun readLoop(input: FileInputStream) {
        val buf = ByteArray(MTU)
        while (!Thread.currentThread().isInterrupted) {
            val n = try {
                input.read(buf)
            } catch (_: IOException) {
                break // tunnel closed
            }
            if (n <= 0) continue
            val udp = UdpPacket.parse(buf, n) ?: continue
            if (udp.dstPort != 53) continue
            handle(udp)
        }
    }

    private fun handle(udp: UdpPacket) {
        val query = try {
            DnsQuery.parse(udp.payload)
        } catch (_: MalformedDnsException) {
            return
        }
        if (query.isResponse) return
        if (query.opcode != 0) {
            write(udp.reply(DnsResponses.formerr(query)))
            return
        }
        val p = policy ?: return
        when (val verdict = p.verdict(query)) {
            DnsVerdict.BLOCK -> {
                write(udp.reply(DnsResponses.nxdomain(query)))
                FilterStatus.recordBlocked(this)
            }
            DnsVerdict.FORWARD, DnsVerdict.FORWARD_SHORT_TTL -> {
                try {
                    workers.execute {
                        val answer = forwarder.resolve(query) ?: return@execute
                        val out = if (verdict == DnsVerdict.FORWARD_SHORT_TTL) DnsResponses.clampTtls(answer) else answer
                        write(udp.reply(out))
                    }
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    // Overloaded: drop; the browser will retry.
                }
            }
        }
    }

    private fun write(packet: ByteArray) {
        synchronized(this) {
            try {
                output?.write(packet)
            } catch (e: IOException) {
                Log.w(TAG, "tunnel write failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun closeTunnel() {
        reader?.interrupt()
        reader = null
        runCatching { tunnel?.close() }
        tunnel = null
        output = null
    }

    @Synchronized
    private fun shutdown() {
        closeTunnel()
        heartbeat?.shutdownNow()
        heartbeat = null
        FilterStatus.running = false
    }

    override fun onRevoke() {
        // Another VPN took over, or the user disconnected it in Settings. guard will notice.
        shutdown()
        stopSelf()
    }

    override fun onDestroy() {
        shutdown()
        upstream.stop()
        workers.shutdownNow()
        super.onDestroy()
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Filter running", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Required by Android while the browser filter runs. Silent."
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_filter)
            .setContentTitle("Browser filter is on")
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "FilterVpn"
        private const val CHANNEL_ID = "filter"
        private const val NOTIFICATION_ID = 1
        private const val MTU = 1500

        // Reserved ranges that are never routed on the internet: TEST-NET-1 (RFC 5737) for IPv4,
        // a unique-local (RFC 4193) prefix for IPv6.
        const val TUN_V4 = "192.0.2.1"
        const val DNS_V4 = "192.0.2.53"
        const val TUN_V6 = "fd00:de70:f17e::1"
        const val DNS_V6 = "fd00:de70:f17e::53"

        const val ACTION_START = "dev.detour.filter.START"
        const val ACTION_STOP = "dev.detour.filter.STOP"
        const val ACTION_RELOAD = "dev.detour.filter.RELOAD"

        fun start(context: Context, action: String = ACTION_START) {
            context.startForegroundService(Intent(context, FilterVpnService::class.java).setAction(action))
        }
    }
}
