package be.nealgysemans.focusmodes.notification

import android.content.Context
import android.util.Log
import be.nealgysemans.focusmodes.di.AppGraph
import be.nealgysemans.focusmodes.tile.TileNudge
import be.nealgysemans.focusmodes.tile.TilePreferences
import be.nealgysemans.focusmodes.tile.TileSnapshot
import be.nealgysemans.focusmodes.tile.TileSnapshotSource
import be.nealgysemans.focusmodes.tile.TileStateCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The one observer that keeps every surface honest.
 *
 * The problem it solves: five things can change which mode is on (the tile, the tile's
 * picker, the long-press grid, the app's switches, a schedule boundary) and three
 * surfaces have to reflect it (the tile, the ongoing notification, the UI). Wiring each
 * writer to each surface is fifteen edges and a guaranteed drift bug. Instead every
 * writer goes through `ModeEngine`, the engine's result lands in Room / DataStore, and
 * this collector fans *one* derived snapshot back out:
 *
 *  - refreshes [TileStateCache], so the tile's click path is warm without doing I/O;
 *  - nudges the tile via [TileNudge], because an `ACTIVE_TILE` only repaints when asked;
 *  - posts or clears the ongoing notification through [StatusNotifier];
 *  - records the active mode as "last used", so an off-tap on the tile knows what to
 *    turn on next — including when the mode was turned on from somewhere else.
 *
 * The UI needs nothing from here: Compose collects the same Room and DataStore flows
 * directly.
 *
 * ## Why it is started by hand
 *
 * It must be running in whichever process handled the change, so [start] is called
 * from every surface entry point in this module: the tile service, both activities, and
 * the notification's "Turn off" receiver. It is idempotent and cheap, so calling it on
 * every entry is the safe default rather than something to be careful about.
 *
 * A single call from `FocusModesApplication.onCreate` (or from `AppGraph`'s
 * post-transition hook) would also cover the schedule-alarm and boot paths in a cold
 * process, where no surface of ours starts first. That file belongs to another module;
 * until the call lands there, an alarm-driven change in a cold process is reflected the
 * next time any surface starts — the engine state itself is already correct.
 */
object SurfaceSync {

    private val started = AtomicBoolean(false)

    /**
     * Begin observing, once per process.
     *
     * The scope is deliberately unbounded: it outlives the tile service, the activities
     * and the receiver that may have started it, because the notification has to be
     * updated even when none of them is alive.
     */
    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        val graph = AppGraph.from(app)
        val preferences = TilePreferences(app)
        // Normally `FocusModesApplication` has already done this. Repeated here because
        // `AppGraph.from` has a fallback path for processes where a custom Application is
        // not the one responding, and notifying on a channel that does not exist is a
        // silent no-op — exactly the failure this whole observer exists to prevent.
        runCatching { graph.statusNotifier.ensureChannel() }
            .onFailure { Log.w(TAG, "ensureChannel failed", it) }

        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            // Already distinct-until-changed at the source, so a write made from here
            // (last-used) cannot bounce back in as a new emission.
            TileSnapshotSource.flow(app).collect { snapshot ->
                runCatching { apply(app, graph, snapshot, preferences) }
                    .onFailure { Log.w(TAG, "surface sync failed", it) }
            }
        }
        Log.d(TAG, "observing")
    }

    private suspend fun apply(
        app: Context,
        graph: AppGraph,
        snapshot: TileSnapshot,
        preferences: TilePreferences,
    ) {
        TileStateCache.publish(snapshot)
        TileNudge.refresh(app)

        val active = snapshot.activeMode
        if (active == null) {
            graph.statusNotifier.clear()
            return
        }

        graph.statusNotifier.show(
            modeId = active.id,
            name = active.name,
            color = active.color,
            glyphRes = active.glyphRes,
            since = snapshot.activeSince,
        )

        // "Last used" means last used anywhere. Guarded so this write cannot loop.
        if (snapshot.lastUsedModeId != active.id) {
            preferences.setLastUsedModeId(active.id)
        }
    }

    private const val TAG = "SurfaceSync"
}
