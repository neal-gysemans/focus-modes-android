package be.nealgysemans.zenspike

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Posts the probe notifications. Two channels:
 *  - [CHANNEL_NORMAL]: an ordinary channel. If an active rule is doing its job, this
 *    one should be silenced (and, per our ZenPolicy, not peek).
 *  - [CHANNEL_BYPASS]: created with setBypassDnd(true). This previews the relay path
 *    the real app would use to let allowed notifications through.
 *
 * Both post after a delay so the phone can be locked first — screen-off is where
 * HyperOS aggressiveness usually shows up.
 */
object Notifier {

    const val CHANNEL_NORMAL = "spike-normal"
    const val CHANNEL_BYPASS = "spike-bypass"
    const val DELAY_MILLIS = 5_000L

    private const val TAG = "notify"
    private var nextId = 1000

    // Deliberately process-scoped, not tied to the Activity: the user will background
    // the app (or lock the screen) during the 5 s window.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_NORMAL,
                "Spike — normal channel",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Ordinary channel. Should be silenced while the spike rule is active."
                setBypassDnd(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_BYPASS,
                "Spike — bypass DND channel",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "setBypassDnd(true). Previews the relay path for allowed senders."
                // Only honoured while the app holds notification policy access.
                setBypassDnd(true)
            },
        )
        val bypass = nm.getNotificationChannel(CHANNEL_BYPASS)
        EventLog.info(
            TAG,
            "channels ready; bypass channel canBypassDnd=${bypass?.canBypassDnd()} " +
                "(false here means the OS refused the flag)",
        )
    }

    fun hasPostPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** Schedules one notification [DELAY_MILLIS] from now. */
    fun postDelayed(context: Context, bypassDnd: Boolean) {
        val channelId = if (bypassDnd) CHANNEL_BYPASS else CHANNEL_NORMAL
        val id = nextId++
        if (!hasPostPermission(context)) {
            EventLog.error(TAG, "POST_NOTIFICATIONS not granted — notification will be dropped")
        }
        EventLog.action(
            TAG,
            "scheduling notification #$id on '$channelId' in ${DELAY_MILLIS / 1000}s — lock the screen now",
        )
        val appContext = context.applicationContext
        scope.launch {
            delay(DELAY_MILLIS)
            post(appContext, channelId, id, bypassDnd)
        }
    }

    private fun post(context: Context, channelId: String, id: Int, bypassDnd: Boolean) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val label = if (bypassDnd) "BYPASS-DND probe" else "NORMAL probe"
        val notification = Notification.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_zen)
            .setContentTitle("$label #$id")
            .setContentText("If you heard/saw this while the rule was active, it was not silenced.")
            .setAutoCancel(true)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .build()
        try {
            nm.notify(id, notification)
            EventLog.result(
                TAG,
                "posted #$id on '$channelId'; currentInterruptionFilter=" +
                    ZenFormat.interruptionFilter(nm.currentInterruptionFilter) +
                    "; channel canBypassDnd=${nm.getNotificationChannel(channelId)?.canBypassDnd()}",
            )
        } catch (t: Throwable) {
            EventLog.failure(TAG, t)
        }
    }
}
