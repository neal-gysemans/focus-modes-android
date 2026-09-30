package be.nealgysemans.focusmodes.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import be.nealgysemans.focusmodes.R
import be.nealgysemans.focusmodes.engine.FocusMode

/**
 * The ongoing notification shown while a mode is active, with a one-tap turn-off.
 *
 * Why it exists: a zen rule silences the phone, and a silenced phone with no
 * visible reason is indistinguishable from a broken one. The notification is the
 * app's answer to "why is it quiet, and how do I stop it?" without opening the app.
 *
 * Requires `POST_NOTIFICATIONS` (runtime grant) — if the user declines, the app
 * still works but loses that escape hatch, so `health/PermissionHealth` reports it
 * as a degradation rather than a hard blocker.
 */
class StatusNotifier(private val context: Context) {

    private val notificationManager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    /** Create the low-importance status channel. Idempotent; safe to call on every start. */
    fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_status_name),
            // IMPORTANCE_LOW: visible and persistent, but never makes a sound —
            // a focus app that beeps at you would be self-defeating.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.channel_status_description)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * Show (or update) the ongoing notification for [mode].
     *
     * @param since epoch millis the mode came on, rendered as a chronometer.
     */
    fun show(mode: FocusMode, since: Long) {
        // TODO(skeleton): build and post for real:
        //    val notification = Notification.Builder(context, CHANNEL_ID)
        //        .setSmallIcon(R.drawable.ic_stat_focus)
        //        .setContentTitle(mode.name)
        //        .setOngoing(true)
        //        .setShowWhen(true).setWhen(since).setUsesChronometer(true)
        //        .addAction(turnOffAction(mode.id))
        //        .setContentIntent(openAppIntent())
        //        .build()
        //    notificationManager.notify(NOTIFICATION_ID, notification)
        //  One fixed id, so a mode switch replaces rather than stacks.
        Log.d(TAG, "show(${mode.id}, since=$since) -> stub")
    }

    /** Clear the notification when no mode is active. */
    fun clear() {
        notificationManager.cancel(NOTIFICATION_ID)
    }

    /**
     * The "Turn off" action.
     *
     * Routes through [TurnOffReceiver], which emits a `USER` trigger event, so the
     * turn-off also clears the pin — tapping it must not leave a pin that blocks
     * the next schedule.
     */
    private fun turnOffIntent(modeId: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        modeId.hashCode(),
        Intent(context, TurnOffReceiver::class.java)
            .putExtra(TurnOffReceiver.EXTRA_MODE_ID, modeId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val TAG = "StatusNotifier"
        const val CHANNEL_ID = "focus_status"
        const val NOTIFICATION_ID = 1
    }
}
