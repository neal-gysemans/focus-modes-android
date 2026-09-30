package be.nealgysemans.focusmodes.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import be.nealgysemans.focusmodes.engine.ScheduleSource
import java.time.Clock
import java.time.ZonedDateTime

/**
 * Arms exactly one alarm: the next instant at which the desired mode could change.
 *
 * One alarm rather than one per trigger, because the engine reconciles from the
 * wall clock anyway — the alarm is only a nudge to wake up and recompute, so a
 * missed one costs a late transition, never a wrong state. That also keeps the app
 * well inside the exact-alarm budget OEM power managers enforce.
 *
 * Re-armed after every fire, after boot, and after any schedule edit. Nothing here
 * decides *which* mode goes on; that is `ModeEngine.reconcile`'s job.
 */
class AlarmScheduler(
    private val context: Context,
    private val schedules: ScheduleSource,
    private val clock: Clock,
) {

    private val alarmManager: AlarmManager
        get() = context.getSystemService(AlarmManager::class.java)

    /**
     * Cancel any pending alarm and arm the next boundary, if there is one.
     *
     * Idempotent: the [PendingIntent] uses a fixed request code, so re-arming
     * replaces rather than stacks.
     */
    fun rearm() {
        val next = schedules.nextBoundaryAfter(ZonedDateTime.now(clock))
        if (next == null) {
            cancel()
            Log.d(TAG, "rearm: no schedules, nothing armed")
            return
        }

        val triggerAtMillis = next.toInstant().toEpochMilli()
        val pendingIntent = pendingIntent()

        // setExactAndAllowWhileIdle, not setExact: the interesting boundaries are at
        // 23:00 and 07:00, when the device is deep in doze.
        if (alarmManager.canScheduleExactAlarms()) {
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent,
                )
                Log.d(TAG, "armed exact boundary at $next")
                return
            } catch (e: SecurityException) {
                // The grant can be revoked between the check and the call.
                Log.w(TAG, "exact alarm refused; falling back to inexact: ${e.message}")
            }
        }

        // Fallback without the "Alarms & reminders" grant. setAndAllowWhileIdle
        // rather than setWindow: an inexact windowed alarm is deferred to the next
        // doze maintenance window, which for a 23:00 bedtime boundary can be hours,
        // whereas setAndAllowWhileIdle still fires in doze (rate-limited to roughly
        // once every 9-15 minutes per app — fine for a nudge to recompute).
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAtMillis,
            pendingIntent,
        )
        Log.i(TAG, "armed inexact boundary at $next (no exact-alarm grant; expect drift)")
    }

    /** Drop the pending alarm, e.g. when the user disables every schedule. */
    fun cancel() {
        alarmManager.cancel(pendingIntent())
        Log.d(TAG, "cancelled the pending boundary alarm")
    }

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, ScheduleAlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val TAG = "AlarmScheduler"
        const val REQUEST_CODE = 1001
    }
}
