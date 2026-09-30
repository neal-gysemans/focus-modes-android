package be.nealgysemans.focusmodes.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import be.nealgysemans.focusmodes.di.AppGraph

/**
 * Re-establishes state after a reboot.
 *
 * Two things are lost across a reboot: every armed alarm, and any belief the
 * system had about our rules' condition state. So this does not try to replay
 * anything — it calls `ModeEngine.reconcile()`, which recomputes the desired mode
 * from the clock and converges, then re-arms the next boundary.
 *
 * Also handles `ACTION_MY_PACKAGE_REPLACED`, because an app update has exactly the
 * same effect on armed alarms as a reboot.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED_ACTIONS) return
        Log.d(TAG, "reconciling after ${intent.action}")
        // TODO(skeleton): goAsync() so the reconcile can finish off the main
        //  thread within the receiver's lifetime, then AlarmScheduler.rearm().
        AppGraph.from(context).reconcileAsync()
    }

    private companion object {
        const val TAG = "BootReceiver"
        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
