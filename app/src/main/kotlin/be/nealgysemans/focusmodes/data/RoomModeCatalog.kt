package be.nealgysemans.focusmodes.data

import be.nealgysemans.focusmodes.engine.FocusMode
import be.nealgysemans.focusmodes.engine.ModeCatalog
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicReference

/**
 * Room-backed [ModeCatalog].
 *
 * Holds a snapshot so the engine's synchronous `modes()` does not hit SQLite on
 * every call inside one reconcile pass. Anything that writes a mode must call
 * [invalidate]; that is a deliberate, visible coupling rather than a cache with
 * an invisible TTL.
 *
 * Same threading contract as [ActiveStatePreferences]: only ever touched from the
 * engine's single background dispatcher.
 */
class RoomModeCatalog(private val modeDao: ModeDao) : ModeCatalog {

    private val snapshot = AtomicReference<List<FocusMode>?>(null)

    override fun modes(): List<FocusMode> =
        snapshot.get() ?: runBlocking { modeDao.getModes().map(ModeEntity::toDomain) }
            .also(snapshot::set)

    /** Drop the snapshot after a write so the next reconcile re-reads. */
    fun invalidate() = snapshot.set(null)

    /**
     * Cache the system's rule id on [modeId]'s row, or clear it with a null [ruleId].
     *
     * The write and the [invalidate] belong together, which is why they are one call: the
     * two callers that need this — the adapter handing back a freshly created id, and the
     * `AUTOMATIC_RULE_STATUS_REMOVED` path dropping a dead one — had the pair open-coded,
     * and a write without the invalidate leaves the engine reading a snapshot in which the
     * rule id is still the old one. That produces a duplicate rule on the next reconcile,
     * or an update aimed at a rule the system has already forgotten.
     */
    fun setZenRuleId(modeId: String, ruleId: String?) {
        runBlocking { modeDao.setZenRuleId(modeId, ruleId) }
        invalidate()
    }
}
