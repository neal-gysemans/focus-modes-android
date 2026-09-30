package be.nealgysemans.focusmodes.zen

import be.nealgysemans.focusmodes.engine.ActivationSource
import be.nealgysemans.focusmodes.engine.FocusMode

/**
 * What the system currently thinks about one mode's `AutomaticZenRule`.
 *
 * Returned by [ZenAdapter.readBack] so `ModeEngine.reconcile` can compare the
 * app's belief against system truth instead of assuming its last write stuck.
 */
data class ZenRuleSnapshot(
    /** The system-assigned rule id, or null if no rule exists for this mode. */
    val ruleId: String?,
    /** Whether the rule object exists at all (the user can delete it in Settings). */
    val exists: Boolean,
    /** Whether the rule is enabled — a disabled rule ignores state changes. */
    val enabled: Boolean,
    /** Whether the rule's condition is currently `STATE_TRUE`. */
    val active: Boolean,
)

/**
 * The only seam between the app and the platform's zen APIs.
 *
 * Everything the engine needs from `NotificationManager` is expressed here, which
 * keeps `ModeEngine` pure and lets tests drive the policy with an in-memory fake.
 *
 * Note that [activate] and [deactivate] both take an [ActivationSource]. That is
 * not bookkeeping: the `Condition` handed to `setAutomaticZenRuleState` carries a
 * source field, and it must say `SOURCE_USER_ACTION` for a tile tap versus
 * `SOURCE_SCHEDULE` for a schedule firing. Get it wrong and the system's own
 * Modes UI misreports why the phone went quiet.
 */
interface ZenAdapter {

    /**
     * Create or update the system rule backing [mode], returning its rule id.
     *
     * Idempotent: called on every reconcile. Room is the source of truth, so this
     * pushes the app's definition at the system, not the reverse — but note the
     * platform lets a user's manual edit in Settings freeze later app updates to
     * that rule, which is why [readBack] exists.
     */
    fun ensureRule(mode: FocusMode): String?

    /**
     * Drive [modeId]'s rule to `STATE_TRUE`, attributing the change to [source].
     *
     * Returns what the **system** reports afterwards, not what we asked for: a
     * `SOURCE_SCHEDULE` activation is silently refused while the user has snoozed
     * the rule (see `AUTOMATIC_RULE_STATUS_DEACTIVATED`), so the only honest answer
     * comes from reading the state back. False also covers "we have no DND access",
     * in which case nothing happened at all.
     */
    fun activate(modeId: String, source: ActivationSource): Boolean

    /**
     * Drive [modeId]'s rule to `STATE_FALSE`, attributing the change to [source].
     *
     * Also the documented way to clear a user snooze: the platform ignores a later
     * `STATE_TRUE` until a `STATE_FALSE` has been sent, so a schedule's end
     * boundary must report itself even when the mode is already off.
     */
    fun deactivate(modeId: String, source: ActivationSource): Boolean

    /** Read system truth for [modeId], or null when nothing is known about it. */
    fun readBack(modeId: String): ZenRuleSnapshot?
}
