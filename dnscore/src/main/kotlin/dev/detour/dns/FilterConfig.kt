package dev.detour.dns

/** One switchable group of domains, e.g. "YouTube". */
data class BlocklistGroup(
    val id: String,
    val name: String,
    val domains: List<String>,
    /** Blocked right now. Unblocked groups stay listed so their answers get short TTLs. */
    val blocked: Boolean,
)

/**
 * Everything filter is told by guard. Deliberately nothing personal: just the blocklist, which
 * browser goes through the filter, and a fallback resolver.
 *
 * Text format (one item per line, tab-separated), so it is easy to inspect and test:
 * ```
 * detour-filter-config 1
 * browser    com.android.chrome
 * fallback   9.9.9.9
 * group      youtube   YouTube   1   youtube.com,youtu.be
 * ```
 */
data class FilterConfig(
    val allowedBrowser: String,
    val fallbackDns: List<String>,
    val groups: List<BlocklistGroup>,
) {
    fun encode(): String = buildString {
        appendLine(HEADER)
        appendLine("browser\t${clean(allowedBrowser)}")
        appendLine("fallback\t${fallbackDns.joinToString(",") { clean(it) }}")
        for (g in groups) {
            appendLine(
                listOf("group", clean(g.id), clean(g.name), if (g.blocked) "1" else "0", g.domains.joinToString(",") { clean(it) })
                    .joinToString("\t"),
            )
        }
    }

    companion object {
        private const val HEADER = "detour-filter-config 1"

        private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').replace(",", "").trim()

        /** Parses [text]; returns null if it isn't a config at all. Bad lines are skipped. */
        fun decode(text: String): FilterConfig? {
            val lines = text.lines()
            if (lines.firstOrNull()?.trim() != HEADER) return null
            var browser = DefaultBlocklist.DEFAULT_BROWSER
            var fallback = DefaultBlocklist.DEFAULT_FALLBACK_DNS
            val groups = ArrayList<BlocklistGroup>()
            for (line in lines.drop(1)) {
                val parts = line.split('\t')
                when (parts.firstOrNull()) {
                    "browser" -> parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { browser = it }
                    "fallback" -> parts.getOrNull(1)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                        ?.takeIf { it.isNotEmpty() }?.let { fallback = it }
                    "group" -> if (parts.size >= 5) {
                        groups += BlocklistGroup(
                            id = parts[1],
                            name = parts[2],
                            blocked = parts[3] == "1",
                            domains = parts[4].split(',').mapNotNull { DomainMatcher.normalize(it) },
                        )
                    }
                }
            }
            return FilterConfig(browser, fallback, groups)
        }
    }
}

/** What the filter does with one DNS question. */
enum class DnsVerdict {
    /** Answer NXDOMAIN locally. */
    BLOCK,

    /** Forward, but clamp TTLs so a block can take effect quickly when it switches on. */
    FORWARD_SHORT_TTL,

    /** Forward unchanged. */
    FORWARD,
}

class DnsPolicy(config: FilterConfig) {
    private val blocked = DomainMatcher(config.groups.filter { it.blocked }.flatMap { it.domains })
    private val listedButAllowed = DomainMatcher(config.groups.filter { !it.blocked }.flatMap { it.domains })

    fun verdict(name: String): DnsVerdict = when {
        blocked.matches(name) -> DnsVerdict.BLOCK
        listedButAllowed.matches(name) -> DnsVerdict.FORWARD_SHORT_TTL
        else -> DnsVerdict.FORWARD
    }

    /** A query with several questions is blocked if any of them is. */
    fun verdict(query: DnsQuery): DnsVerdict {
        val verdicts = query.questions.map { verdict(it.name) }
        return when {
            DnsVerdict.BLOCK in verdicts -> DnsVerdict.BLOCK
            DnsVerdict.FORWARD_SHORT_TTL in verdicts -> DnsVerdict.FORWARD_SHORT_TTL
            else -> DnsVerdict.FORWARD
        }
    }
}

object DefaultBlocklist {
    const val DEFAULT_BROWSER = "com.android.chrome"

    /** Used only if the phone's own network DNS servers can't be read. Quad9: no logging of IPs. */
    val DEFAULT_FALLBACK_DNS = listOf("9.9.9.9", "149.112.112.112")

    /**
     * Default groups, all blocked. Chosen so the sites don't load in Chrome, without touching
     * shared infrastructure: no generic Google domains (googleapis.com, gstatic.com), and no
     * fbcdn.net / facebook.net, which Messenger on the web and "Log in with Facebook" on other
     * sites also use. messenger.com is deliberately not listed.
     */
    val GROUPS: List<BlocklistGroup> = listOf(
        BlocklistGroup(
            "youtube", "YouTube",
            listOf("youtube.com", "youtu.be", "youtube-nocookie.com", "ytimg.com", "googlevideo.com", "youtubekids.com"),
            blocked = true,
        ),
        BlocklistGroup(
            "facebook", "Facebook",
            listOf("facebook.com", "fb.com", "fb.watch", "fb.me"),
            blocked = true,
        ),
        BlocklistGroup(
            "instagram", "Instagram",
            listOf("instagram.com", "instagr.am", "cdninstagram.com", "ig.me"),
            blocked = true,
        ),
        BlocklistGroup(
            "reddit", "Reddit",
            listOf("reddit.com", "redd.it", "redditmedia.com", "redditstatic.com"),
            blocked = true,
        ),
        BlocklistGroup(
            "snapchat", "Snapchat",
            listOf("snapchat.com"),
            blocked = true,
        ),
    )

    val CONFIG = FilterConfig(DEFAULT_BROWSER, DEFAULT_FALLBACK_DNS, GROUPS)
}
