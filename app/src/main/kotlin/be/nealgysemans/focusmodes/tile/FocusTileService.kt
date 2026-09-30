package be.nealgysemans.focusmodes.tile

import android.app.PendingIntent
import android.content.Intent
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
import be.nealgysemans.focusmodes.ui.MainActivity
import be.nealgysemans.focusmodes.ui.ModeGlyphs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Quick Settings tile — the app's primary surface.
 *
 * Registered as an **active** tile (`META_DATA_ACTIVE_TILE`) so state changes from any
 * surface can push a repaint through [TileNudge], and as a **toggleable** tile
 * (`META_DATA_TOGGLEABLE_TILE`) so the platform renders on/off semantics rather than a
 * launcher shortcut.
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
     * True between [onStartListening] and [onStopListening], i.e. while `qsTile` is
     * a valid handle. Guards the async repaint that lands after a warm-up.
     */
    @Volatile
    private var listening = false

    override fun onCreate() {
        super.onCreate()
        // The tile is often the first component to start the process, so it is also
        // where the cross-surface observer gets kicked off. Idempotent.
        SurfaceSync.start(applicationContext)
        ModeGlyphs.ALL_RES.forEach { res -> icons[res] = Icon.createWithResource(this, res) }
    }

    override fun onDestroy() {
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
        listening = true
        // Paint immediately from whatever is already warm — on a live process this is
        // current, and painting before the reads means the shade never shows a blank
        // tile while DataStore is opening. On a genuinely cold process the cache would
        // say "no modes, no grant", which renders as UNAVAILABLE — so nothing is painted
        // until the read lands, leaving whatever the platform last had rather than
        // flashing the tile out and back.
        if (TileStateCache.warm) render(TileStateCache.value)
        warmUp()
    }

    override fun onStopListening() {
        listening = false
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
            TileStateCache.publish(snapshot)
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
     * Reconcile and repaint.
     *
     * [AppGraph.reconcileAsync] is the engine-truth half: it heals a rule the user
     * edited in Settings and converges on what the clock says should be on, so the
     * tile cannot show a mode the system has already dropped. The snapshot read that
     * follows is the display half. Both are off the main thread; the repaint hops back
     * and is skipped if the listening window closed in the meantime.
     */
    private fun warmUp() {
        scope.launch {
            AppGraph.from(applicationContext).reconcileAsync()
            val snapshot = runCatching { TileSnapshotSource.read(applicationContext) }
                .onFailure { Log.w(TAG, "snapshot read failed", it) }
                .getOrNull() ?: return@launch
            TileStateCache.publish(snapshot)
            withContext(Dispatchers.Main) {
                if (listening) render(snapshot)
            }
        }
    }

    private fun render(snapshot: TileSnapshot) {
        val active = snapshot.activeMode
        paint(
            state = when {
                // Never claim to be armed without the grant that makes arming possible.
                !snapshot.dndGranted || snapshot.modes.isEmpty() -> Tile.STATE_UNAVAILABLE
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
        tile.label = getString(R.string.tile_label)
        tile.subtitle = subtitle
        tile.icon = icons[glyphRes] ?: Icon.createWithResource(this, glyphRes)
        tile.contentDescription = getString(R.string.tile_label)
        tile.stateDescription = subtitle
        tile.updateTile()
    }

    private val offLabel: String get() = getString(R.string.tile_subtitle_off)

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

    private fun onPicked(snapshot: TileSnapshot, picked: String?) {
        val current = TileStateCache.value.activeModeId
        when {
            picked == null && current != null -> {
                flip(snapshot, null)
                dispatch(current, Direction.DEACTIVATE)
            }

            picked != null -> {
                flip(snapshot, picked)
                dispatch(picked, Direction.ACTIVATE)
            }

            else -> Unit // "Off" picked while already off.
        }
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
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        runCatching { startActivityAndCollapse(pending) }
            .onFailure { Log.w(TAG, "startActivityAndCollapse failed", it) }
    }

    private companion object {
        const val TAG = "FocusTileService"
    }
}
