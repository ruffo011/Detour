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
import java.util.UUID

/**
 * The browser blocklist, owned by guard. filter reads it through FilterConfigProvider and is
 * told about changes with a signature-protected broadcast.
 */
class BlocklistRepository(
    private val context: Context,
    private val dao: BlocklistDao,
    private val allowedBrowser: () -> String,
) {
    val groups: Flow<List<BlocklistGroup>> = dao.observeAll().map { rows -> rows.map { it.toModel() } }

    suspend fun seedIfEmpty() {
        if (dao.count() > 0) return
        DefaultBlocklist.GROUPS.forEachIndexed { i, g -> dao.upsert(g.toEntity(i)) }
        notifyFilter()
    }

    /** Milestone 5 routes unblocking/removing through the 24-hour queue; for now it's immediate. */
    suspend fun setBlocked(id: String, blocked: Boolean) = edit(id) { it.copy(blocked = blocked) }

    /** Adds a domain (normalized); returns false if it isn't a valid domain. */
    suspend fun addDomain(id: String, domain: String): Boolean {
        val normalized = DomainMatcher.normalize(domain.removePrefix("https://").removePrefix("http://").substringBefore('/'))
            ?.takeIf { '.' in it } ?: return false
        edit(id) { it.copy(domains = (it.domains + normalized).distinct()) }
        return true
    }

    suspend fun removeDomain(id: String, domain: String) = edit(id) { it.copy(domains = it.domains - domain) }

    suspend fun addGroup(name: String): String {
        val id = "custom-" + UUID.randomUUID().toString().take(8)
        val position = (dao.all().maxOfOrNull { it.position } ?: -1) + 1
        dao.upsert(BlocklistGroup(id, name.trim().ifEmpty { "My sites" }, emptyList(), blocked = true).toEntity(position))
        notifyFilter()
        return id
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
}
