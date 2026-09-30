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
 * @property pinnedByUser true when a human turned this on. A pin outranks every
 *   schedule and context trigger and survives until the user clears it — never
 *   until a timer expires.
 */
data class ActiveState(
    val activeModeId: String? = null,
    val source: ActivationSource? = null,
    val since: Long = 0L,
    val pinnedByUser: Boolean = false,
) {
    /** True when no mode is believed active. */
    val isIdle: Boolean get() = activeModeId == null

    companion object {
        /** The state the app starts in, and the state a deactivation lands on. */
        val IDLE = ActiveState()
    }
}
