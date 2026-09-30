package be.nealgysemans.focusmodes.zen

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.service.notification.Condition
import android.service.notification.ZenDeviceEffects
import android.service.notification.ZenPolicy
import android.util.Log
import be.nealgysemans.focusmodes.R
import be.nealgysemans.focusmodes.engine.ActivationSource
import be.nealgysemans.focusmodes.engine.FocusMode
import be.nealgysemans.focusmodes.engine.ModeCatalog
import be.nealgysemans.focusmodes.engine.PeopleFilter

/**
 * The real [ZenAdapter], wrapping `NotificationManager`'s `AutomaticZenRule` API.
 *
 * Everything here needs DND access (`isNotificationPolicyAccessGranted`); see
 * `health/PermissionHealth`. Without it every call below throws or silently
 * no-ops, so the UI must gate on that grant before the engine ever runs.
 *
 * Skeleton status: the API surface, the id/condition plumbing and the domain →
 * `ZenPolicy`/`ZenDeviceEffects` mapping are real; the write paths are marked
 * TODO because they need spike #1's findings on whether HyperOS honours
 * third-party rules at all.
 */
class SystemZenAdapter(
    private val context: Context,
    private val catalog: ModeCatalog,
    /**
     * Called when the system hands back a rule id, so it can be cached on the
     * mode row. The system id is the only handle we have; losing it means
     * creating a duplicate rule on the next reconcile.
     */
    private val onRuleIdResolved: (modeId: String, ruleId: String) -> Unit = { _, _ -> },
) : ZenAdapter {

    private val notificationManager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    override fun ensureRule(mode: FocusMode): String? {
        val rule = buildRule(mode)
        val existingId = mode.zenRuleId

        // TODO(spike-1): create or update for real once we know HyperOS accepts
        //  third-party rules. Shape:
        //    if (existingId == null) {
        //        val id = notificationManager.addAutomaticZenRule(rule)
        //        onRuleIdResolved(mode.id, id)
        //        return id
        //    }
        //    if (notificationManager.getAutomaticZenRule(existingId) == null) {
        //        // user deleted it in Settings -> recreate and re-cache the id
        //        val id = notificationManager.addAutomaticZenRule(rule)
        //        onRuleIdResolved(mode.id, id)
        //        return id
        //    }
        //    notificationManager.updateAutomaticZenRule(existingId, rule)
        //  Caveat from the feasibility study: once a user edits a rule in
        //  Settings the platform may reject later app updates to it, so a failed
        //  update must not be treated as an error.
        Log.d(TAG, "ensureRule(${mode.id}) -> stub; rule=${rule.name} existingId=$existingId")
        return existingId
    }

    override fun activate(modeId: String, source: ActivationSource) {
        val ruleId = ruleIdFor(modeId) ?: return
        val condition = conditionFor(modeId, Condition.STATE_TRUE, source)
        // TODO(spike-1): notificationManager.setAutomaticZenRuleState(ruleId, condition)
        Log.d(TAG, "activate($modeId) -> stub; ruleId=$ruleId condition=$condition")
    }

    override fun deactivate(modeId: String, source: ActivationSource) {
        val ruleId = ruleIdFor(modeId) ?: return
        val condition = conditionFor(modeId, Condition.STATE_FALSE, source)
        // TODO(spike-1): notificationManager.setAutomaticZenRuleState(ruleId, condition)
        Log.d(TAG, "deactivate($modeId) -> stub; ruleId=$ruleId condition=$condition")
    }

    override fun readBack(modeId: String): ZenRuleSnapshot? {
        val ruleId = ruleIdFor(modeId) ?: return null
        // TODO(spike-1): guard on isNotificationPolicyAccessGranted and wrap in
        //  runCatching — OEM builds have been seen to throw here.
        //    val rule = notificationManager.getAutomaticZenRule(ruleId)
        //    val state = notificationManager.getAutomaticZenRuleState(ruleId)
        //    return ZenRuleSnapshot(
        //        ruleId = ruleId,
        //        exists = rule != null,
        //        enabled = rule?.isEnabled == true,
        //        active = state == Condition.STATE_TRUE,
        //    )
        return ZenRuleSnapshot(ruleId = ruleId, exists = false, enabled = false, active = false)
    }

    /**
     * Translate a [FocusMode] into the platform rule.
     *
     * The interesting part is that the allowed-people filter is expressed as
     * [ZenPolicy], i.e. enforced by the system, and the visual effects as
     * [ZenDeviceEffects]. Nothing here relays or suppresses notifications in
     * app code, which is both the correct design and what keeps the app clear of
     * Play's notification-listener policy.
     */
    private fun buildRule(mode: FocusMode): AutomaticZenRule {
        val policy = ZenPolicy.Builder()
            .allowCalls(mode.callsFrom.toZenPeopleType())
            .allowMessages(mode.messagesFrom.toZenPeopleType())
            .allowRepeatCallers(mode.repeatCallers)
            .allowAlarms(true)
            .allowMedia(true)
            .build()

        val effects = ZenDeviceEffects.Builder()
            .setShouldDisplayGrayscale(mode.effects.grayscale)
            .setShouldDimWallpaper(mode.effects.dimWallpaper)
            .setShouldUseNightMode(mode.effects.nightMode)
            .build()

        return AutomaticZenRule.Builder(mode.name, conditionIdFor(mode.id))
            .setType(AutomaticZenRule.TYPE_IMMERSIVE)
            .setZenPolicy(policy)
            .setDeviceEffects(effects)
            .setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            .setManualInvocationAllowed(true)
            // TODO(skeleton): derive per-mode copy from its schedule
            //  ("Weekdays 9:00-17:00") instead of one static string.
            .setTriggerDescription(context.getString(R.string.zen_trigger_description))
            .setEnabled(true)
            .build()
    }

    /**
     * The stable condition id for a mode.
     *
     * `Condition.newId` scopes the URI to this package, so the id cannot collide
     * with another app's rule. It must stay stable across app updates, hence
     * deriving it from the Room row id rather than anything positional.
     */
    private fun conditionIdFor(modeId: String): Uri =
        Condition.newId(context)
            .appendPath(CONDITION_PATH)
            .appendPath(modeId)
            .build()

    private fun conditionFor(modeId: String, state: Int, source: ActivationSource): Condition =
        Condition(
            conditionIdFor(modeId),
            catalog.find(modeId)?.name.orEmpty(),
            state,
            source.toConditionSource(),
        )

    private fun ruleIdFor(modeId: String): String? =
        catalog.find(modeId)?.zenRuleId.also {
            if (it == null) Log.w(TAG, "no cached rule id for mode $modeId; ensureRule first")
        }

    private companion object {
        const val TAG = "SystemZenAdapter"
        const val CONDITION_PATH = "mode"
    }
}

/**
 * Domain filter to `ZenPolicy.PEOPLE_TYPE_*`.
 *
 * Kept next to the adapter so the engine and Room layers never import
 * `android.service.notification`.
 */
internal fun PeopleFilter.toZenPeopleType(): Int = when (this) {
    PeopleFilter.STARRED -> ZenPolicy.PEOPLE_TYPE_STARRED
    PeopleFilter.CONTACTS -> ZenPolicy.PEOPLE_TYPE_CONTACTS
    PeopleFilter.ANYONE -> ZenPolicy.PEOPLE_TYPE_ANYONE
    PeopleFilter.NONE -> ZenPolicy.PEOPLE_TYPE_NONE
}

/**
 * Domain source to `Condition.SOURCE_*`.
 *
 * This is the whole reason [ActivationSource] is threaded through every adapter
 * call: the system's Modes UI reports *why* the phone is quiet from this field.
 */
internal fun ActivationSource.toConditionSource(): Int = when (this) {
    ActivationSource.USER -> Condition.SOURCE_USER_ACTION
    ActivationSource.SCHEDULE -> Condition.SOURCE_SCHEDULE
    ActivationSource.CONTEXT -> Condition.SOURCE_CONTEXT
}
