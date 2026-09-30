package be.nealgysemans.focusmodes.tile

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import be.nealgysemans.focusmodes.di.AppGraph
import be.nealgysemans.focusmodes.engine.ActivationSource
import be.nealgysemans.focusmodes.engine.TriggerEvent

/**
 * The Quick Settings tile — the app's primary surface.
 *
 * Registered in the manifest as an **active** tile
 * (`META_DATA_ACTIVE_TILE = true`) so the app can push label/state updates via
 * `requestListeningState` instead of only refreshing while the shade is open;
 * and as a **toggleable** tile (`META_DATA_TOGGLEABLE_TILE = true`) so the system
 * renders it with on/off semantics rather than as a launcher shortcut.
 *
 * Tap behaviour funnels into `ModeEngine` like everything else: the tile never
 * touches a zen rule, it emits a [TriggerEvent] with [ActivationSource.USER] so
 * the resulting `Condition` reports `SOURCE_USER_ACTION`.
 *
 * The latency and add-tile-flow questions this raises are spike #2's subject.
 */
class FocusTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        // TODO(skeleton): reconcile first, then render from ActiveState:
        //  active -> Tile.STATE_ACTIVE with the mode name as the subtitle,
        //  idle   -> Tile.STATE_INACTIVE, and STATE_UNAVAILABLE when DND access
        //  is missing (PermissionHealth) so the tile cannot lie about being armed.
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
        Log.d(TAG, "onStartListening -> stub")
    }

    override fun onClick() {
        super.onClick()
        // TODO(skeleton): with one mode, toggle it. With several, show the picker
        //  dialog (spike #2) rather than guessing. Locked device -> unlockAndRun.
        //
        // The decision (which mode, on or off) is made on the engine thread, so
        // this handler never blocks the main thread reading DataStore or SQLite —
        // tile taps have a hard latency budget before the shade feels broken.
        AppGraph.from(applicationContext).toggleDefaultModeAsync()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        Log.d(TAG, "tile added")
    }

    private companion object {
        const val TAG = "FocusTileService"
    }
}
