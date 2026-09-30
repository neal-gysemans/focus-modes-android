package be.nealgysemans.zenspike

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

/**
 * Listens for the zen broadcasts and funnels them into [EventLog]. Registered while
 * the screen is visible; the interesting signal is whether HyperOS sends
 * ACTION_AUTOMATIC_ZEN_RULE_STATUS_CHANGED at all, and with which status.
 */
class ZenBroadcastMonitor(private val controller: ZenController) : BroadcastReceiver() {

    fun register(context: Context) {
        val filter = IntentFilter().apply {
            addAction(NotificationManager.ACTION_AUTOMATIC_ZEN_RULE_STATUS_CHANGED)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            addAction(NotificationManager.ACTION_NOTIFICATION_POLICY_CHANGED)
            addAction(NotificationManager.ACTION_CONSOLIDATED_NOTIFICATION_POLICY_CHANGED)
            addAction(NotificationManager.ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED)
        }
        // These are all protected system broadcasts, but targetSdk 34+ still demands
        // an explicit export flag; EXPORTED is the one guaranteed to receive them.
        context.registerReceiver(this, filter, Context.RECEIVER_EXPORTED)
        EventLog.info(TAG, "registered for ${filter.countActions()} zen broadcasts")
    }

    fun unregister(context: Context) {
        runCatching { context.unregisterReceiver(this) }
        EventLog.info(TAG, "unregistered")
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (val action = intent.action) {
            NotificationManager.ACTION_AUTOMATIC_ZEN_RULE_STATUS_CHANGED -> {
                val id = intent.getStringExtra(NotificationManager.EXTRA_AUTOMATIC_ZEN_RULE_ID)
                val status = intent.getIntExtra(
                    NotificationManager.EXTRA_AUTOMATIC_ZEN_RULE_STATUS,
                    NotificationManager.AUTOMATIC_RULE_STATUS_UNKNOWN,
                )
                val mine = if (id == controller.ruleId) "ours" else "other/unknown"
                EventLog.broadcast(
                    TAG,
                    "AUTOMATIC_ZEN_RULE_STATUS_CHANGED status=${ZenFormat.ruleStatus(status)} " +
                        "ruleId=$id ($mine)",
                )
            }

            NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED -> {
                val nm = context.getSystemService(NotificationManager::class.java)
                EventLog.broadcast(
                    TAG,
                    "INTERRUPTION_FILTER_CHANGED -> ${ZenFormat.interruptionFilter(nm.currentInterruptionFilter)}",
                )
            }

            NotificationManager.ACTION_NOTIFICATION_POLICY_CHANGED,
            NotificationManager.ACTION_CONSOLIDATED_NOTIFICATION_POLICY_CHANGED,
            -> {
                val nm = context.getSystemService(NotificationManager::class.java)
                val policy = runCatching { nm.consolidatedNotificationPolicy }.getOrNull()
                EventLog.broadcast(
                    TAG,
                    "${action.substringAfterLast('.')} -> consolidated:\n" +
                        ZenFormat.consolidatedPolicy(policy),
                )
            }

            NotificationManager.ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED -> {
                val nm = context.getSystemService(NotificationManager::class.java)
                EventLog.broadcast(
                    TAG,
                    "POLICY_ACCESS_GRANTED_CHANGED -> granted=${nm.isNotificationPolicyAccessGranted}",
                )
            }

            else -> EventLog.broadcast(TAG, "unexpected action $action")
        }
    }

    private companion object {
        const val TAG = "bcast"
    }
}
