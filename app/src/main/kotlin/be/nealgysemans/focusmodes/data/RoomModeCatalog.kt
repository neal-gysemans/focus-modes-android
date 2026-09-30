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
}
