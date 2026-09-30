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
 *
 * Both read the same rows in the same order, so they share one SQL constant. That is not
 * tidiness: the engine deciding which mode `TapBehavior.CYCLE` steps to next and the list
 * the user arranged have to be the same sequence, and two identical `ORDER BY` clauses are
 * one edit away from not being.
 */
@Dao
interface ModeDao {

    /** Live list for the UI, ordered the way the user arranged it. */
    @Query(MODES_IN_DISPLAY_ORDER)
    fun observeModes(): Flow<List<ModeEntity>>

    /** One-shot snapshot for the engine. */
    @Query(MODES_IN_DISPLAY_ORDER)
    suspend fun getModes(): List<ModeEntity>

    @Upsert
    suspend fun upsert(mode: ModeEntity)

    /** Cache the system rule id after `ZenAdapter.ensureRule` hands one back. */
    @Query("UPDATE modes SET zen_rule_id = :ruleId WHERE id = :modeId")
    suspend fun setZenRuleId(modeId: String, ruleId: String?)
}

/** `sort_order` is the user's arrangement; `name` only breaks ties within it. */
private const val MODES_IN_DISPLAY_ORDER = "SELECT * FROM modes ORDER BY sort_order ASC, name ASC"

/** Reads and writes for triggers. Only schedule triggers exist so far. */
@Dao
interface TriggerDao {

    /** Snapshot used by `schedule/TriggerScheduleSource` on every reconcile. */
    @Query("SELECT * FROM triggers WHERE enabled = 1")
    suspend fun getEnabledTriggers(): List<TriggerEntity>

    /**
     * Every schedule row, **enabled or not**, for the editor.
     *
     * Disabled rows are the point: the seeded Work and Sleep schedules ship switched off,
     * and the enabled-only query above would hide the very thing the user has to be able to
     * find in order to switch them on. The editor also needs other modes' schedules to spot
     * overlaps, so this is not filtered by mode either.
     *
     * The literal `'SCHEDULE'` matches how [Converters] stores the enum — by name, not
     * ordinal — so this query survives anyone reordering [TriggerType].
     */
    @Query("SELECT * FROM triggers WHERE type = 'SCHEDULE' ORDER BY mode_id ASC, id ASC")
    fun observeSchedules(): Flow<List<TriggerEntity>>

    @Upsert
    suspend fun upsert(trigger: TriggerEntity)

    @Delete
    suspend fun delete(trigger: TriggerEntity)
}
