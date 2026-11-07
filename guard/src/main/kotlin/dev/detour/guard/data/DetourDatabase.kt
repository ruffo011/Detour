package dev.detour.guard.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** The user's own decision for one app. Apps without a row use the suggested/default rules. */
@Entity(tableName = "app_classification")
data class AppClassificationEntity(
    @PrimaryKey val packageName: String,
    /** [dev.detour.core.AppCategory] name. */
    val category: String,
    val updatedAtMs: Long,
)

/**
 * A neutral record that protection was partly off (service stopped, permission removed, ...).
 * Shown in the weekly review; never used for scores or streaks.
 */
@Entity(tableName = "lapse_event")
data class LapseEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val atMs: Long,
    val kind: String,
    val detail: String,
    /** Optional "what was going on?" note added later in the weekly review. */
    val note: String? = null,
)

/** A stretch of time a Distracting app was in the foreground. */
@Entity(tableName = "usage_interval", indices = [Index("startMs")])
data class UsageIntervalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val startMs: Long,
    val endMs: Long,
)

/** One of your alternatives. Sets are stored as comma-separated enum names. */
@Entity(tableName = "alternative")
data class AlternativeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val note: String,
    val feelings: String,
    val durations: String,
    val places: String,
    val isRideItOut: Boolean,
    val position: Int,
)

/** You picked an alternative in an intervention; [outcome] is filled in by the unlock check-in. */
@Entity(tableName = "alternative_choice", indices = [Index("chosenAtMs")])
data class AlternativeChoiceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val alternativeId: Long,
    val feeling: String,
    val packageName: String?,
    val chosenAtMs: Long,
    val outcome: String? = null,
    val outcomeAtMs: Long? = null,
)

/** What you typed in "Why are you opening this?". For the weekly review only. */
@Entity(tableName = "intention", indices = [Index("atMs")])
data class IntentionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val text: String,
    val feeling: String?,
    val atMs: Long,
)

/** One browser blocklist group (e.g. YouTube). Domains are comma-separated. */
@Entity(tableName = "blocklist_group")
data class BlocklistGroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val domains: String,
    val blocked: Boolean,
    val position: Int,
)

/** A loosening change waiting its 24 hours. [op] is a [dev.detour.core.settings.SettingsOp] line. */
@Entity(tableName = "pending_change")
data class PendingChangeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val op: String,
    val description: String,
    val createdAtMs: Long,
    val applyAtMs: Long,
)

@Dao
interface PendingDao {
    @Query("SELECT * FROM pending_change ORDER BY applyAtMs")
    fun observeAll(): Flow<List<PendingChangeEntity>>

    @Query("SELECT * FROM pending_change ORDER BY applyAtMs")
    suspend fun all(): List<PendingChangeEntity>

    @Insert
    suspend fun insert(change: PendingChangeEntity): Long

    @Query("DELETE FROM pending_change WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface BlocklistDao {
    @Query("SELECT * FROM blocklist_group ORDER BY position, id")
    fun observeAll(): Flow<List<BlocklistGroupEntity>>

    @Query("SELECT * FROM blocklist_group ORDER BY position, id")
    suspend fun all(): List<BlocklistGroupEntity>

    @Query("SELECT COUNT(*) FROM blocklist_group")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(group: BlocklistGroupEntity)

    @Query("DELETE FROM blocklist_group WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ClassificationDao {
    @Query("SELECT * FROM app_classification")
    fun observeAll(): Flow<List<AppClassificationEntity>>

    @Upsert
    suspend fun upsert(entity: AppClassificationEntity)
}

@Dao
interface LapseDao {
    @Insert
    suspend fun insert(event: LapseEventEntity)

    @Query("SELECT * FROM lapse_event ORDER BY atMs DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<LapseEventEntity>>
}

@Dao
interface UsageDao {
    @Insert
    suspend fun insert(interval: UsageIntervalEntity): Long

    @Update
    suspend fun update(interval: UsageIntervalEntity)

    @Query("SELECT * FROM usage_interval WHERE endMs >= :fromMs ORDER BY startMs")
    suspend fun since(fromMs: Long): List<UsageIntervalEntity>
}

@Dao
interface AlternativeDao {
    @Query("SELECT * FROM alternative ORDER BY position, id")
    fun observeAll(): Flow<List<AlternativeEntity>>

    @Query("SELECT * FROM alternative ORDER BY position, id")
    suspend fun all(): List<AlternativeEntity>

    @Query("SELECT * FROM alternative WHERE id = :id")
    suspend fun byId(id: Long): AlternativeEntity?

    @Query("SELECT COUNT(*) FROM alternative")
    suspend fun count(): Int

    @Insert
    suspend fun insert(entity: AlternativeEntity): Long

    @Update
    suspend fun update(entity: AlternativeEntity)

    @Query("DELETE FROM alternative WHERE id = :id AND isRideItOut = 0")
    suspend fun delete(id: Long)

    @Query("DELETE FROM alternative WHERE isRideItOut = 0")
    suspend fun deleteAllEditable()

    @Query("SELECT * FROM alternative_choice ORDER BY chosenAtMs")
    suspend fun allChoices(): List<AlternativeChoiceEntity>

    @Insert
    suspend fun insertChoice(choice: AlternativeChoiceEntity): Long

    @Query("SELECT * FROM alternative_choice ORDER BY chosenAtMs DESC LIMIT :limit")
    suspend fun recentChoices(limit: Int): List<AlternativeChoiceEntity>

    @Query("SELECT * FROM alternative_choice WHERE outcome IS NOT NULL ORDER BY chosenAtMs DESC LIMIT :limit")
    fun observeOutcomes(limit: Int): Flow<List<AlternativeChoiceEntity>>

    @Query("SELECT * FROM alternative_choice WHERE id = :id")
    suspend fun choiceById(id: Long): AlternativeChoiceEntity?

    @Query("UPDATE alternative_choice SET outcome = :outcome, outcomeAtMs = :atMs WHERE id = :id")
    suspend fun setOutcome(id: Long, outcome: String, atMs: Long)
}

@Dao
interface IntentionDao {
    @Insert
    suspend fun insert(intention: IntentionEntity)
}

@Database(
    entities = [
        AppClassificationEntity::class,
        LapseEventEntity::class,
        UsageIntervalEntity::class,
        AlternativeEntity::class,
        AlternativeChoiceEntity::class,
        IntentionEntity::class,
        BlocklistGroupEntity::class,
        PendingChangeEntity::class,
    ],
    version = 4,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4)],
)
abstract class DetourDatabase : RoomDatabase() {
    abstract fun classifications(): ClassificationDao
    abstract fun blocklist(): BlocklistDao
    abstract fun pending(): PendingDao
    abstract fun lapses(): LapseDao
    abstract fun usage(): UsageDao
    abstract fun alternatives(): AlternativeDao
    abstract fun intentions(): IntentionDao

    companion object {
        fun create(context: Context): DetourDatabase =
            Room.databaseBuilder(context, DetourDatabase::class.java, "detour.db").build()
    }
}
