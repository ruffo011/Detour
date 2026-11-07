package dev.detour.guard.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
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

@Database(
    entities = [AppClassificationEntity::class, LapseEventEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class DetourDatabase : RoomDatabase() {
    abstract fun classifications(): ClassificationDao
    abstract fun lapses(): LapseDao

    companion object {
        fun create(context: Context): DetourDatabase =
            Room.databaseBuilder(context, DetourDatabase::class.java, "detour.db").build()
    }
}
