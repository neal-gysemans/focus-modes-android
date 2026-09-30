package be.nealgysemans.focusmodes.tile

import android.content.Context
import be.nealgysemans.focusmodes.data.ModeEntity
import be.nealgysemans.focusmodes.di.AppGraph
import be.nealgysemans.focusmodes.ui.ModeGlyphs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

/**
 * Builds [TileSnapshot]s from the three stores that between them say what the tile
 * should show: Room (the modes), `ActiveStatePreferences` (what is on) and
 * [TilePreferences] (how the tile behaves).
 *
 * This is the only place those three are joined, so every surface — tile, picker,
 * long-press grid, ongoing notification, home-screen widget — renders from one
 * derivation and they cannot drift apart. Whichever surface changed the state, the
 * change arrives here as a Flow emission and fans back out from [flow].
 *
 * It is also the widget's state source, which is why the widget keeps no Glance state
 * of its own: `widget/FocusWidget` collects this flow inside its composition, so a
 * widget re-rendered after process death re-derives from Room and DataStore rather than
 * from a copy that could be stale or absent.
 *
 * ## One derivation, not one per collector
 *
 * "The only place those three are joined" used to be true of the *code* and false of the
 * *work*. [flow] built a fresh three-store `combine` on every call, and there are eight
 * live call sites — `SurfaceSync`, the tile's warm-up, its cold tap, both picker
 * activities, the widget's initial read and its composition, the widget's tap callback.
 * Each one opened its own Room query, its own two DataStore collections and its own
 * binder probe for the DND grant, and a placed widget paid for two of them at once.
 *
 * So the derivation is a single process-wide [StateFlow] that every caller shares.
 * `WhileSubscribed` with a five-second grace period is what keeps that from being a leak
 * in the other direction: the upstream stores are only collected while somebody is
 * listening, and the grace period spans a configuration change or an activity hop without
 * tearing the whole thing down and rebuilding it.
 *
 * The shared value is nullable, and the null is load-bearing rather than a default: it
 * means "no derivation has landed yet", which is a state [current] has to be able to
 * report honestly. [flow] filters it out, so no collector ever sees it.
 */
object TileSnapshotSource {

    /**
     * The process-wide derivation, built on first use.
     *
     * Its scope is deliberately unbounded — the flow outlives every surface that might
     * have asked for it first, and `WhileSubscribed` rather than the scope is what bounds
     * the actual work.
     */
    @Volatile
    private var shared: StateFlow<TileSnapshot?>? = null

    /**
     * Live snapshots. Emits on any mode edit, any activation from any surface, and
     * any tile-preference change.
     *
     * The DND grant is re-probed on every emission rather than observed: there is no
     * broadcast for it, the user can revoke it in Settings while the app is
     * backgrounded, and the tile also re-reads on `onStartListening` — so the worst
     * staleness is one shade-open.
     */
    fun flow(context: Context): Flow<TileSnapshot> = shared(context).filterNotNull()

    /** One snapshot, for the tile's warm-up and for the picker's initial state. */
    suspend fun read(context: Context): TileSnapshot = flow(context).first()

    /**
     * The snapshot the shared derivation currently holds, or null when none has landed.
     *
     * A plain volatile read: it neither starts the derivation nor waits for it, which is
     * what lets the widget use it as a first frame and only fall back to [read] when there
     * is genuinely nothing to draw yet.
     */
    fun current(): TileSnapshot? = shared?.value

    private fun shared(context: Context): StateFlow<TileSnapshot?> {
        shared?.let { return it }
        val app = context.applicationContext
        return synchronized(this) {
            shared ?: derive(app).also { shared = it }
        }
    }

    private fun derive(app: Context): StateFlow<TileSnapshot?> {
        val graph = AppGraph.from(app)
        val tilePreferences = TilePreferences(app)
        return combine(
            graph.database.modeDao().observeModes(),
            graph.activeStateStore.flow,
            tilePreferences.flow,
        ) { modes, active, tilePrefs ->
            TileSnapshot(
                modes = modes.map(ModeEntity::toTileMode),
                activeModeId = active.activeModeId,
                activeSince = active.since,
                lastUsedModeId = tilePrefs.lastUsedModeId,
                tapBehavior = tilePrefs.tapBehavior,
                dndGranted = graph.permissionHealth.dndGranted(),
            )
        }
            .distinctUntilChanged()
            // The combine body opens Room and makes a binder call for the DND grant, and
            // one collector of this is a Compose composition — which would run both on
            // the main thread. Pinned to IO here rather than remembered at every call site.
            .flowOn(Dispatchers.IO)
            .stateIn(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                started = SharingStarted.WhileSubscribed(IDLE_GRACE_MS),
                initialValue = null,
            )
    }

    /**
     * How long the upstream stores stay collected after the last collector leaves.
     *
     * Long enough to cover an activity finishing while another starts (the widget's picker
     * handing back to the widget, a rotation), short enough that a backgrounded app is not
     * holding a Room observer and two DataStore collections open for nothing.
     */
    private const val IDLE_GRACE_MS = 5_000L
}

/** Resolve the stored `iconKey` to a drawable once, off the tile's click path. */
private fun ModeEntity.toTileMode(): TileMode = TileMode(
    id = id,
    name = name,
    glyphRes = ModeGlyphs.resFor(iconKey),
    color = color,
)
