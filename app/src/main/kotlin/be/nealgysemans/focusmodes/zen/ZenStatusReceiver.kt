package be.nealgysemans.focusmodes.zen

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import be.nealgysemans.focusmodes.di.AppGraph

/**
 * Listens for `ACTION_AUTOMATIC_ZEN_RULE_STATUS_CHANGED` — the app's only way to
 * learn that a human changed one of our modes from a system surface.
 *
 * Why it matters: the platform *snoozes* a rule the user turns off. Until the app
 * sends `STATE_FALSE` itself, every later `STATE_TRUE` with `SOURCE_SCHEDULE` is
 * silently ignored. Without this receiver the app would keep believing the mode is
 * on, `healDrift` would keep re-asserting it into the void, and the pin would never
 * clear. Feeding the change through `ModeEngine` as a `USER`/`DEACTIVATE` event
 * fixes all three at once: the pin clears, the state is written, and the engine's
 * own `zen.deactivate` call is the `STATE_FALSE` that lifts the snooze.
 *
 * Registered **twice**, on purpose:
 *  - in the manifest, so a status change can wake a dead process; and
 *  - at runtime from `FocusModesApplication`, because the manifest path could not be
 *    verified on HyperOS from code alone (the broadcast is package-targeted, which
 *    normally reaches manifest receivers, but OEM broadcast policy is its own thing).
 *
 * Double delivery is harmless: every path below is idempotent — a repeated
 * deactivate lands on `IgnoreReason.ALREADY_IN_DESIRED_STATE` and `reconcile` is
 * idempotent by construction.
 */
class ZenStatusReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != NotificationManager.ACTION_AUTOMATIC_ZEN_RULE_STATUS_CHANGED) {
            Log.w(TAG, "ignoring unexpected action ${intent.action}")
            return
        }
        val ruleId = intent.getStringExtra(NotificationManager.EXTRA_AUTOMATIC_ZEN_RULE_ID)
        val status = intent.getIntExtra(
            NotificationManager.EXTRA_AUTOMATIC_ZEN_RULE_STATUS,
            NotificationManager.AUTOMATIC_RULE_STATUS_UNKNOWN,
        )
        Log.d(TAG, "status changed: rule=$ruleId status=${status.statusName}")

        // The work reads Room and DataStore, so it has to leave the main thread;
        // goAsync keeps the process alive until the engine is done with it.
        val pending = goAsync()
        AppGraph.from(context).onZenRuleStatusChanged(ruleId, status) { pending.finish() }
    }

    companion object {
        private const val TAG = "ZenStatusReceiver"

        /**
         * Runtime registration for the lifetime of the process.
         *
         * `RECEIVER_EXPORTED` is required: the sender is the system, not this app,
         * and `RECEIVER_NOT_EXPORTED` would filter the broadcast out.
         */
        fun register(context: Context) {
            val filter = IntentFilter(
                NotificationManager.ACTION_AUTOMATIC_ZEN_RULE_STATUS_CHANGED,
            )
            context.registerReceiver(ZenStatusReceiver(), filter, Context.RECEIVER_EXPORTED)
            Log.d(TAG, "registered at runtime for zen rule status changes")
        }
    }
}

/**
 * What a status value means for the app's state, independent of Android.
 *
 * Extracted so the mapping is unit-testable on the JVM: getting it wrong is how an
 * app ends up fighting its user over whether the phone is quiet.
 */
internal enum class ZenRuleStatus {
    /** The rule is off: snoozed by the user, disabled in Settings, or deleted. */
    OFF,

    /** The rule is on, possibly because a human turned it on outside the app. */
    ON,

    /** No directional information — recompute from the clock instead of guessing. */
    RECONCILE,
}

/** `NotificationManager.AUTOMATIC_RULE_STATUS_*` to [ZenRuleStatus]. */
internal fun zenRuleStatusOf(status: Int): ZenRuleStatus = when (status) {
    NotificationManager.AUTOMATIC_RULE_STATUS_DEACTIVATED,
    NotificationManager.AUTOMATIC_RULE_STATUS_DISABLED,
    NotificationManager.AUTOMATIC_RULE_STATUS_REMOVED,
    -> ZenRuleStatus.OFF

    NotificationManager.AUTOMATIC_RULE_STATUS_ACTIVATED -> ZenRuleStatus.ON

    // ENABLED means "the rule exists again, keep reporting state"; UNKNOWN is what
    // the platform sends instead of ACTIVATED/DEACTIVATED to apps targeting below
    // API 35. Both are answered the same way: recompute and converge.
    else -> ZenRuleStatus.RECONCILE
}

/** Human-readable status, for logs only. */
internal val Int.statusName: String
    get() = when (this) {
        NotificationManager.AUTOMATIC_RULE_STATUS_ENABLED -> "ENABLED"
        NotificationManager.AUTOMATIC_RULE_STATUS_DISABLED -> "DISABLED"
        NotificationManager.AUTOMATIC_RULE_STATUS_REMOVED -> "REMOVED"
        NotificationManager.AUTOMATIC_RULE_STATUS_ACTIVATED -> "ACTIVATED"
        NotificationManager.AUTOMATIC_RULE_STATUS_DEACTIVATED -> "DEACTIVATED"
        else -> "UNKNOWN($this)"
    }
