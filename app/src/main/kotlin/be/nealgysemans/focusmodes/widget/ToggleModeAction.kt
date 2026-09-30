package be.nealgysemans.focusmodes.widget

import android.content.Context
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import be.nealgysemans.focusmodes.di.AppGraph
import be.nealgysemans.focusmodes.engine.ActivationSource
import be.nealgysemans.focusmodes.engine.Direction
import be.nealgysemans.focusmodes.engine.TriggerEvent
import be.nealgysemans.focusmodes.notification.SurfaceSync
import be.nealgysemans.focusmodes.tile.TileSnapshotSource
import be.nealgysemans.focusmodes.tile.TileStateCache

/**
 * The widget's only action: toggle one named mode, through `ModeEngine`.
 *
 * Every tap on every part of the widget arrives here with a mode id — the single
 * "current Focus" button resolves *which* mode at render time (see
 * `FocusWidget.focusButtonAction`), and each per-mode button names its own. That leaves
 * this callback with one decision, "on or off", and it makes it from stored state rather
 * than from a parameter: a RemoteViews tree can sit on a home screen for hours, so a
 * direction baked in at render time could easily be the wrong one by the time it is
 * tapped. A mode id cannot go stale in the same way — at worst it names a deleted mode,
 * which the engine already rejects as `UNKNOWN_MODE`.
 *
 * Why an `ActionCallback` and not a `clickable { }` lambda: a lambda action only works
 * while the Glance session that created it is alive, and a widget on a home screen is
 * usually tapped long after its session is gone. A callback is a broadcast to a class
 * name, so it survives process death — which is the normal case here, not the edge one.
 *
 * ## Latency
 *
 * Unlike the tile, there is no optimistic flip of the widget itself. The tile needs one
 * because the shade is closing around it within a frame or two of the tap; a widget stays
 * on screen, and the engine round trip was measured at ~36 ms on the 17T Pro, which
 * arrives via `SurfaceSync` before a finger has left the glass. What *is* worth flipping
 * eagerly is the tile's cache, because the user's next glance may well be at the shade.
 */
class ToggleModeAction : ActionCallback {

    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        // A widget tap can be what starts the process, and without the observer running
        // nothing would fan the resulting change back out to the widget, the tile cache
        // or the ongoing notification. Idempotent.
        SurfaceSync.start(context)

        val modeId = parameters[MODE_ID] ?: run {
            Log.w(TAG, "widget tap with no mode id")
            return
        }

        // The warm cache when there is one (the common case: the tile or the widget's own
        // session has already filled it), a real read when there is not. Either way this
        // is the same snapshot every other surface decides from.
        val snapshot = if (TileStateCache.warm) {
            TileStateCache.value
        } else {
            runCatching { TileSnapshotSource.read(context) }
                .onFailure { Log.w(TAG, "snapshot read failed", it) }
                .getOrNull() ?: return
        }

        val direction = if (snapshot.activeModeId == modeId) {
            Direction.DEACTIVATE
        } else {
            Direction.ACTIVATE
        }

        // The same flip-submit-nudge every hand-made toggle in the app performs, owned by
        // `AppGraph` so this surface cannot drift from the other four. The tile flip and
        // nudge are part of it because the tile has a much tighter repaint window than a
        // widget does and may well be where the user looks next. `SurfaceSync` still has
        // the last word for every surface, including this one.
        AppGraph.from(context).submitUserToggleAsync(
            TriggerEvent(ActivationSource.USER, modeId, direction),
        )
        Log.d(TAG, "widget tap -> $direction $modeId")
    }

    companion object {

        /**
         * The click action for [modeId].
         *
         * Built at render time and embedded in the RemoteViews, so the parameter travels
         * with the tap rather than being looked up when it arrives.
         */
        fun of(modeId: String): Action =
            actionRunCallback<ToggleModeAction>(actionParametersOf(MODE_ID to modeId))

        private val MODE_ID = ActionParameters.Key<String>("modeId")

        private const val TAG = "ToggleModeAction"
    }
}
