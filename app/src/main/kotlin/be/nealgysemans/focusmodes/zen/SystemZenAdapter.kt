package be.nealgysemans.focusmodes.zen

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.service.notification.Condition
import android.service.notification.ZenDeviceEffects
import android.service.notification.ZenPolicy
import android.util.Log
import be.nealgysemans.focusmodes.R
import be.nealgysemans.focusmodes.engine.ActivationSource
import be.nealgysemans.focusmodes.engine.FocusMode
import be.nealgysemans.focusmodes.engine.ModeCatalog
import be.nealgysemans.focusmodes.engine.ModeEffects
import be.nealgysemans.focusmodes.engine.PeopleFilter
import java.util.concurrent.ConcurrentHashMap

/**
 * The real [ZenAdapter], wrapping `NotificationManager`'s `AutomaticZenRule` API.
 *
 * Everything here needs DND access (`isNotificationPolicyAccessGranted`); see
 * `health/PermissionHealth`. Without it every call throws `SecurityException`, so
 * every entry point runs inside [guarded], which degrades to a logged no-op. A focus
 * app that crashes a boot receiver because a grant was revoked overnight is worse
 * than one that stays quiet until the user fixes the grant.
 *
 * Verified on the Xiaomi 17T Pro (HyperOS 3.0 / Android 16), spike #1:
 *  - `addAutomaticZenRule` works and the rule is stored field-for-field intact.
 *  - `getAutomaticZenRuleState` is reliable; activation lands in ~15 ms.
 *  - Snooze semantics are stock: after the user turns a rule off from a system
 *    surface, `STATE_TRUE` with `SOURCE_SCHEDULE` is *silently refused* while
 *    `SOURCE_USER_ACTION` punches through. Hence [setState] always reads back.
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

    // `by lazy`, not `get()`: every method here touches this, [guarded] reads
    // `isNotificationPolicyAccessGranted` off it on every single call, and reconcile calls
    // through several times per mode. The manager is a process-lifetime object.
    private val notificationManager: NotificationManager by lazy {
        context.getSystemService(NotificationManager::class.java)
    }

    /**
     * The rule definition this process last successfully pushed, per mode.
     *
     * [ensureRule] is called for every mode on every reconcile — boot, every schedule
     * boundary, every shade open, every foreground — and almost every one of those passes
     * had nothing to do: build a rule, fetch the stored one over a binder, compare them
     * field by field, conclude they match. On a three-mode device that is three rule builds
     * and three binder reads per wake to learn nothing.
     *
     * So a pass is skipped outright when this process has already pushed exactly this
     * definition *and* still holds a rule id for it. Both halves are needed: a cleared rule
     * id (the `REMOVED` path) makes the skip impossible on its own, which is why clearing the
     * id is all that path has to do.
     *
     * The honest cost: a rule the *user* edits in Settings is no longer noticed on the next
     * reconcile of the same process. That is a smaller loss than it sounds — the platform
     * refuses app updates to a user-edited rule anyway, so the old behaviour was to detect
     * the drift, try, be refused and log it once a wake — and the entry is dropped whenever
     * an update *is* refused, so the one case where re-checking could achieve something keeps
     * re-checking. A mode edit in the app changes the fingerprint, and a fresh process starts
     * with none.
     */
    private val pushedRules = ConcurrentHashMap<String, RuleFingerprint>()

    /**
     * Rule ids this process drove itself, with the `elapsedRealtime` of the write.
     *
     * The status broadcast arrives ~50 ms after every change, including our own.
     * Without this, each activation we perform would bounce back as an "external"
     * activation and cost a needless reconcile. Read by `ZenStatusReceiver` via
     * [wasSelfInitiated]; an empty map (fresh process) just means "unknown", and
     * the receiver then falls back to reconciling, which is always safe.
     */
    private val selfInitiated = ConcurrentHashMap<String, Long>()

    override fun ensureRule(mode: FocusMode): String? {
        val cachedId = mode.zenRuleId
        val fingerprint = mode.ruleFingerprint()
        // Nothing to do, and nothing to ask the system: this process already pushed exactly
        // this definition and still has the id the system gave it back. See [pushedRules].
        if (cachedId != null && pushedRules[mode.id] == fingerprint) return cachedId

        return guarded("ensureRule(${mode.id})", fallback = cachedId) {
            val desired = buildRule(mode)
            val existingId = cachedId ?: return@guarded addRule(mode, desired, fingerprint)

            val stored = notificationManager.getAutomaticZenRule(existingId)
            if (stored == null) {
                // The user deleted the rule in Settings (or an OEM cleanup did).
                // Re-add and re-cache: Room is the source of truth, not the system.
                Log.i(TAG, "rule $existingId for ${mode.id} is gone; adding a fresh one")
                return@guarded addRule(mode, desired, fingerprint)
            }

            if (stored.matches(desired)) {
                pushedRules[mode.id] = fingerprint
                return@guarded existingId
            }

            if (notificationManager.updateAutomaticZenRule(existingId, desired)) {
                pushedRules[mode.id] = fingerprint
            } else {
                // Once a user edits a rule in Settings the platform may refuse
                // further app updates to it. That is not an error and must not be
                // retried in a loop: log what the system actually holds, keep the
                // id, and let the UI show a "managed in system settings" badge.
                //
                // Deliberately *not* remembered as pushed — what the system holds is not
                // what we asked for, so the next reconcile has to look again rather than
                // assume this definition landed.
                pushedRules.remove(mode.id)
                Log.w(
                    TAG,
                    "updateAutomaticZenRule($existingId) refused for ${mode.id} — rule looks " +
                        "user-managed; system holds " + describe(notificationManager.getAutomaticZenRule(existingId)),
                )
            }
            existingId
        }
    }

    override fun activate(modeId: String, source: ActivationSource): Boolean =
        setState(modeId, Condition.STATE_TRUE, source)

    override fun deactivate(modeId: String, source: ActivationSource): Boolean =
        setState(modeId, Condition.STATE_FALSE, source)

    override fun readBack(modeId: String): Boolean? {
        val ruleId = ruleIdFor(modeId) ?: return null
        // One binder call, for the one thing the engine asks. Fetching the rule object
        // alongside the state was a second call per mode per reconcile for two fields
        // (`exists`, `enabled`) nothing ever read.
        return guarded("readBack($modeId)", fallback = null) {
            notificationManager.getAutomaticZenRuleState(ruleId) == Condition.STATE_TRUE
        }
    }

    /**
     * True when this process drove [ruleId] within the last few seconds, i.e. the
     * status broadcast we are looking at is our own echo rather than a user acting
     * on a system surface.
     *
     * Deliberately not consuming: one write can produce more than one broadcast,
     * and the worst case of a false positive is one skipped (idempotent) reconcile.
     */
    fun wasSelfInitiated(ruleId: String): Boolean {
        val writtenAt = selfInitiated[ruleId] ?: return false
        if (SystemClock.elapsedRealtime() - writtenAt > SELF_ECHO_WINDOW_MS) {
            selfInitiated.remove(ruleId)
            return false
        }
        return true
    }

    private fun addRule(
        mode: FocusMode,
        rule: AutomaticZenRule,
        fingerprint: RuleFingerprint,
    ): String? {
        val id = notificationManager.addAutomaticZenRule(rule)
        Log.i(TAG, "addAutomaticZenRule -> $id for mode ${mode.id}")
        onRuleIdResolved(mode.id, id)
        pushedRules[mode.id] = fingerprint
        return id
    }

    /**
     * Report a [Condition] for [modeId]'s rule and return what the system says
     * afterwards.
     *
     * The read-back is not paranoia: a `SOURCE_SCHEDULE` activation is a no-op
     * while the rule is snoozed, and the call does not fail — it just does nothing.
     */
    private fun setState(modeId: String, state: Int, source: ActivationSource): Boolean {
        val ruleId = ruleIdFor(modeId) ?: return false
        return guarded("setState($modeId -> ${state.stateName}, $source)", fallback = false) {
            // Marked *before* the call: the status broadcast can land on another
            // thread within ~50 ms and must find the marker already there.
            selfInitiated[ruleId] = SystemClock.elapsedRealtime()
            notificationManager.setAutomaticZenRuleState(
                ruleId,
                conditionFor(modeId, state, source),
            )
            val observed = notificationManager.getAutomaticZenRuleState(ruleId)
            val agreed = observed == state
            if (!agreed) {
                Log.w(
                    TAG,
                    "asked for ${state.stateName} on $modeId via $source but the system reports " +
                        "${observed.stateName} — expected when the user snoozed the rule and the " +
                        "source is not SOURCE_USER_ACTION",
                )
            } else {
                Log.d(TAG, "$modeId -> ${state.stateName} confirmed (source=$source)")
            }
            agreed
        }
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
            // Channels the user marked as priority keep breaking through; that is
            // the user's own escape hatch and the app must not take it away.
            .allowPriorityChannels(true)
            .allowAlarms(true)
            .allowMedia(true)
            .build()

        val effects = ZenDeviceEffects.Builder()
            .setShouldDisplayGrayscale(mode.effects.grayscale)
            .setShouldDimWallpaper(mode.effects.dimWallpaper)
            .setShouldUseNightMode(mode.effects.nightMode)
            .build()

        return AutomaticZenRule.Builder(mode.name, conditionIdFor(mode.id))
            // Required: addAutomaticZenRule rejects a rule with no owner. The modern
            // owner is a configuration activity handling ACTION_AUTOMATIC_ZEN_RULE
            // (spike #1 confirmed the system routes rule configuration back to it).
            .setConfigurationActivity(
                ComponentName(context, ZenRuleConfigActivity::class.java),
            )
            .setType(AutomaticZenRule.TYPE_IMMERSIVE)
            .setZenPolicy(policy)
            .setDeviceEffects(effects)
            .setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            .setManualInvocationAllowed(true)
            // TODO(core-live): derive per-mode copy from its schedule
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

    /**
     * Run a platform call, or degrade to [fallback] with a log.
     *
     * `SecurityException` is the documented failure when DND access is missing, and
     * it can appear *between* a granted check and the call (the user can revoke the
     * grant from the shade). The broader `RuntimeException` arm exists because OEM
     * notification stacks have been observed to throw from these reads; a receiver
     * crash-looping on every zen broadcast would be far worse than a stale state.
     */
    private inline fun <T> guarded(operation: String, fallback: T, block: () -> T): T {
        if (!notificationManager.isNotificationPolicyAccessGranted) {
            Log.w(TAG, "$operation skipped: no DND access")
            return fallback
        }
        return try {
            block()
        } catch (e: SecurityException) {
            Log.w(TAG, "$operation refused: DND access lost (${e.message})")
            fallback
        } catch (e: RuntimeException) {
            Log.e(TAG, "$operation failed on this platform build", e)
            fallback
        }
    }

    /**
     * A [FocusMode] reduced to what actually ends up inside its `AutomaticZenRule`.
     *
     * Exactly the varying inputs to the fields [matches] compares, and deliberately **not**
     * the whole `FocusMode`: `iconKey` and `color` are the app's own presentation and mean
     * nothing to the system, so recolouring a mode must not look like a change the platform
     * needs telling about. `zenRuleId` is out for the opposite reason — it is the system's
     * answer, not part of the question.
     *
     * A value to compare rather than a hash, because a hash collision here would silently
     * skip pushing a real change.
     *
     * If [matches] ever grows a field, this has to grow with it. The two are adjacent for
     * that reason, and the direction of failure is the mild one: a fingerprint that is too
     * coarse skips a needed push, which the next process start repairs.
     */
    private data class RuleFingerprint(
        val id: String,
        val name: String,
        val callsFrom: PeopleFilter,
        val messagesFrom: PeopleFilter,
        val repeatCallers: Boolean,
        val effects: ModeEffects,
    )

    private fun FocusMode.ruleFingerprint() = RuleFingerprint(
        id = id,
        name = name,
        callsFrom = callsFrom,
        messagesFrom = messagesFrom,
        repeatCallers = repeatCallers,
        effects = effects,
    )

    /**
     * Whether the stored rule already says what we want it to say.
     *
     * `AutomaticZenRule.equals` is useless here: it compares `creationTime` and the
     * owning package, both filled in by the system, so a freshly built rule never
     * equals a stored one. Comparing only the fields the app owns keeps reconcile
     * from writing to the system config on every single wake.
     */
    private fun AutomaticZenRule.matches(desired: AutomaticZenRule): Boolean =
        name == desired.name &&
            isEnabled == desired.isEnabled &&
            interruptionFilter == desired.interruptionFilter &&
            type == desired.type &&
            conditionId == desired.conditionId &&
            configurationActivity == desired.configurationActivity &&
            isManualInvocationAllowed == desired.isManualInvocationAllowed &&
            triggerDescription == desired.triggerDescription &&
            deviceEffects == desired.deviceEffects &&
            zenPolicy.matchesPeoplePolicy(desired.zenPolicy)

    /**
     * Compare only the policy fields this app sets.
     *
     * The system fills the rest in from its own defaults, so a whole-object
     * comparison would report drift forever and re-write the rule on every wake.
     */
    private fun ZenPolicy?.matchesPeoplePolicy(desired: ZenPolicy?): Boolean {
        if (this == null || desired == null) return this == desired
        return priorityCallSenders == desired.priorityCallSenders &&
            priorityMessageSenders == desired.priorityMessageSenders &&
            priorityCategoryRepeatCallers == desired.priorityCategoryRepeatCallers &&
            priorityChannelsAllowed == desired.priorityChannelsAllowed
    }

    private fun describe(rule: AutomaticZenRule?): String = when (rule) {
        null -> "nothing (rule gone)"
        else -> "name=${rule.name} enabled=${rule.isEnabled} filter=${rule.interruptionFilter} " +
            "effects=[grayscale=${rule.deviceEffects?.shouldDisplayGrayscale()} " +
            "dim=${rule.deviceEffects?.shouldDimWallpaper()} " +
            "night=${rule.deviceEffects?.shouldUseNightMode()}]"
    }

    private val Int.stateName: String
        get() = when (this) {
            Condition.STATE_TRUE -> "STATE_TRUE"
            Condition.STATE_FALSE -> "STATE_FALSE"
            Condition.STATE_ERROR -> "STATE_ERROR"
            else -> "STATE_UNKNOWN($this)"
        }

    private companion object {
        const val TAG = "SystemZenAdapter"
        const val CONDITION_PATH = "mode"

        /**
         * How long a write of ours counts as explaining an incoming status broadcast.
         * Measured delivery on the 17T Pro is ~50 ms; 5 s is slack for a busy device,
         * still far shorter than any plausible user interaction with Settings.
         */
        const val SELF_ECHO_WINDOW_MS = 5_000L
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
 * call: the system's Modes UI reports *why* the phone is quiet from this field, and
 * only `SOURCE_USER_ACTION` is allowed to override a user's snooze.
 */
internal fun ActivationSource.toConditionSource(): Int = when (this) {
    ActivationSource.USER -> Condition.SOURCE_USER_ACTION
    ActivationSource.SCHEDULE -> Condition.SOURCE_SCHEDULE
    ActivationSource.CONTEXT -> Condition.SOURCE_CONTEXT
}
