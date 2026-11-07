package dev.detour.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FilterConfigTest {
    @Test
    fun roundTrip() {
        val config = DefaultBlocklist.CONFIG.copy(
            groups = DefaultBlocklist.GROUPS.map { if (it.id == "reddit") it.copy(blocked = false) else it },
        )
        assertEquals(config, FilterConfig.decode(config.encode()))
    }

    @Test
    fun separatorsInNamesCannotBreakTheFormat() {
        val config = FilterConfig("com.android.chrome", listOf("1.1.1.1"), listOf(BlocklistGroup("x", "Bad\tname\nhere", listOf("a.com"), true)))
        val decoded = FilterConfig.decode(config.encode())!!
        assertEquals("Bad name here", decoded.groups.single().name)
        assertEquals(listOf("a.com"), decoded.groups.single().domains)
    }

    @Test
    fun rejectsNonConfigAndSkipsBadLines() {
        assertNull(FilterConfig.decode("hello"))
        val decoded = FilterConfig.decode("detour-filter-config 1\ngroup\tbroken\nbrowser\torg.mozilla.firefox\n")!!
        assertEquals("org.mozilla.firefox", decoded.allowedBrowser)
        assertEquals(emptyList<BlocklistGroup>(), decoded.groups)
        assertEquals(DefaultBlocklist.DEFAULT_FALLBACK_DNS, decoded.fallbackDns)
    }

    @Test
    fun policyVerdicts() {
        val policy = DnsPolicy(
            DefaultBlocklist.CONFIG.copy(groups = DefaultBlocklist.GROUPS.map { if (it.id == "reddit") it.copy(blocked = false) else it }),
        )
        assertEquals(DnsVerdict.BLOCK, policy.verdict("www.youtube.com"))
        assertEquals(DnsVerdict.BLOCK, policy.verdict("i.ytimg.com."))
        assertEquals(DnsVerdict.BLOCK, policy.verdict("M.FACEBOOK.COM"))
        assertEquals(DnsVerdict.BLOCK, policy.verdict("scontent.cdninstagram.com"))
        assertEquals(DnsVerdict.FORWARD_SHORT_TTL, policy.verdict("old.reddit.com"))
        assertEquals(DnsVerdict.FORWARD, policy.verdict("messenger.com"))
        assertEquals(DnsVerdict.FORWARD, policy.verdict("static.xx.fbcdn.net"))
        assertEquals(DnsVerdict.FORWARD, policy.verdict("www.google.com"))
        assertEquals(DnsVerdict.FORWARD, policy.verdict("music.youtube.com.example.org"))
    }

    @Test
    fun anyBlockedQuestionBlocksTheQuery() {
        val bytes = java.io.ByteArrayOutputStream().apply {
            fun u16(v: Int) { write(v shr 8); write(v and 0xFF) }
            fun name(n: String) { n.split('.').forEach { write(it.length); write(it.toByteArray()) }; write(0) }
            u16(1); u16(0x0100); u16(2); u16(0); u16(0); u16(0)
            name("example.com"); u16(1); u16(1)
            name("youtu.be"); u16(1); u16(1)
        }.toByteArray()
        assertEquals(DnsVerdict.BLOCK, DnsPolicy(DefaultBlocklist.CONFIG).verdict(DnsQuery.parse(bytes)))
    }
}
