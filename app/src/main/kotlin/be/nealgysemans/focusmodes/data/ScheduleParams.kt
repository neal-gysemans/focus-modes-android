package be.nealgysemans.focusmodes.data

import java.util.UUID

/**
 * The **writer** for a schedule trigger's `params_json`.
 *
 * The reader lives in `schedule/TriggerScheduleSource.toWindowOrNull`, and the two
 * have to agree exactly or a schedule the user just created is silently dropped on
 * the next reconcile. They are deliberately not the same function: the reader has to
 * survive anything (a row written by an older build, a hand-edited database) so it is
 * a tolerant `org.json` parse that returns null on anything it cannot make sense of,
 * while the writer only ever has to emit one canonical shape.
 *
 * That shape, matching the seeded rows in [Seed] character for character:
 *
 * ```
 * {"start":540,"end":1020,"days":[1,2,3,4,5]}
 * ```
 *
 * `start` and `end` are minutes since local midnight, 0..1439. `days` are ISO
 * day-of-week values (1 = Monday) naming the days the window **starts** on, sorted
 * ascending so two equal schedules produce equal strings. An `end` less than or equal
 * to `start` means the window wraps midnight; `end` of 0 therefore reads as "until
 * midnight", which is why 1440 is normalised down to 0 rather than rejected.
 *
 * Built by hand instead of through `org.json` on purpose: the keys are three fixed
 * identifiers and every value is an `Int`, so there is nothing to escape, and a plain
 * string keeps this file free of Android — `ScheduleParamsTest` round-trips it on the
 * JVM, which is the only test that can catch the writer and reader drifting apart.
 */

// The two calendar facts the whole app shares, declared here — the lowest layer that needs
// them — so that `schedule/` and `ui/` import them rather than each keeping a copy. They had
// four spellings between them: this file's Int and `ScheduleWindows`' Long
// `MINUTES_PER_DAY`, and this file's `1..7` range beside `ScheduleRules`' `(1..7).toList()`.

/** Minutes in a day. Every stored minute-of-day is reduced modulo this. */
internal const val MINUTES_PER_DAY: Int = 24 * 60

/**
 * ISO day-of-week values (1 = Monday) in week order: the only ones `days` may contain, and
 * the order every day-set the UI draws is presented in.
 *
 * A `List` rather than an `IntRange` because the order is load-bearing for the UI — the day
 * toggles and "Mon, Wed, Fri" both iterate it — and membership over seven elements is free.
 */
internal val ISO_WEEK: List<Int> = (1..7).toList()

/**
 * Encode a schedule window into the canonical `params_json`.
 *
 * Out-of-range days are dropped rather than clamped: a "day 9" has no sane nearest
 * neighbour, and an empty `days` array is already a meaningful state (a schedule that
 * never fires, which the editor warns about instead of silently fixing).
 */
fun scheduleParamsJson(
    startMinuteOfDay: Int,
    endMinuteOfDay: Int,
    daysOfWeek: Set<Int>,
): String {
    val days = daysOfWeek.filter { it in ISO_WEEK }.sorted().joinToString(separator = ",")
    return "{\"start\":${normalizeMinuteOfDay(startMinuteOfDay)}," +
        "\"end\":${normalizeMinuteOfDay(endMinuteOfDay)}," +
        "\"days\":[$days]}"
}

/** A complete schedule row, so `TriggerType.SCHEDULE` is named in exactly one place. */
fun scheduleTrigger(
    id: String,
    modeId: String,
    startMinuteOfDay: Int,
    endMinuteOfDay: Int,
    daysOfWeek: Set<Int>,
    enabled: Boolean = true,
): TriggerEntity = TriggerEntity(
    id = id,
    modeId = modeId,
    type = TriggerType.SCHEDULE,
    paramsJson = scheduleParamsJson(startMinuteOfDay, endMinuteOfDay, daysOfWeek),
    enabled = enabled,
)

/**
 * An id for a schedule the user just created.
 *
 * Prefixed so it is distinguishable from the seeded `trigger-work-weekdays` /
 * `trigger-sleep-nightly` rows when reading the database by hand, and random rather
 * than sequential so two devices editing the same restored database cannot collide.
 */
fun newScheduleTriggerId(): String = "schedule-${UUID.randomUUID()}"

/**
 * Fold any integer into 0..1439, so 1440 ("midnight tomorrow") reads as 0.
 *
 * Double-modulo rather than a single one, because Kotlin's `%` keeps the sign of the
 * dividend: `-60 % 1440` is -60, not 1380. Shared with `ui/timeLabel`, which renders these
 * values and has to fold exactly the same way — the writer normalising and the reader
 * normalising differently is how a stored time comes back as something the user never typed.
 */
internal fun normalizeMinuteOfDay(minute: Int): Int =
    ((minute % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
