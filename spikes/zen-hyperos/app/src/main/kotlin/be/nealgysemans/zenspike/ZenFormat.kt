package be.nealgysemans.zenspike

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.service.notification.Condition
import android.service.notification.ZenDeviceEffects
import android.service.notification.ZenPolicy

/**
 * Human-readable renderings of the zen constants. The whole point of the spike is
 * reading back what the OS actually stored, so every int gets a name.
 */
object ZenFormat {

    fun conditionState(state: Int): String = when (state) {
        Condition.STATE_TRUE -> "STATE_TRUE (active)"
        Condition.STATE_FALSE -> "STATE_FALSE (inactive)"
        Condition.STATE_ERROR -> "STATE_ERROR"
        Condition.STATE_UNKNOWN -> "STATE_UNKNOWN"
        else -> "state=$state (?)"
    }

    fun conditionSource(source: Int): String = when (source) {
        Condition.SOURCE_USER_ACTION -> "SOURCE_USER_ACTION"
        Condition.SOURCE_SCHEDULE -> "SOURCE_SCHEDULE"
        Condition.SOURCE_CONTEXT -> "SOURCE_CONTEXT"
        Condition.SOURCE_UNKNOWN -> "SOURCE_UNKNOWN"
        else -> "source=$source (?)"
    }

    fun ruleStatus(status: Int): String = when (status) {
        NotificationManager.AUTOMATIC_RULE_STATUS_ENABLED -> "ENABLED"
        NotificationManager.AUTOMATIC_RULE_STATUS_DISABLED -> "DISABLED"
        NotificationManager.AUTOMATIC_RULE_STATUS_ACTIVATED -> "ACTIVATED"
        NotificationManager.AUTOMATIC_RULE_STATUS_DEACTIVATED -> "DEACTIVATED"
        NotificationManager.AUTOMATIC_RULE_STATUS_REMOVED -> "REMOVED"
        NotificationManager.AUTOMATIC_RULE_STATUS_UNKNOWN -> "UNKNOWN"
        else -> "status=$status (?)"
    }

    fun interruptionFilter(filter: Int): String = when (filter) {
        NotificationManager.INTERRUPTION_FILTER_ALL -> "ALL (dnd off)"
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "PRIORITY"
        NotificationManager.INTERRUPTION_FILTER_ALARMS -> "ALARMS"
        NotificationManager.INTERRUPTION_FILTER_NONE -> "NONE (total silence)"
        NotificationManager.INTERRUPTION_FILTER_UNKNOWN -> "UNKNOWN"
        else -> "filter=$filter (?)"
    }

    fun ruleType(type: Int): String = when (type) {
        AutomaticZenRule.TYPE_UNKNOWN -> "UNKNOWN"
        AutomaticZenRule.TYPE_OTHER -> "OTHER"
        AutomaticZenRule.TYPE_SCHEDULE_TIME -> "SCHEDULE_TIME"
        AutomaticZenRule.TYPE_SCHEDULE_CALENDAR -> "SCHEDULE_CALENDAR"
        AutomaticZenRule.TYPE_BEDTIME -> "BEDTIME"
        AutomaticZenRule.TYPE_DRIVING -> "DRIVING"
        AutomaticZenRule.TYPE_IMMERSIVE -> "IMMERSIVE"
        AutomaticZenRule.TYPE_THEATER -> "THEATER"
        AutomaticZenRule.TYPE_MANAGED -> "MANAGED"
        else -> "type=$type (?)"
    }

    private fun peopleType(value: Int): String = when (value) {
        ZenPolicy.PEOPLE_TYPE_ANYONE -> "ANYONE"
        ZenPolicy.PEOPLE_TYPE_CONTACTS -> "CONTACTS"
        ZenPolicy.PEOPLE_TYPE_STARRED -> "STARRED"
        ZenPolicy.PEOPLE_TYPE_NONE -> "NONE"
        ZenPolicy.PEOPLE_TYPE_UNSET -> "UNSET"
        else -> "people=$value (?)"
    }

    private fun conversationSenders(value: Int): String = when (value) {
        ZenPolicy.CONVERSATION_SENDERS_ANYONE -> "ANYONE"
        ZenPolicy.CONVERSATION_SENDERS_IMPORTANT -> "IMPORTANT"
        ZenPolicy.CONVERSATION_SENDERS_NONE -> "NONE"
        ZenPolicy.CONVERSATION_SENDERS_UNSET -> "UNSET"
        else -> "conv=$value (?)"
    }

    private fun tri(value: Int): String = when (value) {
        ZenPolicy.STATE_ALLOW -> "allow"
        ZenPolicy.STATE_DISALLOW -> "disallow"
        ZenPolicy.STATE_UNSET -> "unset"
        else -> "state=$value (?)"
    }

    /** What the OS stored for our rule's [ZenPolicy] — every field, so drift is visible. */
    fun zenPolicy(policy: ZenPolicy?): String {
        if (policy == null) return "(null — OS dropped the policy)"
        return buildString {
            appendLine("calls=${tri(policy.priorityCategoryCalls)}/${peopleType(policy.priorityCallSenders)}")
            appendLine("messages=${tri(policy.priorityCategoryMessages)}/${peopleType(policy.priorityMessageSenders)}")
            appendLine("conversations=${tri(policy.priorityCategoryConversations)}/${conversationSenders(policy.priorityConversationSenders)}")
            appendLine("repeatCallers=${tri(policy.priorityCategoryRepeatCallers)}")
            appendLine("priorityChannels=${tri(policy.priorityChannelsAllowed)}")
            appendLine("reminders=${tri(policy.priorityCategoryReminders)} events=${tri(policy.priorityCategoryEvents)}")
            appendLine("alarms=${tri(policy.priorityCategoryAlarms)} media=${tri(policy.priorityCategoryMedia)} system=${tri(policy.priorityCategorySystem)}")
            append("visual: peek=${tri(policy.visualEffectPeek)} statusBar=${tri(policy.visualEffectStatusBar)} badge=${tri(policy.visualEffectBadge)} ambient=${tri(policy.visualEffectAmbient)} list=${tri(policy.visualEffectNotificationList)} lights=${tri(policy.visualEffectLights)} fsi=${tri(policy.visualEffectFullScreenIntent)}")
        }
    }

    /** The three effects the spike cares about, plus the fourth for completeness. */
    fun deviceEffects(effects: ZenDeviceEffects?): String {
        if (effects == null) return "(null — OS dropped the device effects)"
        return "grayscale=${effects.shouldDisplayGrayscale()} " +
            "dimWallpaper=${effects.shouldDimWallpaper()} " +
            "nightMode=${effects.shouldUseNightMode()} " +
            "suppressAmbient=${effects.shouldSuppressAmbientDisplay()}"
    }

    /** The effective policy across all active rules — what actually gates notifications. */
    fun consolidatedPolicy(policy: NotificationManager.Policy?): String {
        if (policy == null) return "(null)"
        val categories = NotificationManager.Policy
            .priorityCategoriesToString(policy.priorityCategories)
            .ifBlank { "(none)" }
        val suppressed = NotificationManager.Policy
            .suppressedEffectsToString(policy.suppressedVisualEffects)
            .ifBlank { "(none)" }
        return buildString {
            appendLine("allowed: $categories")
            appendLine(
                "callSenders=${NotificationManager.Policy.prioritySendersToString(policy.priorityCallSenders)} " +
                    "msgSenders=${NotificationManager.Policy.prioritySendersToString(policy.priorityMessageSenders)} " +
                    "convSenders=${conversationSenders(policy.priorityConversationSenders)}",
            )
            append("suppressedVisual: $suppressed")
        }
    }

    /** Compact one-liner of a rule, for the event log. */
    fun rule(rule: AutomaticZenRule): String = buildString {
        appendLine("name='${rule.name}' enabled=${rule.isEnabled} type=${ruleType(rule.type)}")
        appendLine("interruptionFilter=${interruptionFilter(rule.interruptionFilter)}")
        appendLine("conditionId=${rule.conditionId}")
        appendLine("owner=${rule.owner} configActivity=${rule.configurationActivity}")
        appendLine("manualInvocationAllowed=${rule.isManualInvocationAllowed}")
        appendLine("triggerDescription='${rule.triggerDescription}'")
        appendLine("effects: ${deviceEffects(rule.deviceEffects)}")
        append("policy: ${zenPolicy(rule.zenPolicy)}")
    }
}
