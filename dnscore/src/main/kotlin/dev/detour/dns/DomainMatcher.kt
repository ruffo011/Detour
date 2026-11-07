package dev.detour.dns

/**
 * Matches DNS names against a set of blocked domains.
 *
 * A name matches if it equals a blocked domain or is any subdomain of one
 * (`m.youtube.com` matches `youtube.com`; `notyoutube.com` does not).
 * Matching is case-insensitive and ignores a trailing dot on either side.
 */
class DomainMatcher(domains: Iterable<String>) {
    private val blocked: Set<String> = domains.mapNotNull(::normalize).toSet()

    fun matches(name: String): Boolean {
        var candidate = normalize(name) ?: return false
        while (true) {
            if (candidate in blocked) return true
            val dot = candidate.indexOf('.')
            if (dot < 0) return false
            candidate = candidate.substring(dot + 1)
        }
    }

    companion object {
        /** Lowercases and strips one trailing dot; returns null for empty/invalid input. */
        fun normalize(name: String): String? {
            val trimmed = name.trim().removeSuffix(".").lowercase()
            if (trimmed.isEmpty() || trimmed.startsWith('.') || ".." in trimmed) return null
            return trimmed
        }
    }
}
