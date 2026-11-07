package dev.detour.dns

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainMatcherTest {
    private val matcher = DomainMatcher(listOf("youtube.com", "fb.me", "Instagram.COM."))

    @Test
    fun exactMatch() {
        assertTrue(matcher.matches("youtube.com"))
        assertTrue(matcher.matches("fb.me"))
    }

    @Test
    fun subdomainsMatch() {
        assertTrue(matcher.matches("www.youtube.com"))
        assertTrue(matcher.matches("a.b.c.youtube.com"))
    }

    @Test
    fun caseAndTrailingDotIgnored() {
        assertTrue(matcher.matches("WWW.YouTube.Com."))
        assertTrue(matcher.matches("instagram.com"))
        assertTrue(matcher.matches("scontent.instagram.com."))
    }

    @Test
    fun lookalikesDoNotMatch() {
        assertFalse(matcher.matches("notyoutube.com"))
        assertFalse(matcher.matches("youtube.com.evil.net"))
        assertFalse(matcher.matches("com"))
        assertFalse(matcher.matches("messenger.com"))
    }

    @Test
    fun invalidNamesDoNotMatch() {
        assertFalse(matcher.matches(""))
        assertFalse(matcher.matches("."))
        assertFalse(matcher.matches("www..youtube.com"))
    }

    @Test
    fun invalidBlocklistEntriesAreIgnored() {
        assertNull(DomainMatcher.normalize(""))
        assertNull(DomainMatcher.normalize(".youtube.com"))
        assertFalse(DomainMatcher(listOf("", ".")).matches("example.com"))
    }
}
