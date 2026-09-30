package be.nealgysemans.focusmodes.notification

import android.content.Context
import android.util.Log
import be.nealgysemans.focusmodes.di.AppGraph
import be.nealgysemans.focusmodes.tile.TileNudge
import be.nealgysemans.focusmodes.tile.TilePreferences
import be.nealgysemans.focusmodes.tile.TileSnapshot
import be.nealgysemans.focusmodes.tile.TileSnapshotSource
import be.nealgysemans.focusmodes.tile.TileStateCache
import be.nealgysemans.focusmodes.widget.FocusWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The one observer that keeps every surface honest.
 *
 * The problem it solves: six things can change which mode is on (the tile, the tile's
 * picker, the long-press grid, the app's switches, the home-screen widget, a schedule
 * boundary) and four surfaces have to reflect it (the tile, the ongoing notification, the
 * widget, the UI). Wiring each writer to each surface is two dozen edges and a guaranteed
 * drift bug. Instead every writer goes through `ModeEngine`, the engine's result lands in
 * Room / DataStore, and this collector fans *one* derived snapshot back out:
 *
 *  - refreshes [TileStateCache], so the tile's click path is warm without doing I/O;
 *  - nudges the tile via [TileNudge], because an `ACTIVE_TILE` only repaints when asked;
 *  - updates every placed home-screen widget via [FocusWidget.refreshAll], because a
 *    widget whose Glance session has been torn down is likewise not watching anything;
 *  - posts or clears the ongoing notification through [StatusNotifier];
 *  - records the active mode as "last used", so an off-tap on the tile knows what to
 *    turn on next — including when the mode was turned on from somewhere else.
 *
 * The UI needs nothing from here: Compose collects the same Room and DataStore flows
 * directly. Nor does a widget with a *live* session, which collects them too — the
 * refresh below is for the far more common case of one that does not.
 *
 * ## Why it is started by hand
 *
 * It must be running in whichever process handled the change, so [start] is called
 * from every surface entry point: the tile service, both activities, the notification's
 * "Turn off" receiver, and the widget — from both its render (`provideGlance`) and its
 * tap (`ToggleModeAction`), because either can be the thing that starts the process. It
 * is idempotent and cheap, so calling it on every entry is the safe default rather than
 * something to be careful about.
 *
 * `FocusModesApplication.onCreate` calls it too, which is what covers the schedule-alarm
 * and boot paths in a cold process where no surface of ours starts first. The per-surface
 * calls are still not redundant: `AppGraph.from` has a fallback for processes where a
 * custom `Application` subclass is not the one responding, and in one of those the
 * Application call never happens.
 *
 * ## Why it does not fan out twice per activation
 *
 * [apply] writes `lastUsedModeId`, and that value is *part of the snapshot it is
 * observing* — so every activation produces a second emission whose only difference is
 * the write this observer just made. Left alone that is a full second fan-out per
 * toggle: a tile nudge, a Glance re-render of every placed widget and a notification
 * re-post, for a value none of those three draws. So the last applied snapshot is kept
 * and the second pass is recognised for what it is: the cache is still refreshed (the
 * tile's click path decides from `lastUsedModeId`, so it must see the new value) and
 * nothing else runs.
 *
 * Skipping the widget is safe for the same reason it is worth doing. `lastUsedModeId`
 * only changes at the moment a mode comes *on*, and while a mode is on the widget's
 * toggle zone reads "turn this off" — a decision that does not consult last-used at all.
 * By the time it does matter, the mode has gone off, which is an emission of its own.
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
            // One collector, one coroutine — which is what makes the two `lastX` fields
            // below safe as plain vars: nothing else ever touches them.
            TileSnapshotSource.flow(app).collect { snapshot ->
                runCatching { apply(app, graph, snapshot, preferences) }
                    .onFailure { Log.w(TAG, "surface sync failed", it) }
            }
        }
        Log.d(TAG, "observing")
    }

    /**
     * The snapshot the last fan-out was performed for, or null before the first one.
     *
     * Two jobs. It is how the last-used echo is recognised (see the class KDoc), and
     * `null` is also how "this is the first pass in this process" is known — which the
     * notification's clear path needs, because a notification outlives the process that
     * posted it.
     */
    private var lastApplied: TileSnapshot? = null

    /** What the ongoing notification was last posted with, or null when it is cleared. */
    private var lastShown: Shown? = null

    /**
     * Exactly the values [StatusNotifier.show] renders.
     *
     * Kept as its own type so "has anything the user would see changed?" is an equality
     * check the compiler writes, rather than five comparisons someone has to remember to
     * extend when a sixth value joins the notification.
     */
    private data class Shown(
        val modeId: String,
        val name: String,
        val color: Int,
        val glyphRes: Int,
        val since: Long,
    )

    private suspend fun apply(
        app: Context,
        graph: AppGraph,
        snapshot: TileSnapshot,
        preferences: TilePreferences,
    ) {
        val previous = lastApplied
        lastApplied = snapshot

        // Always, and first: the tile's click path reads this and decides from it, so it
        // has to be current even on a pass where nothing is repainted.
        TileStateCache.publish(snapshot)

        // "Last used" means last used anywhere. Guarded so this write cannot loop: the
        // write lands in the observed snapshot and comes straight back through here.
        val active = snapshot.activeMode
        if (active != null && snapshot.lastUsedModeId != active.id) {
            preferences.setLastUsedModeId(active.id)
        }

        // The echo of the write above, arriving as its own emission. Nothing any surface
        // draws has changed, so nothing is repainted.
        if (previous != null && previous.copy(lastUsedModeId = snapshot.lastUsedModeId) == snapshot) {
            return
        }

        TileNudge.refresh(app)
        // Before the notification, deliberately: the widget is on screen the moment the
        // user looks at the home screen, while the notification is behind a shade pull.
        // `refreshAll` swallows its own failures, so a launcher that refuses an update
        // cannot stop the rest of this fan-out.
        FocusWidget.refreshAll(app)

        if (active == null) {
            // `previous == null` is the first pass of this process, where "we have not
            // posted one" is not the same as "there is none" — a notification survives
            // the process that posted it, so the first idle pass always clears.
            if (previous == null || lastShown != null) {
                graph.statusNotifier.clear()
                lastShown = null
            }
            return
        }

        val shown = Shown(
            modeId = active.id,
            name = active.name,
            color = active.color,
            glyphRes = active.glyphRes,
            since = snapshot.activeSince,
        )
        // A tile-preference change, a DND grant change or an edit to some *other* mode
        // all arrive here with the notification's own values untouched. Re-posting an
        // identical notification is a binder call and a shade animation for nothing.
        if (shown == lastShown) return
        lastShown = shown

        graph.statusNotifier.show(
            modeId = shown.modeId,
            name = shown.name,
            color = shown.color,
            glyphRes = shown.glyphRes,
            since = shown.since,
        )
    }

    private const val TAG = "SurfaceSync"
}
