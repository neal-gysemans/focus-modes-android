package be.nealgysemans.focusmodes.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.util.Log
import be.nealgysemans.focusmodes.R
import be.nealgysemans.focusmodes.engine.FocusMode
import be.nealgysemans.focusmodes.ui.MainActivity
import be.nealgysemans.focusmodes.ui.ModeGlyphs

/**
 * The ongoing notification shown while a mode is active, with a one-tap turn-off.
 *
 * Why it exists: a zen rule silences the phone, and a silenced phone with no visible
 * reason is indistinguishable from a broken one. The notification is the app's answer
 * to "why is it quiet, and how do I stop it?" without opening the app.
 *
 * Requires `POST_NOTIFICATIONS` (runtime grant). If the user declines, `notify()` is a
 * silent no-op and the app still works but loses that escape hatch, so
 * `health/PermissionHealth` reports it as a degradation rather than a hard blocker and
 * `ui/MainActivity` asks for it at the moment it first matters.
 *
 * Posting is driven by [SurfaceSync], which observes engine state — so the notification
 * is re-posted on every state change no matter which surface caused it, and a user who
 * swipes it away (allowed for ongoing notifications since Android 14) gets it back on
 * the next change rather than being nagged immediately.
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
    fun show(mode: FocusMode, since: Long) = show(
        modeId = mode.id,
        name = mode.name,
        color = mode.color,
        glyphRes = ModeGlyphs.resFor(mode.iconKey),
        since = since,
    )

    /**
     * Show (or update) the ongoing notification from already-resolved display values.
     *
     * This overload exists for [SurfaceSync], which holds a flattened snapshot rather
     * than a [FocusMode] — the tile and the notification then provably draw the same
     * glyph and the same accent, because they are handed the same two values.
     *
     * One fixed notification id, so switching modes replaces the notification instead
     * of stacking a second one.
     */
    fun show(modeId: String, name: String, color: Int, glyphRes: Int, since: Long) {
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(glyphRes)
            .setColor(color)
            .setContentTitle(name)
            .setContentText(context.getString(R.string.notification_active_text))
            // Ongoing so it sits with the other status notifications rather than in the
            // conversation area. Users can still dismiss it (Android 14+), which is fine:
            // dismissing it does not turn the mode off, and the next state change re-posts.
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            // "On since 09:14", counting up — the single most useful thing to know about
            // a mode you did not turn on yourself.
            .setShowWhen(true)
            .setWhen(since.takeIf { it > 0L } ?: System.currentTimeMillis())
            .setUsesChronometer(true)
            .setContentIntent(openAppIntent())
            .addAction(turnOffAction(modeId))
            .build()

        runCatching { notificationManager.notify(NOTIFICATION_ID, notification) }
            .onFailure { Log.w(TAG, "notify failed", it) }
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
    private fun turnOffAction(modeId: String): Notification.Action = Notification.Action.Builder(
        Icon.createWithResource(context, ModeGlyphs.OFF_RES),
        context.getString(R.string.notification_turn_off),
        turnOffIntent(modeId),
    ).build()

    private fun turnOffIntent(modeId: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        modeId.hashCode(),
        Intent(context, TurnOffReceiver::class.java)
            .putExtra(TurnOffReceiver.EXTRA_MODE_ID, modeId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * Tapping the body opens the app.
     *
     * `CLEAR_TOP` rather than a fresh task: there is only one activity, and a user
     * arriving from the notification should land on it, not on a second copy.
     */
    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        OPEN_APP_REQUEST_CODE,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val TAG = "StatusNotifier"
        const val CHANNEL_ID = "focus_status"
        const val NOTIFICATION_ID = 1

        /**
         * Cannot collide with the per-mode turn-off codes: those are broadcast
         * PendingIntents and this is an activity one, which the platform keys separately.
         */
        const val OPEN_APP_REQUEST_CODE = 0
    }
}
