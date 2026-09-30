package be.nealgysemans.focusmodes.engine

import java.time.ZonedDateTime

/**
 * Read access to the mode definitions [ModeEngine] reconciles against.
 *
 * Backed by Room in production (`data/RoomModeCatalog`); a plain list in tests.
 * Deliberately synchronous: the engine runs on one background dispatcher and
 * must be able to compute a decision without suspending, so that a tile tap or
 * a boot broadcast can be handled inside its short lifetime budget.
 */
interface ModeCatalog {
    /** Every mode the user has defined, in display order. */
    fun modes(): List<FocusMode>

    /** The mode with [modeId], or null if it was deleted out from under a trigger. */
    fun find(modeId: String): FocusMode? = modes().firstOrNull { it.id == modeId }
}

/**
 * A time window a schedule trigger owns.
 *
 * `paramsJson` on `data/TriggerEntity` decodes into these; keeping the decoded
 * form in the engine package means the reducer never parses JSON.
 */
data class ScheduleWindow(
    val triggerId: String,
    val modeId: String,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    /** ISO day-of-week values (1 = Monday) this window applies to. */
    val daysOfWeek: Set<Int>,
)

/**
 * Answers "which mode *should* be on right now, purely from the clock?".
 *
 * This is the input that makes [ModeEngine.reconcile] a reconciliation loop
 * rather than an event log replay: after a reboot, a doze skip or a missed
 * alarm, the engine asks this instead of trying to work out which events it lost.
 */
interface ScheduleSource {
    /** The mode a schedule window covers at [now], or null if no window applies. */
    fun modeIdActiveAt(now: ZonedDateTime): String?

    /**
     * The next instant at which [modeIdActiveAt] could change, used by
     * `schedule/AlarmScheduler` to arm exactly one alarm. Null when no schedules
     * exist, in which case no alarm is armed at all.
     */
    fun nextBoundaryAfter(now: ZonedDateTime): ZonedDateTime?
}

/**
 * Persistence port for [ActiveState].
 *
 * Synchronous for the same reason as [ModeCatalog]; the DataStore-backed
 * implementation in `data/` bridges the suspending API.
 */
interface ActiveStateStore {
    fun read(): ActiveState

    fun write(state: ActiveState)
}
