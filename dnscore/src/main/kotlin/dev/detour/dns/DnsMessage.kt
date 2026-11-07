package dev.detour.dns

/** Thrown for anything that isn't a well-formed DNS message. Never crashes the filter. */
class MalformedDnsException(message: String) : Exception(message)

data class DnsQuestion(val name: String, val type: Int, val qclass: Int)

/**
 * The parts of a DNS query the filter needs: header fields and the question section.
 * Answer/authority/additional sections are not interpreted (EDNS OPT records live there).
 */
class DnsQuery private constructor(
    val raw: ByteArray,
    val id: Int,
    val flags: Int,
    val questions: List<DnsQuestion>,
    /** Byte offset just past the question section. */
    val questionEnd: Int,
) {
    val isResponse: Boolean get() = flags and 0x8000 != 0
    val opcode: Int get() = (flags shr 11) and 0xF
    val recursionDesired: Boolean get() = flags and 0x0100 != 0

    companion object {
        const val HEADER_SIZE = 12
        const val TYPE_A = 1
        const val TYPE_AAAA = 28
        const val TYPE_OPT = 41
        const val RCODE_FORMERR = 1
        const val RCODE_NXDOMAIN = 3

        fun parse(bytes: ByteArray, length: Int = bytes.size): DnsQuery {
            if (length < HEADER_SIZE || length > bytes.size) throw MalformedDnsException("too short")
            val data = bytes.copyOf(length)
            val id = u16(data, 0)
            val flags = u16(data, 2)
            val qdCount = u16(data, 4)
            if (qdCount == 0) throw MalformedDnsException("no questions")
            if (qdCount > 16) throw MalformedDnsException("too many questions")
            var pos = HEADER_SIZE
            val questions = ArrayList<DnsQuestion>(qdCount)
            repeat(qdCount) {
                val (name, next) = readName(data, pos)
                if (next + 4 > data.size) throw MalformedDnsException("question truncated")
                questions += DnsQuestion(name, u16(data, next), u16(data, next + 2))
                pos = next + 4
            }
            return DnsQuery(data, id, flags, questions, pos)
        }

        /**
         * Reads a (possibly compressed) domain name at [start]. Returns the dotted name
         * (no trailing dot, "" for the root) and the offset just after the name *at its
         * original position* (i.e. after the first pointer, if any).
         */
        fun readName(data: ByteArray, start: Int): Pair<String, Int> {
            val labels = ArrayList<String>()
            var pos = start
            var end = -1
            var jumps = 0
            var total = 0
            while (true) {
                if (pos >= data.size) throw MalformedDnsException("name runs past end")
                val len = data[pos].toInt() and 0xFF
                when (len and 0xC0) {
                    0x00 -> {
                        if (len == 0) {
                            if (end < 0) end = pos + 1
                            return labels.joinToString(".") to end
                        }
                        if (pos + 1 + len > data.size) throw MalformedDnsException("label runs past end")
                        total += len + 1
                        if (total > 255) throw MalformedDnsException("name too long")
                        labels += String(data, pos + 1, len, Charsets.ISO_8859_1)
                        pos += 1 + len
                    }
                    0xC0 -> {
                        if (pos + 1 >= data.size) throw MalformedDnsException("pointer truncated")
                        val target = ((len and 0x3F) shl 8) or (data[pos + 1].toInt() and 0xFF)
                        if (end < 0) end = pos + 2
                        if (++jumps > 32) throw MalformedDnsException("compression loop")
                        if (target >= pos) throw MalformedDnsException("forward pointer")
                        pos = target
                    }
                    else -> throw MalformedDnsException("unsupported label type")
                }
            }
        }

        internal fun u16(d: ByteArray, i: Int) = ((d[i].toInt() and 0xFF) shl 8) or (d[i + 1].toInt() and 0xFF)
        internal fun u32(d: ByteArray, i: Int): Long =
            ((d[i].toLong() and 0xFF) shl 24) or ((d[i + 1].toLong() and 0xFF) shl 16) or
                ((d[i + 2].toLong() and 0xFF) shl 8) or (d[i + 3].toLong() and 0xFF)
    }
}

/** Builds and edits DNS responses. */
object DnsResponses {
    /**
     * A local "this name doesn't exist" answer to [query]: same id and question(s), QR=1,
     * RD copied, RA=1, RCODE=NXDOMAIN, no records.
     */
    fun nxdomain(query: DnsQuery): ByteArray = errorResponse(query, DnsQuery.RCODE_NXDOMAIN)

    fun formerr(query: DnsQuery): ByteArray = errorResponse(query, DnsQuery.RCODE_FORMERR)

    private fun errorResponse(query: DnsQuery, rcode: Int): ByteArray {
        val out = ByteArray(query.questionEnd)
        System.arraycopy(query.raw, 0, out, 0, query.questionEnd)
        val flags = 0x8000 or (query.opcode shl 11) or (query.flags and 0x0100) or 0x0080 or rcode
        out[2] = (flags shr 8).toByte()
        out[3] = flags.toByte()
        // qdcount unchanged; an/ns/ar = 0
        for (i in 6 until 12) out[i] = 0
        return out
    }

    /**
     * Returns a copy of [response] with every record's TTL lowered to at most [maxTtl] seconds
     * (the EDNS OPT pseudo-record is left alone, its "TTL" holds flags). If the response can't be
     * walked safely, it is returned unchanged.
     */
    fun clampTtls(response: ByteArray, length: Int = response.size, maxTtl: Long = 30): ByteArray {
        val out = response.copyOf(length)
        try {
            if (length < DnsQuery.HEADER_SIZE) return out
            val qd = DnsQuery.u16(out, 4)
            val records = DnsQuery.u16(out, 6) + DnsQuery.u16(out, 8) + DnsQuery.u16(out, 10)
            var pos = DnsQuery.HEADER_SIZE
            repeat(qd) { pos = DnsQuery.readName(out, pos).second + 4 }
            repeat(records) {
                pos = DnsQuery.readName(out, pos).second
                if (pos + 10 > length) throw MalformedDnsException("record header truncated")
                val type = DnsQuery.u16(out, pos)
                val ttl = DnsQuery.u32(out, pos + 4)
                val rdLength = DnsQuery.u16(out, pos + 8)
                if (type != DnsQuery.TYPE_OPT && ttl > maxTtl) {
                    out[pos + 4] = (maxTtl shr 24).toByte()
                    out[pos + 5] = (maxTtl shr 16).toByte()
                    out[pos + 6] = (maxTtl shr 8).toByte()
                    out[pos + 7] = maxTtl.toByte()
                }
                pos += 10 + rdLength
                if (pos > length) throw MalformedDnsException("rdata truncated")
            }
            return out
        } catch (_: MalformedDnsException) {
            return response.copyOf(length)
        }
    }

    /** True if the TC (truncated) bit is set. */
    fun isTruncated(response: ByteArray): Boolean = response.size >= 4 && (response[2].toInt() and 0x02) != 0

    /** The client's advertised UDP payload size (EDNS), or 512 without EDNS. */
    fun maxUdpSize(query: DnsQuery): Int {
        try {
            val d = query.raw
            val an = DnsQuery.u16(d, 6)
            val ns = DnsQuery.u16(d, 8)
            val ar = DnsQuery.u16(d, 10)
            var pos = query.questionEnd
            repeat(an + ns + ar) { index ->
                pos = DnsQuery.readName(d, pos).second
                if (pos + 10 > d.size) return 512
                val type = DnsQuery.u16(d, pos)
                val cls = DnsQuery.u16(d, pos + 2)
                if (type == DnsQuery.TYPE_OPT && index >= an + ns) return maxOf(512, cls)
                pos += 10 + DnsQuery.u16(d, pos + 8)
            }
        } catch (_: MalformedDnsException) {
        }
        return 512
    }
}
