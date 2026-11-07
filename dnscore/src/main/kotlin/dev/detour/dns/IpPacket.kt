package dev.detour.dns

/**
 * A UDP datagram read from the VPN tunnel (IPv4 or IPv6), and building the reply that goes
 * back into the tunnel. Only plain UDP is handled; anything else is ignored by the filter.
 */
class UdpPacket(
    val ipVersion: Int,
    val srcAddress: ByteArray,
    val dstAddress: ByteArray,
    val srcPort: Int,
    val dstPort: Int,
    val payload: ByteArray,
) {
    /** A reply from [dstAddress]:[dstPort] back to [srcAddress]:[srcPort] carrying [replyPayload]. */
    fun reply(replyPayload: ByteArray): ByteArray =
        IpPackets.buildUdp(ipVersion, src = dstAddress, dst = srcAddress, srcPort = dstPort, dstPort = srcPort, payload = replyPayload)

    companion object {
        const val PROTO_UDP = 17

        /** Parses an IP packet; returns null for non-UDP, fragments or malformed packets. */
        fun parse(packet: ByteArray, length: Int): UdpPacket? {
            if (length < 1 || length > packet.size) return null
            return when ((packet[0].toInt() and 0xF0) shr 4) {
                4 -> parseV4(packet, length)
                6 -> parseV6(packet, length)
                else -> null
            }
        }

        private fun parseV4(p: ByteArray, length: Int): UdpPacket? {
            if (length < 20) return null
            val ihl = (p[0].toInt() and 0x0F) * 4
            val total = u16(p, 2)
            if (ihl < 20 || total > length || total < ihl + 8) return null
            if (p[9].toInt() and 0xFF != PROTO_UDP) return null
            val fragment = u16(p, 6)
            if (fragment and 0x3FFF != 0) return null // more-fragments flag or nonzero offset
            return udp(4, p.copyOfRange(12, 16), p.copyOfRange(16, 20), p, ihl, total)
        }

        private fun parseV6(p: ByteArray, length: Int): UdpPacket? {
            if (length < 48) return null
            val payloadLen = u16(p, 4)
            if (40 + payloadLen > length) return null
            if (p[6].toInt() and 0xFF != PROTO_UDP) return null // extension headers not supported
            return udp(6, p.copyOfRange(8, 24), p.copyOfRange(24, 40), p, 40, 40 + payloadLen)
        }

        private fun udp(v: Int, src: ByteArray, dst: ByteArray, p: ByteArray, off: Int, end: Int): UdpPacket? {
            val udpLen = u16(p, off + 4)
            if (udpLen < 8 || off + udpLen > end) return null
            return UdpPacket(v, src, dst, u16(p, off), u16(p, off + 2), p.copyOfRange(off + 8, off + udpLen))
        }

        private fun u16(d: ByteArray, i: Int) = ((d[i].toInt() and 0xFF) shl 8) or (d[i + 1].toInt() and 0xFF)
    }
}

object IpPackets {
    fun buildUdp(ipVersion: Int, src: ByteArray, dst: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val udpLen = 8 + payload.size
        val headerLen = if (ipVersion == 4) 20 else 40
        val out = ByteArray(headerLen + udpLen)
        if (ipVersion == 4) {
            out[0] = 0x45
            put16(out, 2, out.size)
            out[6] = 0x40 // don't fragment
            out[8] = 64 // TTL
            out[9] = UdpPacket.PROTO_UDP.toByte()
            System.arraycopy(src, 0, out, 12, 4)
            System.arraycopy(dst, 0, out, 16, 4)
            put16(out, 10, checksum(out, 0, 20, 0))
        } else {
            out[0] = 0x60
            put16(out, 4, udpLen)
            out[6] = UdpPacket.PROTO_UDP.toByte()
            out[7] = 64 // hop limit
            System.arraycopy(src, 0, out, 8, 16)
            System.arraycopy(dst, 0, out, 24, 16)
        }
        val u = headerLen
        put16(out, u, srcPort)
        put16(out, u + 2, dstPort)
        put16(out, u + 4, udpLen)
        System.arraycopy(payload, 0, out, u + 8, payload.size)
        var sum = pseudoHeaderSum(src, dst, udpLen)
        var c = checksum(out, u, udpLen, sum)
        if (c == 0) c = 0xFFFF // 0 means "no checksum" in UDP; IPv6 requires one
        put16(out, u + 6, c)
        return out
    }

    /** Verifies the IPv4 header checksum (if v4) and the UDP checksum. Used by tests. */
    fun checksumsValid(packet: ByteArray): Boolean {
        val v = (packet[0].toInt() and 0xF0) shr 4
        val headerLen = if (v == 4) (packet[0].toInt() and 0x0F) * 4 else 40
        if (v == 4 && checksum(packet, 0, headerLen, 0) != 0) return false
        val src = if (v == 4) packet.copyOfRange(12, 16) else packet.copyOfRange(8, 24)
        val dst = if (v == 4) packet.copyOfRange(16, 20) else packet.copyOfRange(24, 40)
        val udpLen = ((packet[headerLen + 4].toInt() and 0xFF) shl 8) or (packet[headerLen + 5].toInt() and 0xFF)
        return checksum(packet, headerLen, udpLen, pseudoHeaderSum(src, dst, udpLen)) == 0
    }

    private fun pseudoHeaderSum(src: ByteArray, dst: ByteArray, udpLen: Int): Long {
        var sum = 0L
        for (addr in listOf(src, dst)) {
            var i = 0
            while (i < addr.size) {
                sum += ((addr[i].toInt() and 0xFF) shl 8) or (addr[i + 1].toInt() and 0xFF)
                i += 2
            }
        }
        sum += UdpPacket.PROTO_UDP
        sum += udpLen
        return sum
    }

    /** Internet checksum (RFC 1071) over data[off, off+len) plus an initial sum. */
    private fun checksum(data: ByteArray, off: Int, len: Int, initial: Long): Int {
        var sum = initial
        var i = off
        val end = off + len
        while (i + 1 < end) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (data[i].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun put16(d: ByteArray, i: Int, v: Int) {
        d[i] = (v shr 8).toByte()
        d[i + 1] = v.toByte()
    }
}
