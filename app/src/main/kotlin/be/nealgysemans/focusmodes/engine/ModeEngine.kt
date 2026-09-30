package be.nealgysemans.focusmodes.engine

import be.nealgysemans.focusmodes.zen.ZenAdapter
import java.time.Clock
import java.time.ZonedDateTime

/**
 * The single reducer. Every mode change in the app goes through here.
 *
 * Nothing else is allowed to call `setAutomaticZenRuleState`: the tile, the
 * alarm receiver, the boot receiver, the notification action and the UI all
 * build a [TriggerEvent] and hand it to [onEvent], or ask for [reconcile].
 * That is what makes the two invariants enforceable in one place:
 *
 *  - **Manual pin wins.** A [ActivationSource.USER] activation sets
 *    `pinnedByUser` and holds until a user clears it. Schedule and context
 *    triggers arriving while pinned are dropped with
 *    [IgnoreReason.USER_PIN_HOLDS] — there is no timeout that silently releases
 *    the pin, because "my focus turned itself off" is the worst failure mode.
 *  - **Single active mode.** Activating a mode deactivates whatever was on.
 *    The type system helps: [ActiveState] holds one nullable id, not a set.
 *
 * [reconcile] is the recovery path. Rather than replaying events it lost across
 * a reboot, a doze window or an OEM alarm kill, it recomputes the desired mode
 * from the wall clock plus stored schedules and converges. It is idempotent:
 * calling it twice in a row produces [Transition.NoChange] the second time.
 *
 * Pure Kotlin — no Android imports, no coroutines — so the policy is testable on
 * the JVM with a fixed [Clock] and fake ports.
 */
class ModeEngine(
    private val clock: Clock,
    private val catalog: ModeCatalog,
    private val schedules: ScheduleSource,
    private val state: ActiveStateStore,
    private val zen: ZenAdapter,
) {

    /**
     * Apply one trigger.
     *
     * Returns what actually happened, including the deliberate no-ops, so
     * callers can log a dropped schedule firing instead of wondering why
     * nothing changed.
     */
    fun onEvent(event: TriggerEvent): Transition {
        if (catalog.find(event.modeId) == null) {
            return Transition.Ignored(event, IgnoreReason.UNKNOWN_MODE)
        }
        val current = state.read()
        return when (event.direction) {
            Direction.ACTIVATE -> applyActivate(event, current)
            Direction.DEACTIVATE -> applyDeactivate(event, current)
        }
    }

    /**
     * Recompute the desired mode from the wall clock and stored schedules, then
     * converge on it.
     *
     * Called on boot, on every alarm fire, when the tile starts listening, and
     * whenever the app comes to the foreground. Safe to call at any time.
     *
     * Healing the system side is part of this, in two halves. [ZenAdapter.ensureRule]
     * below re-creates or re-pushes the rule for every mode, which covers one the user
     * deleted or edited in Settings; [healDrift] re-asserts the mode the app believes is
     * on when [ZenAdapter.readBack] says the system disagrees. Re-arming the next alarm
     * from [ScheduleSource.nextBoundaryAfter] is not done here on purpose — it is a side
     * effect rather than a decision, so `di/AppGraph.afterTransition` owns it and runs it
     * after *every* transition, including [Transition.NoChange].
     */
    fun reconcile(): Transition {
        val now = ZonedDateTime.now(clock)
        val current = state.read()

        // Make sure a system rule exists for every mode before deciding anything;
        // a missing rule would make activation silently no-op.
        catalog.modes().forEach { zen.ensureRule(it) }

        val pinnedMode = current.activeModeId
        if (current.pinnedByUser && pinnedMode != null) {
            // A user pin outranks the clock. Only heal drift, never override.
            healDrift(pinnedMode, ActivationSource.USER)
            return Transition.NoChange
        }

        val desired = schedules.modeIdActiveAt(now)
        return when {
            desired == current.activeModeId -> {
                desired?.let { healDrift(it, current.source ?: ActivationSource.SCHEDULE) }
                Transition.NoChange
            }

            desired != null -> applyActivate(
                TriggerEvent(ActivationSource.SCHEDULE, desired, Direction.ACTIVATE),
                current,
            )

            else -> applyDeactivate(
                TriggerEvent(ActivationSource.SCHEDULE, current.activeModeId!!, Direction.DEACTIVATE),
                current,
            )
        }
    }

    private fun applyActivate(event: TriggerEvent, current: ActiveState): Transition {
        if (current.pinnedByUser && event.source != ActivationSource.USER) {
            return Transition.Ignored(event, IgnoreReason.USER_PIN_HOLDS)
        }

        if (current.activeModeId == event.modeId) {
            // Already on. A user tap on an already-scheduled mode upgrades it to a
            // pin, so the schedule's end boundary will not turn it off.
            if (event.source == ActivationSource.USER && !current.pinnedByUser) {
                // Writing the source *is* setting the pin — see [ActiveState.pinnedByUser].
                state.write(current.copy(source = ActivationSource.USER))
                return Transition.Activated(event.modeId, ActivationSource.USER)
            }
            return Transition.Ignored(event, IgnoreReason.ALREADY_IN_DESIRED_STATE)
        }

        // Single-active invariant: whatever was on goes off first, and it goes off
        // carrying the *new* event's source so the system attributes it correctly.
        val displaced = current.activeModeId
        if (displaced != null) {
            zen.deactivate(displaced, event.source)
        }
        zen.activate(event.modeId, event.source)
        state.write(
            ActiveState(
                activeModeId = event.modeId,
                source = event.source,
                since = clock.millis(),
            ),
        )
        return Transition.Activated(event.modeId, event.source, listOfNotNull(displaced))
    }

    private fun applyDeactivate(event: TriggerEvent, current: ActiveState): Transition {
        if (current.activeModeId != event.modeId) {
            return Transition.Ignored(event, IgnoreReason.ALREADY_IN_DESIRED_STATE)
        }
        if (current.pinnedByUser && event.source != ActivationSource.USER) {
            return Transition.Ignored(event, IgnoreReason.USER_PIN_HOLDS)
        }
        zen.deactivate(event.modeId, event.source)
        state.write(ActiveState.IDLE)
        return Transition.Deactivated(event.modeId, event.source)
    }

    /**
     * Re-assert a mode the app believes is on but the system says is off.
     *
     * Costs one read per reconcile and keeps reconcile idempotent: when the
     * system agrees, nothing is written.
     *
     * `!= true` rather than `== false`, deliberately: [ZenAdapter.readBack] answers null for
     * "cannot tell", and not being able to tell is a reason to re-assert — the activation is
     * idempotent, while leaving a mode the app believes is on silently off is not recoverable
     * until something else happens.
     */
    private fun healDrift(modeId: String, source: ActivationSource) {
        if (zen.readBack(modeId) != true) {
            zen.activate(modeId, source)
        }
    }
}
