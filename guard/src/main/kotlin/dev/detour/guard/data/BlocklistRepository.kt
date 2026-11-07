package dev.detour.guard.data

import android.content.Context
import android.content.Intent
import dev.detour.dns.BlocklistGroup
import dev.detour.dns.DefaultBlocklist
import dev.detour.dns.DomainMatcher
import dev.detour.dns.FilterConfig
import dev.detour.ipc.DetourIpc
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The browser blocklist, owned by guard. filter reads it through FilterConfigProvider and is
 * told about changes with a signature-protected broadcast.
 *
 * The write methods here apply directly; the UI never calls them, it goes through ChangeQueue
 * so unblocking/removing waits 24 hours.
 */
class BlocklistRepository(
    private val context: Context,
    private val dao: BlocklistDao,
    private val allowedBrowser: () -> String,
) {
    val groups: Flow<List<BlocklistGroup>> = dao.observeAll().map { rows -> rows.map { it.toModel() } }

    suspend fun all(): List<BlocklistGroup> = dao.all().map { it.toModel() }

    suspend fun seedIfEmpty() {
        if (dao.count() > 0) return
        DefaultBlocklist.GROUPS.forEachIndexed { i, g -> dao.upsert(g.toEntity(i)) }
        notifyFilter()
    }

    suspend fun setBlocked(id: String, blocked: Boolean) = edit(id) { it.copy(blocked = blocked) }

    suspend fun addDomain(id: String, domain: String) = edit(id) { it.copy(domains = (it.domains + domain).distinct()) }

    suspend fun removeDomain(id: String, domain: String) = edit(id) { it.copy(domains = it.domains - domain) }

    suspend fun addGroup(id: String, name: String) {
        if (dao.all().any { it.id == id }) return
        val position = (dao.all().maxOfOrNull { it.position } ?: -1) + 1
        dao.upsert(BlocklistGroup(id, name.trim().ifEmpty { "My sites" }, emptyList(), blocked = true).toEntity(position))
        notifyFilter()
    }

    suspend fun deleteGroup(id: String) {
        dao.delete(id)
        notifyFilter()
    }

    /** Everything filter needs, and nothing more. */
    suspend fun buildConfig(): FilterConfig {
        // filter may ask before guard has ever started: never hand out an empty blocklist.
        if (dao.count() == 0) DefaultBlocklist.GROUPS.forEachIndexed { i, g -> dao.upsert(g.toEntity(i)) }
        return FilterConfig(
            allowedBrowser = allowedBrowser(),
            fallbackDns = DefaultBlocklist.DEFAULT_FALLBACK_DNS,
            groups = dao.all().map { it.toModel() },
        )
    }

    /** Tells filter to re-read the config. Only apps with the signature permission receive it. */
    fun notifyFilter() {
        context.sendBroadcast(
            Intent(DetourIpc.ACTION_CONFIG_CHANGED).setPackage(DetourIpc.FILTER_PACKAGE),
            DetourIpc.PERMISSION,
        )
    }

    private suspend fun edit(id: String, transform: (BlocklistGroup) -> BlocklistGroup) {
        val row = dao.all().firstOrNull { it.id == id } ?: return
        dao.upsert(transform(row.toModel()).toEntity(row.position))
        notifyFilter()
    }

    private fun BlocklistGroupEntity.toModel() =
        BlocklistGroup(id, name, domains.split(',').filter { it.isNotBlank() }, blocked)

    private fun BlocklistGroup.toEntity(position: Int) =
        BlocklistGroupEntity(id, name, domains.joinToString(","), blocked, position)

    companion object {
        /** "https://www.TikTok.com/foo" -> "www.tiktok.com"; null if it isn't a domain. */
        fun normalizeDomain(input: String): String? =
            DomainMatcher.normalize(input.trim().removePrefix("https://").removePrefix("http://").substringBefore('/'))
                ?.takeIf { '.' in it && ' ' !in it }
    }
}
