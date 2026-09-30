package be.nealgysemans.tilespike

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.service.notification.Condition
import android.service.notification.ZenPolicy
import android.util.Log

/**
 * Thin wrapper over the real zen-rule APIs.
 *
 * The point of using a *real* `AutomaticZenRule` here rather than a local boolean is
 * that end-to-end latency is only meaningful if the system actually has to do work:
 * write the rule state, recompute the effective zen policy, and tell us about it.
 */
class ZenController(private val context: Context) {

    private val nm: NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    val hasDndAccess: Boolean get() = nm.isNotificationPolicyAccessGranted

    /** Whether the user is allowed to edit/delete our rules from Settings. */
    val rulesUserManaged: Boolean get() = runCatching { nm.areAutomaticZenRulesUserManaged() }.getOrDefault(false)

    fun findExistingRuleId(): String? = runCatching {
        nm.automaticZenRules.entries.firstOrNull { (_, rule) ->
            rule.conditionId == CONDITION_ID
        }?.key
    }.onFailure { Log.w(TAG, "automaticZenRules read failed", it) }.getOrNull()

    /**
     * Creates the rule if it does not exist yet. Returns the rule id, or null with a
     * reason string when the platform refused.
     */
    fun createRule(): Result<String> = runCatching {
        findExistingRuleId()?.let { return@runCatching it }
        val rule = AutomaticZenRule.Builder(RULE_NAME, CONDITION_ID)
            .setConfigurationActivity(ComponentName(context, ZenRuleConfigActivity::class.java))
            .setType(AutomaticZenRule.TYPE_OTHER)
            .setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            .setZenPolicy(
                ZenPolicy.Builder()
                    .disallowAllSounds()
                    .allowAlarms(true)
                    .allowMedia(true)
                    .build(),
            )
            .setIconResId(R.drawable.ic_focus_on)
            .setTriggerDescription("Toggled from the Quick Settings tile")
            .setManualInvocationAllowed(true)
            .setEnabled(true)
            .build()
        nm.addAutomaticZenRule(rule) ?: error("addAutomaticZenRule returned null")
    }

    fun deleteRule(id: String): Result<Boolean> = runCatching { nm.removeAutomaticZenRule(id) }

    /**
     * Hot path. Keep this allocation-light: it runs off the main thread right after the
     * optimistic tile flip, and its duration is the number we care about.
     *
     * SOURCE_USER_ACTION is not cosmetic — the platform treats user-sourced conditions
     * differently from schedule-sourced ones when the user has manually overridden a rule.
     */
    fun setRuleState(id: String, on: Boolean): Result<Unit> = runCatching {
        val state = if (on) Condition.STATE_TRUE else Condition.STATE_FALSE
        nm.setAutomaticZenRuleState(
            id,
            Condition(
                CONDITION_ID,
                if (on) "Focus on" else "Focus off",
                state,
                Condition.SOURCE_USER_ACTION,
            ),
        )
    }

    fun ruleState(id: String): Int = runCatching { nm.getAutomaticZenRuleState(id) }
        .getOrDefault(Condition.STATE_ERROR)

    fun ruleStateName(id: String): String = when (ruleState(id)) {
        Condition.STATE_TRUE -> "TRUE"
        Condition.STATE_FALSE -> "FALSE"
        Condition.STATE_UNKNOWN -> "UNKNOWN"
        Condition.STATE_ERROR -> "ERROR"
        else -> "?"
    }

    val currentInterruptionFilter: String
        get() = when (nm.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_ALL -> "ALL"
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "PRIORITY"
            NotificationManager.INTERRUPTION_FILTER_NONE -> "NONE"
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> "ALARMS"
            else -> "UNKNOWN"
        }

    companion object {
        private const val TAG = "ZenController"
        const val RULE_NAME = "Tile Feel Spike"

        /**
         * Our own scheme/authority. The platform only uses this as an opaque key that
         * ties a Condition back to the rule, so it never has to resolve.
         */
        val CONDITION_ID: Uri = Uri.parse("condition://be.nealgysemans.tilespike/focus")

        fun stateName(state: Int): String = when (state) {
            Condition.STATE_TRUE -> "TRUE"
            Condition.STATE_FALSE -> "FALSE"
            Condition.STATE_UNKNOWN -> "UNKNOWN"
            Condition.STATE_ERROR -> "ERROR"
            else -> "?"
        }
    }
}
