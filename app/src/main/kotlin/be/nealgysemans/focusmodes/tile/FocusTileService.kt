package be.nealgysemans.focusmodes.tile

import android.app.PendingIntent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import be.nealgysemans.focusmodes.R
import be.nealgysemans.focusmodes.di.AppGraph
import be.nealgysemans.focusmodes.engine.ActivationSource
import be.nealgysemans.focusmodes.engine.Direction
import be.nealgysemans.focusmodes.engine.TriggerEvent
import be.nealgysemans.focusmodes.notification.SurfaceSync
import be.nealgysemans.focusmodes.ui.ModeGlyphs
import be.nealgysemans.focusmodes.ui.mainActivityIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Quick Settings tile — the app's primary surface.
 *
 * Registered as an **active** tile (`META_DATA_ACTIVE_TILE`) so a state change made
 * while the shade is closed can open a listening window through [TileNudge], and as a
 * **toggleable** tile (`META_DATA_TOGGLEABLE_TILE`) so the platform renders on/off
 * semantics rather than a launcher shortcut.
 *
 * While the shade is *open* the nudge is a no-op — the tile is already listening — so
 * repainting is driven by collecting [TileStateCache.snapshots] for the duration of the
 * listening window. Between the two, every change from every surface lands on the tile.
 *
 * ## Latency discipline
 *
 * Spike #2 on the 17T Pro (HyperOS 3.0 / A16) measured tap→flip at 2 ms and
 * tap→rule-confirmed at ~36 ms with exactly this ordering, and the ordering is the
 * reason for it:
 *
 *  1. [onStartListening] pays for the disk reads — the platform always calls it before
 *     [onClick], so the click path can assume a warm [TileStateCache].
 *  2. [onClick] reads one volatile snapshot, decides with a pure function
 *     ([TileSnapshot.tap]), flips `state` / `subtitle` / `icon` from the pre-built
 *     [Icon] cache, and calls `updateTile()`. No DataStore, no SQLite, no allocation
 *     of an `Icon`, no permission probe.
 *  3. Only *then* is the real work dispatched — a [TriggerEvent] to [AppGraph], which
 *     runs it on the engine's own thread.
 *
 * Everything the tile changes still funnels through `ModeEngine`: the tile never
 * touches a zen rule, so the manual-pin and single-active invariants stay in one place.
 */
class FocusTileService : TileService() {

    /** Off the main thread for warm-ups and dispatch; never used from [onClick]'s hot path. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Pre-built tile icons, keyed by drawable id.
     *
     * `Icon.createWithResource` inside `onClick` would be an avoidable allocation on
     * the one path with a latency budget, so every glyph the tile can ever show is
     * built once in [onCreate].
     */
    private val icons = HashMap<Int, Icon>()

    /**
     * Repaints on the main thread. Separate from [scope] because `qsTile` must only be
     * touched from the main thread, and because this one is tied to the listening window
     * rather than to the service's lifetime.
     */
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Collects [TileStateCache.snapshots] for the duration of one listening window.
     *
     * This is what keeps the tile honest while the shade is open. `requestListeningState`
     * only produces an `onStartListening` when the tile is *not* already listening, so a
     * nudge from `SurfaceSync` cannot repaint an open shade — without this collector a
     * mode turned off from the notification, the app or a schedule leaves the tile
     * showing the old mode until the user closes and reopens the shade.
     */
    private var renderJob: Job? = null

    /**
     * The two strings [paint] writes on every repaint, resolved once.
     *
     * `getString` is a resource lookup, and [paint] runs on the click path — three of them
     * per tap (the label twice, plus the off subtitle) for two values that cannot change
     * while the service is alive. `lateinit` rather than `by lazy` so the cost lands in
     * [onCreate] with the icon pre-warm, not on whichever tap happens to be first.
     */
    private lateinit var tileLabel: String
    private lateinit var offLabel: String

    override fun onCreate() {
        super.onCreate()
        // The tile is often the first component to start the process, so it is also
        // where the cross-surface observer gets kicked off. Idempotent.
        SurfaceSync.start(applicationContext)
        tileLabel = getString(R.string.tile_label)
        offLabel = getString(R.string.tile_subtitle_off)
        ModeGlyphs.ALL_RES.forEach { res -> icons[res] = Icon.createWithResource(this, res) }
    }

    override fun onDestroy() {
        renderJob?.cancel()
        mainScope.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        Log.d(TAG, "onTileAdded")
        warmUp()
    }

    override fun onStartListening() {
        super.onStartListening()
        // Paint every snapshot for as long as this listening window lasts. The first
        // value arrives immediately (StateFlow replays), so a live process paints from
        // the warm cache on the same frame the shade opens; a cold one paints nothing
        // until the prime below lands, leaving whatever the platform last had rather
        // than flashing the tile to UNAVAILABLE and back.
        renderJob?.cancel()
        renderJob = mainScope.launch {
            TileStateCache.snapshots.collect { snapshot ->
                if (TileStateCache.warm) render(snapshot)
            }
        }
        warmUp()
    }

    override fun onStopListening() {
        renderJob?.cancel()
        renderJob = null
        super.onStopListening()
    }

    override fun onClick() {
        // ---- HOT PATH BEGINS: volatile reads and pre-built objects only ----
        val snapshot = TileStateCache.value
        // The warm-up started in onStartListening is asynchronous, so a tap that beats it
        // would read the empty default and mistake it for "blocked". Rare (it needs a tap
        // within milliseconds of a cold process starting) and handled honestly: wait for
        // the read, then act. Only this first tap of a process can ever pay for it.
        if (!TileStateCache.warm) {
            Log.d(TAG, "tap before warm-up landed; deferring one tap")
            handleColdTap()
            return
        }
        when (val tap = snapshot.tap()) {
            // Under TapBehavior.CYCLE an Activate can arrive while another mode is on,
            // i.e. mean "switch". Nothing here has to change for that: the flip repaints
            // to the new mode, and the engine's single-active rule takes the old one down
            // as part of applying the activation.
            is TileTap.Activate -> {
                flip(snapshot, tap.modeId)
                // ---- HOT PATH ENDS ----
                dispatch(tap.modeId, Direction.ACTIVATE)
            }

            is TileTap.Deactivate -> {
                flip(snapshot, null)
                // ---- HOT PATH ENDS ----
                dispatch(tap.modeId, Direction.DEACTIVATE)
            }

            // No optimistic flip for these two: nothing has been decided yet, and a
            // tile that flipped and then flipped back would be worse than one that waits.
            TileTap.Ask -> showPicker(snapshot)
            TileTap.Blocked -> openApp()
        }
    }

    /**
     * The one slow tap: read the stores, then re-enter the normal decision.
     *
     * Kept separate from [onClick] so the fast path has no branch that can grow: this
     * function is allowed to touch disk, [onClick] is not.
     */
    private fun handleColdTap() {
        scope.launch {
            val snapshot = runCatching { TileSnapshotSource.read(applicationContext) }
                .onFailure { Log.w(TAG, "cold tap snapshot read failed", it) }
                .getOrNull() ?: return@launch
            TileStateCache.primeIfCold(snapshot)
            withContext(Dispatchers.Main) { onClick() }
        }
    }

    // ------------------------------------------------------------- optimistic flip

    /**
     * Render [nextModeId] as if it were already on, then push it.
     *
     * Everything here is a field read, a pre-built [Icon] and one `updateTile()` binder
     * call. `SurfaceSync` will overwrite the cache with engine truth a few
     * milliseconds later; if the engine disagrees (a pin holds, the mode vanished) the
     * tile corrects itself on that emission.
     */
    private fun flip(snapshot: TileSnapshot, nextModeId: String?) {
        val next = nextModeId?.let { id -> snapshot.modes.firstOrNull { it.id == id } }
        paint(
            state = if (next != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE,
            subtitle = next?.name ?: offLabel,
            glyphRes = next?.glyphRes ?: ModeGlyphs.OFF_RES,
        )
        TileStateCache.flipTo(nextModeId)
    }

    private fun dispatch(modeId: String, direction: Direction) {
        scope.launch {
            AppGraph.from(applicationContext).submitAsync(
                TriggerEvent(ActivationSource.USER, modeId, direction),
            )
        }
    }

    // -------------------------------------------------------------------- rendering

    /**
     * Reconcile, and seed the cache if nothing has filled it yet.
     *
     * [AppGraph.reconcileAsync] is the engine-truth half: it heals a rule the user
     * edited in Settings and converges on what the clock says should be on, so the tile
     * cannot show a mode the system has already dropped. Any state it changes comes back
     * through `SurfaceSync` and repaints the tile via [renderJob], like every other
     * surface's change does.
     *
     * The read is only a *cold-start* seed. It used to publish unconditionally, which
     * was the repaint bug: a read issued here before a tap could complete after it and
     * revert the tile to the pre-tap mode. [TileStateCache.primeIfCold] is a no-op once
     * anything real is in the cache, so a slow read can no longer outrank a fast tap.
     */
    private fun warmUp() {
        scope.launch {
            AppGraph.from(applicationContext).reconcileAsync()
            if (TileStateCache.warm) return@launch
            val snapshot = runCatching { TileSnapshotSource.read(applicationContext) }
                .onFailure { Log.w(TAG, "snapshot read failed", it) }
                .getOrNull() ?: return@launch
            TileStateCache.primeIfCold(snapshot)
        }
    }

    private fun render(snapshot: TileSnapshot) {
        val active = snapshot.activeMode
        paint(
            state = when {
                // Never claim to be armed without the grant that makes arming possible.
                // Same predicate [TileSnapshot.tap] returns Blocked for, so the tile cannot
                // look available and then refuse to act.
                snapshot.blocked -> Tile.STATE_UNAVAILABLE
                active != null -> Tile.STATE_ACTIVE
                else -> Tile.STATE_INACTIVE
            },
            subtitle = active?.name ?: offLabel,
            glyphRes = active?.glyphRes ?: ModeGlyphs.OFF_RES,
        )
    }

    /**
     * The one place the tile is mutated.
     *
     * The subtitle carries the active mode's name because HyperOS hides tile labels by
     * default — the glyph says *which* mode and the subtitle confirms it in words, and
     * neither depends on the label being visible.
     */
    private fun paint(state: Int, subtitle: String, glyphRes: Int) {
        val tile = qsTile ?: return
        tile.state = state
        tile.label = tileLabel
        tile.subtitle = subtitle
        tile.icon = icons[glyphRes] ?: Icon.createWithResource(this, glyphRes)
        tile.contentDescription = tileLabel
        tile.stateDescription = subtitle
        tile.updateTile()
    }

    // ----------------------------------------------------------------------- picker

    /**
     * Show the Compose picker.
     *
     * `showDialog` collapses the shade for us, but it renders *under* the keyguard on a
     * locked secure device — so the locked case is routed through `unlockAndRun` and
     * the dialog is shown after the user authenticates.
     */
    private fun showPicker(snapshot: TileSnapshot) {
        val show = Runnable {
            runCatching {
                showDialog(
                    ComposeModePickerDialog(this, snapshot) { picked -> onPicked(snapshot, picked) },
                )
            }.onFailure { Log.w(TAG, "showDialog failed", it) }
        }
        if (isSecure && isLocked) {
            Log.d(TAG, "tap while locked+secure -> unlockAndRun")
            unlockAndRun(show)
        } else {
            show.run()
        }
    }

    /**
     * Commit a pick from the dialog.
     *
     * The decision is [TileSnapshot.pick]'s, shared with both picker activities, but it is
     * asked of the **cache** rather than of [snapshot]: the dialog can have been open long
     * enough for another surface to change what is on, and "Off" has to turn off whatever
     * is actually on now. [snapshot] is still what the flip paints from — it holds the
     * modes, which cannot have gone anywhere while the dialog was showing them.
     *
     * Not `submitUserToggleAsync`: this service paints itself and must never nudge itself.
     */
    private fun onPicked(snapshot: TileSnapshot, picked: String?) {
        val event = TileStateCache.value.pick(picked)?.toUserEvent() ?: return
        flip(snapshot, picked)
        dispatch(event.modeId, event.direction)
    }

    // -------------------------------------------------------------------- fallback

    /**
     * Send the user into the app, for the case the tile cannot act on — no DND access,
     * or no modes defined. Both are fixable, and only in the app.
     *
     * `startActivityAndCollapse` accepts only the `PendingIntent` overload from
     * targetSdk 34 on; the `Intent` one throws.
     */
    private fun openApp() {
        val pending = PendingIntent.getActivity(
            this,
            0,
            mainActivityIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        runCatching { startActivityAndCollapse(pending) }
            .onFailure { Log.w(TAG, "startActivityAndCollapse failed", it) }
    }

    private companion object {
        const val TAG = "FocusTileService"
    }
}
