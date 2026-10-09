package be.nealgysemans.focusmodes.engine

/**
 * Why [ModeEngine] declined to act on a [TriggerEvent].
 *
 * Dropped events are a normal, expected outcome — not errors — so they are
 * modelled explicitly and surfaced in logs rather than swallowed.
 */
enum class IgnoreReason {
    /** A user pin is in force and a schedule/context trigger tried to override it. */
    USER_PIN_HOLDS,

    /** The user turned this mode off during its schedule window, and the window is still open. */
    USER_DISMISSED,

    /** The event asked for the state the engine is already in. */
    ALREADY_IN_DESIRED_STATE,

    /** The mode was deleted between the trigger being armed and it firing. */
    UNKNOWN_MODE,
}

/**
 * The outcome of one pass through [ModeEngine].
 *
 * Returning a value instead of just mutating makes the priority policy directly
 * unit-testable, and gives the tile / notification layers something to react to
 * without re-reading state.
 */
sealed interface Transition {
    /** The engine converged without changing anything; already at the desired state. */
    data object NoChange : Transition

    /**
     * [modeId] is now active.
     *
     * @property deactivated modes turned off to preserve the single-active invariant.
     */
    data class Activated(
        val modeId: String,
        val source: ActivationSource,
        val deactivated: List<String> = emptyList(),
    ) : Transition

    /** [modeId] is now off and nothing replaced it. */
    data class Deactivated(
        val modeId: String,
        val source: ActivationSource,
    ) : Transition

    /** The event was dropped on purpose; see [reason]. */
    data class Ignored(
        val event: TriggerEvent,
        val reason: IgnoreReason,
    ) : Transition
}
