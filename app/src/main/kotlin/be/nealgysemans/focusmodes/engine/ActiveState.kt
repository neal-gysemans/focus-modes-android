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
 */
data class ActiveState(
    val activeModeId: String? = null,
    val source: ActivationSource? = null,
    val since: Long = 0L,
) {
    /** True when no mode is believed active. */
    val isIdle: Boolean get() = activeModeId == null

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
