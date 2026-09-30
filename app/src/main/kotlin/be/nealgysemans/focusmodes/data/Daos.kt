package be.nealgysemans.focusmodes.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Reads and writes for mode definitions.
 *
 * The [Flow] query feeds the UI; the blocking-friendly `suspend` list query feeds
 * `engine/ModeCatalog`, which needs a snapshot rather than a stream because the
 * reducer must decide synchronously.
 */
@Dao
interface ModeDao {

    /** Live list for the UI, ordered the way the user arranged it. */
    @Query("SELECT * FROM modes ORDER BY sort_order ASC, name ASC")
    fun observeModes(): Flow<List<ModeEntity>>

    /** One-shot snapshot for the engine. */
    @Query("SELECT * FROM modes ORDER BY sort_order ASC, name ASC")
    suspend fun getModes(): List<ModeEntity>

    @Query("SELECT * FROM modes WHERE id = :id")
    suspend fun getMode(id: String): ModeEntity?

    @Query("SELECT COUNT(*) FROM modes")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(mode: ModeEntity)

    @Upsert
    suspend fun upsertAll(modes: List<ModeEntity>)

    /** Cache the system rule id after `ZenAdapter.ensureRule` hands one back. */
    @Query("UPDATE modes SET zen_rule_id = :ruleId WHERE id = :modeId")
    suspend fun setZenRuleId(modeId: String, ruleId: String?)

    @Delete
    suspend fun delete(mode: ModeEntity)
}

/** Reads and writes for triggers. Only schedule triggers exist so far. */
@Dao
interface TriggerDao {

    @Query("SELECT * FROM triggers WHERE enabled = 1")
    fun observeEnabledTriggers(): Flow<List<TriggerEntity>>

    /** Snapshot used by `schedule/TriggerScheduleSource` on every reconcile. */
    @Query("SELECT * FROM triggers WHERE enabled = 1")
    suspend fun getEnabledTriggers(): List<TriggerEntity>

    @Query("SELECT * FROM triggers WHERE mode_id = :modeId")
    suspend fun getTriggersForMode(modeId: String): List<TriggerEntity>

    @Upsert
    suspend fun upsert(trigger: TriggerEntity)

    @Delete
    suspend fun delete(trigger: TriggerEntity)
}
