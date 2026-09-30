package be.nealgysemans.focusmodes.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import be.nealgysemans.focusmodes.di.AppGraph
import be.nealgysemans.focusmodes.engine.ActivationSource
import be.nealgysemans.focusmodes.engine.Direction
import be.nealgysemans.focusmodes.engine.TriggerEvent

/**
 * Handles the ongoing notification's "Turn off" action.
 *
 * Emits a [ActivationSource.USER] deactivation, which both stops the mode and
 * clears the pin — the user said off, so the next scheduled window is allowed to
 * take over again.
 *
 * `goAsync` is load-bearing and was missing. A `BroadcastReceiver` is killable the moment
 * `onReceive` returns, and the engine work this hands off runs on another thread — so
 * without holding the result open, a tap on "Turn off" could lose its own transition and
 * leave the phone silent with the notification gone. Every other receiver in the app
 * already did this; the one that the user actually taps did not.
 */
class TurnOffReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val modeId = intent.getStringExtra(EXTRA_MODE_ID) ?: return
        // This receiver can be the first thing to start the process (the notification
        // outlives every one of our activities), so the observer that clears the
        // notification afterwards has to be started here too.
        SurfaceSync.start(context)
        val pending = goAsync()
        // The optimistic tile flip and the nudge come with the shared protocol: the shade
        // collapses immediately after this tap, and the tile behind it should already
        // read "Off".
        AppGraph.from(context).submitUserToggleAsync(
            TriggerEvent(ActivationSource.USER, modeId, Direction.DEACTIVATE),
        ) { pending.finish() }
    }

    companion object {
        const val EXTRA_MODE_ID = "mode_id"
    }
}
