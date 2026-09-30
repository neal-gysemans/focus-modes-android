package be.nealgysemans.tilespike

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Listens for [NotificationManager.ACTION_AUTOMATIC_ZEN_RULE_STATUS_CHANGED] — the
 * system's out-of-band "your rule changed" signal.
 *
 * Uses a CONFLATED channel rather than a SharedFlow on purpose: [drain] then
 * [awaitNext] is race-free, whereas starting a `flow.first()` collector just before
 * calling `setAutomaticZenRuleState` can lose the broadcast if the system is fast.
 */
class ZenBroadcastWatcher(
    private val context: Context,
    private val onEvent: (String) -> Unit,
) {
    private val arrivals = Channel<Long>(capacity = Channel.CONFLATED)
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val nanos = SystemClock.elapsedRealtimeNanos()
            arrivals.trySend(nanos)
            val id = intent?.getStringExtra(NotificationManager.EXTRA_AUTOMATIC_ZEN_RULE_ID)
            val status = intent?.getIntExtra(
                NotificationManager.EXTRA_AUTOMATIC_ZEN_RULE_STATUS,
                NotificationManager.AUTOMATIC_RULE_STATUS_UNKNOWN,
            ) ?: NotificationManager.AUTOMATIC_RULE_STATUS_UNKNOWN
            onEvent("zen-status broadcast: rule=${id?.take(12)} status=${statusName(status)}")
        }
    }

    fun register() {
        if (registered) return
        // RECEIVER_NOT_EXPORTED is required on API 34+; system broadcasts still reach us.
        context.registerReceiver(
            receiver,
            IntentFilter(NotificationManager.ACTION_AUTOMATIC_ZEN_RULE_STATUS_CHANGED),
            Context.RECEIVER_NOT_EXPORTED,
        )
        registered = true
    }

    fun unregister() {
        if (!registered) return
        runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }

    /** Throw away anything buffered from an earlier toggle. Call right before toggling. */
    fun drain() {
        while (arrivals.tryReceive().isSuccess) { /* discard */ }
    }

    /** Returns the arrival timestamp in elapsed-realtime nanos, or null on timeout. */
    suspend fun awaitNext(timeoutMs: Long): Long? =
        withTimeoutOrNull(timeoutMs) { arrivals.receive() }

    companion object {
        fun statusName(status: Int): String = when (status) {
            NotificationManager.AUTOMATIC_RULE_STATUS_ACTIVATED -> "ACTIVATED"
            NotificationManager.AUTOMATIC_RULE_STATUS_DEACTIVATED -> "DEACTIVATED"
            NotificationManager.AUTOMATIC_RULE_STATUS_ENABLED -> "ENABLED"
            NotificationManager.AUTOMATIC_RULE_STATUS_DISABLED -> "DISABLED"
            NotificationManager.AUTOMATIC_RULE_STATUS_REMOVED -> "REMOVED"
            else -> "UNKNOWN"
        }
    }
}
