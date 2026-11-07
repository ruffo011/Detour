package dev.detour.dns

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Small builder for hand-made DNS messages. */
private class Msg {
    private val out = ByteArrayOutputStream()
    val size get() = out.size()
    fun u8(v: Int) = apply { out.write(v and 0xFF) }
    fun u16(v: Int) = apply { u8(v shr 8); u8(v) }
    fun u32(v: Long) = apply { u16((v shr 16).toInt()); u16(v.toInt()) }
    fun name(n: String) = apply {
        if (n.isNotEmpty()) n.split('.').forEach { l -> u8(l.length); l.forEach { u8(it.code) } }
        u8(0)
    }
    fun pointer(offset: Int) = apply { u16(0xC000 or offset) }
    fun header(id: Int, flags: Int, qd: Int, an: Int = 0, ns: Int = 0, ar: Int = 0) =
        apply { u16(id); u16(flags); u16(qd); u16(an); u16(ns); u16(ar) }
    fun opt(udpSize: Int) = apply { u8(0); u16(41); u16(udpSize); u32(0); u16(0) }
    fun bytes(): ByteArray = out.toByteArray()
}

private fun query(name: String, type: Int = DnsQuery.TYPE_A, id: Int = 0x1234, edns: Int? = null): ByteArray {
    val m = Msg().header(id, 0x0100, qd = 1, ar = if (edns != null) 1 else 0).name(name).u16(type).u16(1)
    if (edns != null) m.opt(edns)
    return m.bytes()
}

class DnsMessageTest {
    // ---- parsing ---------------------------------------------------------------------------

    @Test
    fun parsesSimpleAQuery() {
        val q = DnsQuery.parse(query("www.YouTube.com"))
        assertEquals(0x1234, q.id)
        assertFalse(q.isResponse)
        assertTrue(q.recursionDesired)
        assertEquals(listOf(DnsQuestion("www.YouTube.com", DnsQuery.TYPE_A, 1)), q.questions)
    }

    @Test
    fun parsesAaaaQuery() {
        val q = DnsQuery.parse(query("youtube.com", DnsQuery.TYPE_AAAA))
        assertEquals(DnsQuery.TYPE_AAAA, q.questions.single().type)
    }

    @Test
    fun parsesMultipleQuestionsWithCompressionPointer() {
        // Q1: m.youtube.com   Q2: www.<pointer to "youtube.com" inside Q1>
        val m = Msg().header(1, 0x0100, qd = 2)
        m.u8(1).u8('m'.code)            // offset 12: "m"
        val youtubeOffset = m.size       // offset 14: "youtube.com"
        m.name("youtube.com").u16(1).u16(1)
        m.u8(3).u8('w'.code).u8('w'.code).u8('w'.code).pointer(youtubeOffset).u16(28).u16(1)
        val q = DnsQuery.parse(m.bytes())
        assertEquals(listOf("m.youtube.com", "www.youtube.com"), q.questions.map { it.name })
        assertEquals(28, q.questions[1].type)
        assertEquals(m.size, q.questionEnd)
    }

    @Test
    fun parsesEdnsQueryAndReadsUdpSize() {
        val q = DnsQuery.parse(query("example.com", edns = 4096))
        assertEquals("example.com", q.questions.single().name)
        assertEquals(4096, DnsResponses.maxUdpSize(q))
        assertEquals(512, DnsResponses.maxUdpSize(DnsQuery.parse(query("example.com"))))
        assertEquals(512, DnsResponses.maxUdpSize(DnsQuery.parse(query("example.com", edns = 100))))
    }

    @Test
    fun parsesRootName() {
        assertEquals("", DnsQuery.parse(query("")).questions.single().name)
    }

    @Test
    fun respectsLengthArgument() {
        val bytes = query("example.com") + ByteArray(50) { 0x7F }
        assertEquals("example.com", DnsQuery.parse(bytes, bytes.size - 50).questions.single().name)
    }

    // ---- malformed input -------------------------------------------------------------------

    private fun assertMalformed(bytes: ByteArray, why: String) {
        try {
            DnsQuery.parse(bytes)
            fail("expected malformed: $why")
        } catch (_: MalformedDnsException) {
        }
    }

    @Test
    fun rejectsMalformedPackets() {
        assertMalformed(ByteArray(0), "empty")
        assertMalformed(ByteArray(11), "shorter than header")
        assertMalformed(Msg().header(1, 0x0100, qd = 0).bytes(), "no questions")
        assertMalformed(Msg().header(1, 0x0100, qd = 1).u8(10).u8('a'.code).bytes(), "label runs past end")
        assertMalformed(Msg().header(1, 0x0100, qd = 1).name("example.com").u16(1).bytes(), "question missing class")
        assertMalformed(Msg().header(1, 0x0100, qd = 2).name("example.com").u16(1).u16(1).bytes(), "second question missing")
        assertMalformed(Msg().header(1, 0x0100, qd = 1).pointer(12).u16(1).u16(1).bytes(), "pointer to itself")
        assertMalformed(Msg().header(1, 0x0100, qd = 1).pointer(40).u16(1).u16(1).bytes(), "forward pointer")
        assertMalformed(Msg().header(1, 0x0100, qd = 1).u8(0x40).u16(1).u16(1).bytes(), "reserved label type")
        assertMalformed(Msg().header(1, 0x0100, qd = 1).u8(0xC0).bytes(), "truncated pointer")
        val longName = (1..5).joinToString(".") { "a".repeat(63) }
        assertMalformed(query(longName), "name over 255 bytes")
        assertMalformed(Msg().header(1, 0x0100, qd = 17).bytes(), "absurd question count")
    }

    @Test
    fun randomGarbageNeverThrowsAnythingElse() {
        val rnd = java.util.Random(42)
        repeat(5_000) {
            val bytes = ByteArray(rnd.nextInt(80)).also(rnd::nextBytes)
            try {
                DnsQuery.parse(bytes)
            } catch (_: MalformedDnsException) {
            }
            DnsResponses.clampTtls(bytes)
            UdpPacket.parse(bytes, bytes.size)
        }
    }

    // ---- NXDOMAIN --------------------------------------------------------------------------

    @Test
    fun nxdomainEchoesIdAndQuestion() {
        val bytes = query("www.youtube.com", id = 0xBEEF)
        val q = DnsQuery.parse(bytes)
        val r = DnsResponses.nxdomain(q)
        assertEquals(0xBEEF, DnsQuery.u16(r, 0))
        val flags = DnsQuery.u16(r, 2)
        assertTrue("QR", flags and 0x8000 != 0)
        assertTrue("RD copied", flags and 0x0100 != 0)
        assertTrue("RA", flags and 0x0080 != 0)
        assertEquals(DnsQuery.RCODE_NXDOMAIN, flags and 0xF)
        assertEquals(1, DnsQuery.u16(r, 4))
        assertEquals(0, DnsQuery.u16(r, 6) + DnsQuery.u16(r, 8) + DnsQuery.u16(r, 10))
        assertArrayEquals(bytes.copyOfRange(12, q.questionEnd), r.copyOfRange(12, r.size))
        // And it parses back as a message with the same question.
        assertEquals("www.youtube.com", DnsQuery.parse(r).questions.single().name)
    }

    @Test
    fun nxdomainForEdnsQueryDropsTheOptRecord() {
        val q = DnsQuery.parse(query("youtube.com", DnsQuery.TYPE_AAAA, edns = 1232))
        val r = DnsResponses.nxdomain(q)
        assertEquals(q.questionEnd, r.size)
        assertEquals(0, DnsQuery.u16(r, 10))
    }

    @Test
    fun nxdomainKeepsAllQuestions() {
        val m = Msg().header(7, 0x0100, qd = 2).name("a.com").u16(1).u16(1).name("b.com").u16(28).u16(1)
        val r = DnsResponses.nxdomain(DnsQuery.parse(m.bytes()))
        assertEquals(listOf("a.com", "b.com"), DnsQuery.parse(r).questions.map { it.name })
    }

    // ---- TTL clamping ----------------------------------------------------------------------

    /** www.youtube.com -> CNAME youtube-ui.l.google.com (TTL 300) -> A (TTL 120) + A (TTL 10), plus OPT. */
    private fun sampleResponse(): ByteArray {
        val m = Msg().header(9, 0x8180, qd = 1, an = 3, ar = 1)
        m.name("www.youtube.com").u16(1).u16(1)
        // CNAME, owner = pointer to question name (offset 12)
        m.pointer(12).u16(5).u16(1).u32(300)
        val target = Msg().name("youtube-ui.l.google.com").bytes()
        m.u16(target.size)
        val targetOffset = m.size
        target.forEach { m.u8(it.toInt()) }
        m.pointer(targetOffset).u16(1).u16(1).u32(120).u16(4).u8(142).u8(250).u8(1).u8(1)
        m.pointer(targetOffset).u16(1).u16(1).u32(10).u16(4).u8(142).u8(250).u8(1).u8(2)
        m.u8(0).u16(41).u16(1232).u32(0x00008000).u16(0) // OPT with DO flag in "TTL"
        return m.bytes()
    }

    private fun ttls(r: ByteArray): List<Long> {
        val out = ArrayList<Long>()
        var pos = DnsQuery.readName(r, 12).second + 4
        repeat(DnsQuery.u16(r, 6) + DnsQuery.u16(r, 8) + DnsQuery.u16(r, 10)) {
            pos = DnsQuery.readName(r, pos).second
            out += DnsQuery.u32(r, pos + 4)
            pos += 10 + DnsQuery.u16(r, pos + 8)
        }
        return out
    }

    @Test
    fun clampsTtlsButNotOpt() {
        val original = sampleResponse()
        val clamped = DnsResponses.clampTtls(original, maxTtl = 30)
        assertEquals(listOf(300L, 120L, 10L, 0x8000L), ttls(original))
        assertEquals(listOf(30L, 30L, 10L, 0x8000L), ttls(clamped))
        assertEquals(original.size, clamped.size)
    }

    @Test
    fun clampLeavesMalformedResponsesUnchanged() {
        val truncated = sampleResponse().copyOf(40)
        assertArrayEquals(truncated, DnsResponses.clampTtls(truncated))
    }

    @Test
    fun detectsTruncatedFlag() {
        val r = sampleResponse()
        assertFalse(DnsResponses.isTruncated(r))
        r[2] = (r[2].toInt() or 0x02).toByte()
        assertTrue(DnsResponses.isTruncated(r))
    }

    // ---- IP / UDP ------------------------------------------------------------------------

    private val clientV4 = byteArrayOf(10, 0, 0, 2)
    private val dnsV4 = byteArrayOf(192.toByte(), 0, 2, 53)
    private val clientV6 = ByteArray(16).also { it[0] = 0xFD.toByte(); it[15] = 2 }
    private val dnsV6 = ByteArray(16).also { it[0] = 0xFD.toByte(); it[15] = 0x53 }

    @Test
    fun ipv4RoundTripWithValidChecksums() {
        val payload = query("youtube.com")
        val packet = IpPackets.buildUdp(4, clientV4, dnsV4, 40000, 53, payload)
        assertTrue(IpPackets.checksumsValid(packet))
        val udp = UdpPacket.parse(packet, packet.size)!!
        assertEquals(4, udp.ipVersion)
        assertEquals(53, udp.dstPort)
        assertArrayEquals(dnsV4, udp.dstAddress)
        assertArrayEquals(payload, udp.payload)

        val reply = udp.reply(DnsResponses.nxdomain(DnsQuery.parse(udp.payload)))
        assertTrue(IpPackets.checksumsValid(reply))
        val back = UdpPacket.parse(reply, reply.size)!!
        assertArrayEquals(dnsV4, back.srcAddress)
        assertArrayEquals(clientV4, back.dstAddress)
        assertEquals(53, back.srcPort)
        assertEquals(40000, back.dstPort)
    }

    @Test
    fun ipv6RoundTripWithValidChecksums() {
        val payload = query("youtube.com", DnsQuery.TYPE_AAAA)
        val packet = IpPackets.buildUdp(6, clientV6, dnsV6, 51000, 53, payload)
        assertTrue(IpPackets.checksumsValid(packet))
        val udp = UdpPacket.parse(packet, packet.size)!!
        assertEquals(6, udp.ipVersion)
        val reply = udp.reply(byteArrayOf(1, 2, 3)) // odd length exercises checksum padding
        assertTrue(IpPackets.checksumsValid(reply))
        assertArrayEquals(clientV6, UdpPacket.parse(reply, reply.size)!!.dstAddress)
    }

    @Test
    fun ignoresNonUdpFragmentsAndTruncatedPackets() {
        val packet = IpPackets.buildUdp(4, clientV4, dnsV4, 40000, 53, query("a.com"))
        val tcp = packet.copyOf().also { it[9] = 6 }
        assertNull(UdpPacket.parse(tcp, tcp.size))
        val fragment = packet.copyOf().also { it[6] = 0x20 } // more fragments
        assertNull(UdpPacket.parse(fragment, fragment.size))
        assertNull(UdpPacket.parse(packet, 25))
        assertNull(UdpPacket.parse(byteArrayOf(0x50), 1)) // version 5
        assertNotNull(UdpPacket.parse(packet, packet.size))
    }
}
