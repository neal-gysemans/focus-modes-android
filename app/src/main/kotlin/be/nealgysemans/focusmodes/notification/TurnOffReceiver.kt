package be.nealgysemans.focusmodes.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import be.nealgysemans.focusmodes.di.AppGraph
import be.nealgysemans.focusmodes.engine.ActivationSource
import be.nealgysemans.focusmodes.engine.Direction
import be.nealgysemans.focusmodes.engine.TriggerEvent
import be.nealgysemans.focusmodes.tile.TileStateCache

/**
 * Handles the ongoing notification's "Turn off" action.
 *
 * Emits a [ActivationSource.USER] deactivation, which both stops the mode and
 * clears the pin — the user said off, so the next scheduled window is allowed to
 * take over again.
 */
class TurnOffReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val modeId = intent.getStringExtra(EXTRA_MODE_ID) ?: return
        // This receiver can be the first thing to start the process (the notification
        // outlives every one of our activities), so the observer that clears the
        // notification afterwards has to be started here too.
        SurfaceSync.start(context)
        // Same optimistic discipline as the tile: the shade collapses immediately after
        // this tap, and the tile behind it should already read "Off".
        TileStateCache.flipTo(null)
        AppGraph.from(context).submitAsync(
            TriggerEvent(ActivationSource.USER, modeId, Direction.DEACTIVATE),
        )
    }

    companion object {
        const val EXTRA_MODE_ID = "mode_id"
    }
}
