package be.nealgysemans.focusmodes.schedule

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import be.nealgysemans.focusmodes.di.AppGraph

/**
 * Wakes on the armed boundary alarm and asks the engine to reconcile, then re-arms.
 *
 * Note what it does *not* do: it carries no mode id and no direction. The alarm
 * is only a "recompute now" signal, so an alarm that fires late, twice, or for a
 * schedule the user has since edited still lands on the correct state.
 *
 * Also handles `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`. That
 * broadcast is documented to reach manifest receivers, and it matters because
 * revoking the grant *deletes* every exact alarm the app had set: when the user
 * grants it again there is nothing armed until we re-arm here.
 */
class ScheduleAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val graph = AppGraph.from(context)
        // The engine reads Room and DataStore, so the work leaves the main thread;
        // goAsync keeps this receiver (and its process) alive until it finishes.
        val pending = goAsync()

        when (intent.action) {
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> {
                // Only the alarm needs re-planting — nothing about the clock or the
                // user's modes changed, so there is nothing to reconcile.
                Log.i(TAG, "exact-alarm grant changed; re-arming the boundary alarm")
                graph.rearmAsync { pending.finish() }
            }

            else -> {
                Log.d(TAG, "boundary alarm fired")
                graph.reconcileAsync { pending.finish() }
            }
        }
    }

    private companion object {
        const val TAG = "ScheduleAlarmReceiver"
    }
}
