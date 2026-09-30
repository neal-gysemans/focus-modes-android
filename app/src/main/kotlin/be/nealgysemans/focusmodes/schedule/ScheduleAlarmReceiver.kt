package be.nealgysemans.focusmodes.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import be.nealgysemans.focusmodes.di.AppGraph

/**
 * Wakes on the armed boundary alarm and asks the engine to reconcile.
 *
 * Note what it does *not* do: it carries no mode id and no direction. The alarm
 * is only a "recompute now" signal, so an alarm that fires late, twice, or for a
 * schedule the user has since edited still lands on the correct state.
 */
class ScheduleAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "boundary alarm fired")
        // TODO(skeleton): goAsync() + hand off to the engine dispatcher, then
        //  AlarmScheduler.rearm() for the following boundary.
        AppGraph.from(context).reconcileAsync()
    }

    private companion object {
        const val TAG = "ScheduleAlarmReceiver"
    }
}
