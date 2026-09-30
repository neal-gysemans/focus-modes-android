package be.nealgysemans.zenspike

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.service.notification.Condition
import android.service.notification.ZenDeviceEffects
import android.service.notification.ZenPolicy

/** Everything the status header shows, captured in one read. */
data class ZenStatus(
    val dndAccessGranted: Boolean = false,
    val rulesUserManaged: Boolean = false,
    val modesSettingsResolvable: Boolean = false,
    val dndAccessSettingsResolvable: Boolean = false,
    val ruleId: String? = null,
    val ruleFound: Boolean = false,
    val ruleName: String? = null,
    val ruleEnabled: Boolean? = null,
    val ruleStateText: String = "-",
    val ruleActive: Boolean = false,
    val storedEffects: String = "-",
    val currentInterruptionFilter: String = "-",
    val consolidatedPolicy: String = "-",
    val otherRuleCount: Int = 0,
    val readError: String? = null,
)

/**
 * Thin, deliberately un-abstracted wrapper over the AutomaticZenRule API. Each method
 * maps 1:1 to a button in the UI and logs both the call and its result, so a manual
 * test session on a device produces a readable transcript.
 */
class ZenController(context: Context) {

    private val appContext = context.applicationContext
    private val nm: NotificationManager =
        appContext.getSystemService(NotificationManager::class.java)
    private val prefs =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The rule id the OS handed us on add, remembered across process death. */
    var ruleId: String?
        get() = prefs.getString(KEY_RULE_ID, null)
        private set(value) = prefs.edit().apply {
            if (value == null) remove(KEY_RULE_ID) else putString(KEY_RULE_ID, value)
        }.apply()

    // ---------------------------------------------------------------- status

    fun readStatus(): ZenStatus {
        val granted = nm.isNotificationPolicyAccessGranted
        val id = ruleId
        val base = ZenStatus(
            dndAccessGranted = granted,
            modesSettingsResolvable = resolves(Settings.ACTION_AUTOMATIC_ZEN_RULE_SETTINGS),
            dndAccessSettingsResolvable = resolves(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS),
            ruleId = id,
        )
        if (!granted) {
            // Every read below throws SecurityException without policy access.
            return base.copy(readError = "DND access not granted — reads unavailable")
        }
        return try {
            val rule = id?.let { nm.getAutomaticZenRule(it) }
            val state = id?.let { nm.getAutomaticZenRuleState(it) }
            base.copy(
                rulesUserManaged = nm.areAutomaticZenRulesUserManaged(),
                ruleFound = rule != null,
                ruleName = rule?.name,
                ruleEnabled = rule?.isEnabled,
                ruleStateText = state?.let { ZenFormat.conditionState(it) } ?: "-",
                ruleActive = state == Condition.STATE_TRUE,
                storedEffects = rule?.let { ZenFormat.deviceEffects(it.deviceEffects) } ?: "-",
                currentInterruptionFilter = ZenFormat.interruptionFilter(nm.currentInterruptionFilter),
                consolidatedPolicy = ZenFormat.consolidatedPolicy(nm.consolidatedNotificationPolicy),
                otherRuleCount = nm.automaticZenRules.keys.count { it != id },
            )
        } catch (t: Throwable) {
            base.copy(readError = "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun resolves(action: String): Boolean =
        Intent(action).resolveActivity(appContext.packageManager) != null

    // ---------------------------------------------------------------- intents

    fun dndAccessSettingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)

    fun modesSettingsIntent(): Intent =
        Intent(Settings.ACTION_AUTOMATIC_ZEN_RULE_SETTINGS)

    // ---------------------------------------------------------------- rule CRUD

    /**
     * Adds the spike rule, or updates it in place if we already hold an id the OS
     * still recognises. Carries the full payload under test: starred-only people,
     * priority channels, and all three [ZenDeviceEffects].
     */
    fun createOrUpdateRule() {
        val existingId = ruleId
        val rule = buildRule()
        EventLog.action(TAG, if (existingId == null) "addAutomaticZenRule(...)" else "updateAutomaticZenRule($existingId, ...)")
        try {
            if (existingId != null && nm.getAutomaticZenRule(existingId) != null) {
                val ok = nm.updateAutomaticZenRule(existingId, rule)
                EventLog.result(TAG, "updateAutomaticZenRule -> $ok")
                if (!ok) EventLog.error(TAG, "update returned false — rule may have been user-edited")
            } else {
                if (existingId != null) {
                    EventLog.info(TAG, "stored id $existingId no longer exists; adding fresh")
                }
                val newId = nm.addAutomaticZenRule(rule)
                ruleId = newId
                EventLog.result(TAG, "addAutomaticZenRule -> id=$newId")
            }
            readBackRule()
        } catch (t: Throwable) {
            EventLog.failure(TAG, t)
        }
    }

    private fun buildRule(): AutomaticZenRule {
        val policy = ZenPolicy.Builder()
            // The four calls under test.
            .allowCalls(ZenPolicy.PEOPLE_TYPE_STARRED)
            .allowMessages(ZenPolicy.PEOPLE_TYPE_STARRED)
            .allowRepeatCallers(true)
            .allowPriorityChannels(true)
            // Explicit denials so "did activation silence the test notification?"
            // has a deterministic answer instead of inheriting the device default.
            .allowConversations(ZenPolicy.CONVERSATION_SENDERS_NONE)
            .allowReminders(false)
            .allowEvents(false)
            .allowSystem(false)
            .allowAlarms(true)
            .allowMedia(true)
            .build()

        val effects = ZenDeviceEffects.Builder()
            .setShouldDisplayGrayscale(true)
            .setShouldDimWallpaper(true)
            .setShouldUseNightMode(true)
            .build()

        return AutomaticZenRule.Builder(RULE_NAME, CONDITION_ID)
            // Required: without an owner (here: a configuration activity) the OS
            // rejects addAutomaticZenRule outright.
            .setConfigurationActivity(
                ComponentName(appContext, RuleConfigActivity::class.java),
            )
            .setType(AutomaticZenRule.TYPE_OTHER)
            .setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            .setZenPolicy(policy)
            .setDeviceEffects(effects)
            .setManualInvocationAllowed(true)
            .setTriggerDescription("Spike test")
            .setEnabled(true)
            .build()
    }

    fun deleteRule() {
        val id = ruleId
        if (id == null) {
            EventLog.error(TAG, "removeAutomaticZenRule skipped — no stored rule id")
            return
        }
        EventLog.action(TAG, "removeAutomaticZenRule($id)")
        try {
            val ok = nm.removeAutomaticZenRule(id)
            EventLog.result(TAG, "removeAutomaticZenRule -> $ok")
            if (ok) ruleId = null
        } catch (t: Throwable) {
            EventLog.failure(TAG, t)
        }
    }

    // ---------------------------------------------------------------- activation

    /**
     * Toggles our own rule by reporting a [Condition]. [source] is the whole
     * experiment: SOURCE_USER_ACTION is documented to punch through a manual
     * snooze, SOURCE_SCHEDULE is not.
     */
    fun setState(state: Int, source: Int) {
        val id = ruleId
        if (id == null) {
            EventLog.error(TAG, "setAutomaticZenRuleState skipped — no stored rule id")
            return
        }
        val summary = when (state) {
            Condition.STATE_TRUE -> "Spike active"
            else -> "Spike inactive"
        }
        val condition = Condition(CONDITION_ID, summary, state, source)
        EventLog.action(
            TAG,
            "setAutomaticZenRuleState($id, ${ZenFormat.conditionState(state)}, ${ZenFormat.conditionSource(source)})",
        )
        try {
            nm.setAutomaticZenRuleState(id, condition)
            val observed = nm.getAutomaticZenRuleState(id)
            EventLog.result(
                TAG,
                "after call: getAutomaticZenRuleState -> ${ZenFormat.conditionState(observed)}, " +
                    "currentInterruptionFilter=${ZenFormat.interruptionFilter(nm.currentInterruptionFilter)}",
            )
            if (state == Condition.STATE_TRUE && observed != Condition.STATE_TRUE) {
                EventLog.error(
                    TAG,
                    "requested STATE_TRUE but OS reports ${ZenFormat.conditionState(observed)} " +
                        "— expected for SOURCE_SCHEDULE after a manual deactivation (snooze override)",
                )
            }
        } catch (t: Throwable) {
            EventLog.failure(TAG, t)
        }
    }

    fun activateAsUserAction() = setState(Condition.STATE_TRUE, Condition.SOURCE_USER_ACTION)
    fun activateAsSchedule() = setState(Condition.STATE_TRUE, Condition.SOURCE_SCHEDULE)
    fun deactivate() = setState(Condition.STATE_FALSE, Condition.SOURCE_USER_ACTION)

    // ---------------------------------------------------------------- read-back

    /** Dumps the rule exactly as the OS stored it — the drift detector. */
    fun readBackRule() {
        val id = ruleId
        if (id == null) {
            EventLog.error(TAG, "read-back skipped — no stored rule id")
            return
        }
        EventLog.action(TAG, "getAutomaticZenRule($id) + getAutomaticZenRuleState + getConsolidatedNotificationPolicy")
        try {
            val rule = nm.getAutomaticZenRule(id)
            if (rule == null) {
                EventLog.error(TAG, "getAutomaticZenRule -> null (rule gone; deleted by the OS or the user?)")
            } else {
                EventLog.result(TAG, "rule:\n${ZenFormat.rule(rule)}")
            }
            EventLog.result(TAG, "state: ${ZenFormat.conditionState(nm.getAutomaticZenRuleState(id))}")
            EventLog.result(TAG, "consolidated policy:\n${ZenFormat.consolidatedPolicy(nm.consolidatedNotificationPolicy)}")
            EventLog.result(TAG, "currentInterruptionFilter: ${ZenFormat.interruptionFilter(nm.currentInterruptionFilter)}")
            EventLog.result(TAG, "areAutomaticZenRulesUserManaged: ${nm.areAutomaticZenRulesUserManaged()}")
            val others = nm.automaticZenRules.filterKeys { it != id }
            EventLog.result(
                TAG,
                "other rules on device (${others.size}): " +
                    others.entries.joinToString { "${it.value.name}[${if (it.value.isEnabled) "on" else "off"}]" },
            )
        } catch (t: Throwable) {
            EventLog.failure(TAG, t)
        }
    }

    /** Logs the environment once at startup so the transcript is self-describing. */
    fun logEnvironment() {
        EventLog.info(
            TAG,
            "device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} " +
                "sdk=${android.os.Build.VERSION.SDK_INT} release=${android.os.Build.VERSION.RELEASE} " +
                "build=${android.os.Build.DISPLAY}",
        )
        EventLog.info(
            TAG,
            "dndAccessGranted=${nm.isNotificationPolicyAccessGranted} " +
                "modesSettingsResolvable=${resolves(Settings.ACTION_AUTOMATIC_ZEN_RULE_SETTINGS)} " +
                "dndAccessSettingsResolvable=${resolves(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)}",
        )
    }

    companion object {
        private const val TAG = "zen"
        private const val PREFS = "zen-spike"
        private const val KEY_RULE_ID = "rule-id"
        const val RULE_NAME = "Zen Spike test"
        val CONDITION_ID: Uri = Uri.parse("condition://be.nealgysemans.zenspike/spike")
    }
}
