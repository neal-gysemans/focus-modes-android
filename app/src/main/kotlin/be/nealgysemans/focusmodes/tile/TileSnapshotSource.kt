package be.nealgysemans.focusmodes.tile

import android.content.Context
import be.nealgysemans.focusmodes.data.ModeEntity
import be.nealgysemans.focusmodes.di.AppGraph
import be.nealgysemans.focusmodes.ui.ModeGlyphs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn

/**
 * Builds [TileSnapshot]s from the three stores that between them say what the tile
 * should show: Room (the modes), `ActiveStatePreferences` (what is on) and
 * [TilePreferences] (how the tile behaves).
 *
 * This is the only place those three are joined, so every surface — tile, picker,
 * long-press grid, ongoing notification — renders from one derivation and they
 * cannot drift apart. Whichever surface changed the state, the change arrives here
 * as a Flow emission and fans back out from [flow].
 */
object TileSnapshotSource {

    /**
     * Live snapshots. Emits on any mode edit, any activation from any surface, and
     * any tile-preference change.
     *
     * The DND grant is re-probed on every emission rather than observed: there is no
     * broadcast for it, the user can revoke it in Settings while the app is
     * backgrounded, and the tile also re-reads on `onStartListening` — so the worst
     * staleness is one shade-open.
     */
    fun flow(context: Context): Flow<TileSnapshot> {
        val app = context.applicationContext
        val graph = AppGraph.from(app)
        return combine(
            graph.database.modeDao().observeModes(),
            graph.activeStateStore.flow,
            TilePreferences(app).flow,
        ) { modes, active, tilePrefs ->
            TileSnapshot(
                modes = modes.map(ModeEntity::toTileMode),
                activeModeId = active.activeModeId,
                activeSince = active.since,
                lastUsedModeId = tilePrefs.lastUsedModeId,
                alwaysAsk = tilePrefs.alwaysAsk,
                dndGranted = graph.permissionHealth.dndAccess().granted,
            )
        }
            .distinctUntilChanged()
            // The combine body opens Room and makes a binder call for the DND grant, and
            // one collector of this is a Compose composition — which would run both on
            // the main thread. Pinned to IO here rather than remembered at every call site.
            .flowOn(Dispatchers.IO)
    }

    /** One snapshot, for the tile's warm-up and for the picker's initial state. */
    suspend fun read(context: Context): TileSnapshot = flow(context).first()
}

/** Resolve the stored `iconKey` to a drawable once, off the tile's click path. */
private fun ModeEntity.toTileMode(): TileMode = TileMode(
    id = id,
    name = name,
    glyphRes = ModeGlyphs.resFor(iconKey),
    color = color,
)
