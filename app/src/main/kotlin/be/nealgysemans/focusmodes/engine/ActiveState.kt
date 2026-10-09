package be.nealgysemans.focusmodes.engine

/**
 * The app's belief about which mode is on — persisted in DataStore
 * (see `data/ActiveStateStore`), separate from Room's mode definitions.
 *
 * This is deliberately *small* and single-valued: the single-active-mode
 * invariant is encoded in the shape of the type, not in a check somewhere.
 *
 * @property activeModeId the mode believed active, or null when idle.
 * @property source what last changed it; drives the `Condition` source on re-assert.
 * @property since epoch millis of the transition, for the status notification's
 *   "on since" text and for debugging reconcile loops.
 * @property dismissedModeId a scheduled mode the user turned off by hand while its
 *   schedule was still asking for it. Reconcile leaves it off until [dismissedUntil].
 * @property dismissedUntil epoch millis at which the dismissal lapses: the moment the
 *   schedule stops asking for [dismissedModeId], so the next scheduled period starts it
 *   again. [Long.MAX_VALUE] when the schedule never lets go.
 */
data class ActiveState(
    val activeModeId: String? = null,
    val source: ActivationSource? = null,
    val since: Long = 0L,
    val dismissedModeId: String? = null,
    val dismissedUntil: Long = 0L,
) {
    /** True when no mode is believed active. */
    val isIdle: Boolean get() = activeModeId == null

    /**
     * True when the user turned [modeId] off during its scheduled window and that window
     * has not ended yet at [nowMillis].
     *
     * Without this, turning a scheduled mode off lasted exactly until the next reconcile —
     * a tile listening, the app coming to the foreground, the system's own status
     * broadcast — which saw the window still open and turned the mode straight back on.
     * The user turned it off again, and the two fought in a loop.
     */
    fun dismisses(modeId: String?, nowMillis: Long): Boolean =
        modeId != null && modeId == dismissedModeId && nowMillis < dismissedUntil

    /**
     * True when a human turned this on. A pin outranks every schedule and context trigger
     * and survives until the user clears it — never until a timer expires.
     *
     * **Derived**, not stored. It was a constructor parameter alongside [source], and every
     * single write set the two in lockstep: `pinnedByUser = source == USER`, in three places,
     * because "a user activation pins" *is* what a `USER` source means. Two fields that can
     * only ever agree are two fields that a fourth write site could make disagree — and the
     * disagreement the type allowed was the dangerous one: a pin with a `SCHEDULE` source
     * would have been re-asserted to the platform as `SOURCE_SCHEDULE`, which a user snooze
     * silently refuses, so the app would have believed a mode was pinned on while the phone
     * stayed loud.
     *
     * The stored `pinned_by_user` preference key is therefore gone too. Old values are
     * ignored rather than migrated: they were always equal to what this now computes.
     */
    val pinnedByUser: Boolean get() = source == ActivationSource.USER

    companion object {
        /** The state the app starts in, and the state a deactivation lands on. */
        val IDLE = ActiveState()
    }
}
