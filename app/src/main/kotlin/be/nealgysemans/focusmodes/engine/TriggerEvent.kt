package be.nealgysemans.focusmodes.engine

/**
 * Where a state change came from.
 *
 * This is threaded through **every** activate/deactivate call because the
 * system's `Condition` needs the matching `SOURCE_*` value — a user tap must
 * report `Condition.SOURCE_USER_ACTION` and a schedule firing must report
 * `Condition.SOURCE_SCHEDULE`, or the system's own UI misattributes the change.
 * It is also the input to the priority policy in [ModeEngine].
 */
enum class ActivationSource {
    /** A tile tap, an in-app toggle, or a notification action. Pins the mode. */
    USER,

    /** A time window firing via `schedule/AlarmScheduler`. Yields to a user pin. */
    SCHEDULE,

    /** Reserved: future context triggers (location, app launch, Bluetooth). Yields to a user pin. */
    CONTEXT,
}

/** Which way a [TriggerEvent] pushes the mode. */
enum class Direction {
    ACTIVATE,
    DEACTIVATE,
}

/**
 * The only thing that may be fed into [ModeEngine.onEvent].
 *
 * Tiles, schedules, boot and notification actions all build one of these rather
 * than touching a zen rule directly, so there is exactly one code path that can
 * change which mode is on.
 */
data class TriggerEvent(
    val source: ActivationSource,
    val modeId: String,
    val direction: Direction,
)
