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
 */
class TurnOffReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val modeId = intent.getStringExtra(EXTRA_MODE_ID) ?: return
        AppGraph.from(context).submitAsync(
            TriggerEvent(ActivationSource.USER, modeId, Direction.DEACTIVATE),
        )
    }

    companion object {
        const val EXTRA_MODE_ID = "mode_id"
    }
}
