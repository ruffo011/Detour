package dev.detour.guard.data

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import dev.detour.core.AppCategory
import dev.detour.core.budget.BudgetSettings
import dev.detour.core.settings.GroupState
import dev.detour.core.settings.GuardTarget
import dev.detour.core.settings.PendingChange
import dev.detour.core.settings.ProtectedSettings
import dev.detour.core.settings.RestorePlanner
import dev.detour.core.settings.SettingsOp
import dev.detour.core.time.AllowedHours
import dev.detour.guard.DetourApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalTime

data class BackupStatus(
    val folderUri: String?,
    val lastBackupMs: Long,
    val error: String?,
    /** A backup was found in a newly chosen folder and you haven't decided yet: restore or start fresh. */
    val foundBackupMs: Long?,
)

data class RestoreSummary(val appliedNow: Int, val queued: Int, val pendingRestored: Int, val alternatives: Int)

/**
 * Backs up config + alternatives + outcome history to a JSON file in a folder you pick
 * (Storage Access Framework, so no storage permission). Written automatically a few seconds after
 * any change. guard itself has no internet; pick a folder on the phone unless you want the
 * folder's own app (e.g. Drive) to sync it.
 */
class BackupManager(private val app: DetourApp) {
    private val prefs = app.getSharedPreferences("backup", android.content.Context.MODE_PRIVATE)
    private val _status = MutableStateFlow(readStatus())
    val status: StateFlow<BackupStatus> = _status.asStateFlow()
    private var pendingJob: Job? = null

    private fun readStatus() = BackupStatus(
        folderUri = prefs.getString(K_FOLDER, null),
        lastBackupMs = prefs.getLong(K_LAST, 0),
        error = prefs.getString(K_ERROR, null),
        foundBackupMs = prefs.getLong(K_FOUND, -1).takeIf { it >= 0 },
    )

    private fun refresh() {
        _status.value = readStatus()
    }

    /** Remembers the folder (with lasting permission) and checks it for an existing backup. */
    suspend fun setFolder(tree: Uri) = withContext(Dispatchers.IO) {
        app.contentResolver.takePersistableUriPermission(
            tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        prefs.edit().putString(K_FOLDER, tree.toString()).remove(K_ERROR).apply()
        val existing = readBackupJson()
        if (existing != null) {
            // Don't overwrite it until you decide whether to restore it.
            prefs.edit().putLong(K_FOUND, existing.optLong("exportedAt", 0)).apply()
        } else {
            prefs.edit().remove(K_FOUND).apply()
            exportNow()
        }
        refresh()
    }

    /** "Start fresh": keep the folder, overwrite the old backup from now on. */
    suspend fun discardFoundBackup() {
        prefs.edit().remove(K_FOUND).apply()
        refresh()
        exportNow()
    }

    /** Debounced automatic backup after a change. */
    fun requestBackup() {
        pendingJob?.cancel()
        pendingJob = app.appScope.launch {
            delay(3_000)
            exportNow()
        }
    }

    suspend fun exportNow(): Boolean = withContext(Dispatchers.IO) {
        val folder = prefs.getString(K_FOLDER, null) ?: return@withContext false
        if (prefs.contains(K_FOUND)) return@withContext false
        try {
            val json = buildJson().toString(2)
            val uri = backupFileUri(Uri.parse(folder), create = true) ?: error("Couldn't create the backup file")
            app.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(json.toByteArray()) }
            prefs.edit().putLong(K_LAST, System.currentTimeMillis()).remove(K_ERROR).apply()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Backup failed", e)
            prefs.edit().putString(K_ERROR, e.message ?: e.javaClass.simpleName).apply()
            false
        } finally {
            refresh()
        }
    }

    // ---- writing ---------------------------------------------------------------------------

    private suspend fun buildJson(): JSONObject {
        val s = app.changes.snapshot()
        val choices = app.classifications.choices.value.orEmpty()
        return JSONObject().apply {
            put("format", "detour-backup")
            put("version", 1)
            put("exportedAt", System.currentTimeMillis())
            put("settings", settingsJson(s.copy(categories = choices)))
            put("pending", JSONArray().apply {
                app.changes.allPending().forEach { p ->
                    put(JSONObject().put("op", p.op.encode()).put("description", p.description)
                        .put("createdAt", p.createdAtMs).put("applyAt", p.applyAtMs))
                }
            })
            val alts = app.database.alternatives().all()
            put("alternatives", JSONArray().apply {
                alts.forEach { a ->
                    put(JSONObject().put("ref", a.id).put("title", a.title).put("note", a.note).put("feelings", a.feelings)
                        .put("durations", a.durations).put("places", a.places).put("rideItOut", a.isRideItOut).put("position", a.position))
                }
            })
            put("choices", JSONArray().apply {
                app.database.alternatives().allChoices().forEach { c ->
                    put(JSONObject().put("altRef", c.alternativeId).put("feeling", c.feeling).put("pkg", c.packageName ?: "")
                        .put("chosenAt", c.chosenAtMs).put("outcome", c.outcome ?: "").put("outcomeAt", c.outcomeAtMs ?: 0))
                }
            })
        }
    }

    private fun settingsJson(s: ProtectedSettings) = JSONObject().apply {
        put("categories", JSONObject().apply { s.categories.forEach { (k, v) -> put(k, v.name) } })
        val b = s.budget
        put("budget", JSONObject().apply {
            put("dailyMinutes", b.dailyBudgetMinutes ?: -1)
            put("opensCap", b.opensCap ?: -1)
            put("hoursStart", b.allowedHours?.start?.toSecondOfDay() ?: -1)
            put("hoursEnd", b.allowedHours?.end?.toSecondOfDay() ?: -1)
            put("reset", b.resetTime.toSecondOfDay())
            put("waits", b.waitsSeconds.joinToString(","))
            put("checkInMinutes", b.sessionCheckInMinutes)
            put("reopenSeconds", b.reopenAfterSeconds)
            put("observeUntil", b.observeUntilMs ?: -1)
            put("confirmed", b.budgetConfirmed)
        })
        put("blocklist", JSONArray().apply {
            s.blocklist.forEach { g ->
                put(JSONObject().put("id", g.id).put("name", g.name).put("blocked", g.blocked).put("domains", g.domains.joinToString(",")))
            }
        })
        put("browser", s.allowedBrowser)
        put("guardEnabled", s.settingsGuardEnabled)
        put("guardTargets", JSONArray().apply {
            s.guardTargets.forEach { put(JSONObject().put("key", it.key).put("pkg", it.packageName).put("cls", it.className)) }
        })
    }

    // ---- restoring -------------------------------------------------------------------------

    /**
     * Restores the found backup. Settings go through the same tighten/loosen gate as any change,
     * so a looser backup can't take effect immediately; pending changes keep their original times.
     */
    suspend fun restore(): RestoreSummary? = withContext(Dispatchers.IO) {
        val json = readBackupJson() ?: return@withContext null
        val backup = parseSettings(json.getJSONObject("settings"))
        val current = app.changes.snapshot()
        val ops = RestorePlanner.plan(current, backup).let { it.immediate + it.queued }
        var now = 0
        var queued = 0
        for (op in ops) {
            when (app.changes.submit(op, "Restored from backup: ${describe(op)}", allowSetupWindow = false)) {
                SubmitResult.Applied -> now++
                is SubmitResult.Queued -> queued++
            }
        }

        val existingPending = app.changes.allPending().map { it.op.encode() to it.applyAtMs }.toSet()
        var pendingCount = 0
        json.optJSONArray("pending")?.let { arr ->
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                val op = SettingsOp.decode(p.getString("op")) ?: continue
                val applyAt = p.getLong("applyAt")
                if ((op.encode() to applyAt) in existingPending) continue
                app.changes.restorePending(PendingChange(0, op, p.optString("description"), p.getLong("createdAt"), applyAt))
                pendingCount++
            }
        }

        val altCount = restoreAlternatives(json)
        prefs.edit().remove(K_FOUND).apply()
        refresh()
        exportNow()
        RestoreSummary(now, queued, pendingCount, altCount)
    }

    private suspend fun restoreAlternatives(json: JSONObject): Int {
        val alts = json.optJSONArray("alternatives") ?: return 0
        val dao = app.database.alternatives()
        dao.deleteAllEditable()
        val rideItOutId = dao.all().firstOrNull { it.isRideItOut }?.id
        val idMap = HashMap<Long, Long>()
        var count = 0
        for (i in 0 until alts.length()) {
            val a = alts.getJSONObject(i)
            val ref = a.getLong("ref")
            if (a.optBoolean("rideItOut")) {
                rideItOutId?.let { idMap[ref] = it }
                continue
            }
            idMap[ref] = dao.insert(
                AlternativeEntity(
                    title = a.getString("title"), note = a.optString("note"), feelings = a.optString("feelings"),
                    durations = a.optString("durations"), places = a.optString("places"), isRideItOut = false,
                    position = a.optInt("position", i),
                ),
            )
            count++
        }
        val known = dao.allChoices().map { it.chosenAtMs to it.alternativeId }.toSet()
        json.optJSONArray("choices")?.let { arr ->
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                val altId = idMap[c.getLong("altRef")] ?: continue
                val chosenAt = c.getLong("chosenAt")
                if ((chosenAt to altId) in known) continue
                dao.insertChoice(
                    AlternativeChoiceEntity(
                        alternativeId = altId, feeling = c.getString("feeling"), packageName = c.optString("pkg").ifEmpty { null },
                        chosenAtMs = chosenAt, outcome = c.optString("outcome").ifEmpty { null },
                        outcomeAtMs = c.optLong("outcomeAt", 0).takeIf { it > 0 },
                    ),
                )
            }
        }
        return count
    }

    private fun parseSettings(o: JSONObject): ProtectedSettings {
        val cats = o.getJSONObject("categories").let { c ->
            c.keys().asSequence().mapNotNull { k -> runCatching { k to AppCategory.valueOf(c.getString(k)) }.getOrNull() }.toMap()
        }
        val b = o.getJSONObject("budget")
        val hs = b.optInt("hoursStart", -1)
        val he = b.optInt("hoursEnd", -1)
        val budget = BudgetSettings(
            dailyBudgetMinutes = b.optInt("dailyMinutes", -1).takeIf { it >= 0 },
            opensCap = b.optInt("opensCap", -1).takeIf { it >= 0 },
            allowedHours = if (hs >= 0 && he >= 0) AllowedHours(LocalTime.ofSecondOfDay(hs.toLong()), LocalTime.ofSecondOfDay(he.toLong())) else null,
            resetTime = LocalTime.ofSecondOfDay(b.optInt("reset", 4 * 3600).toLong()),
            waitsSeconds = b.optString("waits").split(',').mapNotNull { it.trim().toIntOrNull() },
            sessionCheckInMinutes = b.optInt("checkInMinutes", 10),
            reopenAfterSeconds = b.optInt("reopenSeconds", 30),
            observeUntilMs = b.optLong("observeUntil", -1).takeIf { it >= 0 },
            budgetConfirmed = b.optBoolean("confirmed"),
        )
        val groups = o.optJSONArray("blocklist")?.let { arr ->
            (0 until arr.length()).map { i ->
                val g = arr.getJSONObject(i)
                GroupState(g.getString("id"), g.getString("name"), g.optString("domains").split(',').filter { it.isNotBlank() }.toSet(), g.getBoolean("blocked"))
            }
        }.orEmpty()
        val targets = o.optJSONArray("guardTargets")?.let { arr ->
            (0 until arr.length()).map { i -> arr.getJSONObject(i).let { GuardTarget(it.getString("key"), it.getString("pkg"), it.getString("cls")) } }.toSet()
        }.orEmpty()
        return ProtectedSettings(cats, budget, groups, o.optString("browser", "com.android.chrome"), o.optBoolean("guardEnabled", true), targets)
    }

    // ---- file access -----------------------------------------------------------------------

    private fun readBackupJson(): JSONObject? = try {
        val folder = prefs.getString(K_FOLDER, null)?.let(Uri::parse)
        val uri = folder?.let { backupFileUri(it, create = false) }
        uri?.let { u ->
            app.contentResolver.openInputStream(u)?.use { JSONObject(it.readBytes().decodeToString()) }
                ?.takeIf { it.optString("format") == "detour-backup" }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't read backup", e)
        null
    }

    private fun backupFileUri(tree: Uri, create: Boolean): Uri? {
        val resolver = app.contentResolver
        val treeId = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)
        resolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == FILE_NAME) return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
            }
        }
        if (!create) return null
        return DocumentsContract.createDocument(
            resolver, DocumentsContract.buildDocumentUriUsingTree(tree, treeId), "application/json", FILE_NAME,
        )
    }

    companion object {
        private const val TAG = "BackupManager"
        const val FILE_NAME = "detour-backup.json"
        private const val K_FOLDER = "folder"
        private const val K_LAST = "last_backup"
        private const val K_ERROR = "error"
        private const val K_FOUND = "found_backup"

        fun describe(op: SettingsOp): String = when (op) {
            is SettingsOp.SetCategory -> "${op.packageName} → ${op.category.label()}"
            is SettingsOp.SetDailyBudget -> "daily time ${op.minutes?.let { "$it min" } ?: "unlimited"}"
            is SettingsOp.SetOpensCap -> "opens limit ${op.cap ?: "off"}"
            is SettingsOp.SetAllowedHours -> "allowed hours ${op.hours?.let { "${it.start}–${it.end}" } ?: "off"}"
            is SettingsOp.SetResetTime -> "new day at ${op.time}"
            is SettingsOp.SetWaits -> "waits ${op.waits.joinToString(", ")} s"
            is SettingsOp.SetCheckInMinutes -> "check-in after ${op.minutes} min"
            is SettingsOp.SetReopenSeconds -> "new open after ${op.seconds} s"
            is SettingsOp.SetObserveUntil -> if (op.untilMs == null) "stop observing" else "observe week"
            is SettingsOp.SetBudgetConfirmed -> "budget confirmed"
            is SettingsOp.SetGroupBlocked -> "${op.groupId} ${if (op.blocked) "blocked" else "unblocked"}"
            is SettingsOp.AddDomain -> "block ${op.domain}"
            is SettingsOp.RemoveDomain -> "unblock ${op.domain}"
            is SettingsOp.AddGroup -> "group ${op.name}"
            is SettingsOp.DeleteGroup -> "delete group ${op.groupId}"
            is SettingsOp.SetAllowedBrowser -> "browser ${op.packageName}"
            is SettingsOp.SetSettingsGuard -> "Settings guard ${if (op.enabled) "on" else "off"}"
            is SettingsOp.AddGuardTarget -> "guard a settings screen"
            is SettingsOp.RemoveGuardTarget -> "stop guarding a settings screen"
        }
    }
}
