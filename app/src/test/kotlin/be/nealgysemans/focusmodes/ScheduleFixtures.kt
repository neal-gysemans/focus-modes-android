package be.nealgysemans.focusmodes

import be.nealgysemans.focusmodes.data.ISO_WEEK
import be.nealgysemans.focusmodes.engine.ScheduleWindow
import be.nealgysemans.focusmodes.ui.WEEKDAYS
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The fixtures every schedule test needs, in one place.
 *
 * Three test classes in three packages — `schedule/ScheduleWindowsTest`,
 * `ui/ScheduleRulesTest`, `data/ScheduleParamsTest` — all ask the same questions of the same
 * two windows on the same real dates, and all three had their own copy of the zone, the
 * calendar, the `at()` helper and the `ScheduleWindow` boilerplate.
 *
 * That was worse than repetition. `ScheduleRules.coversWeekMinute` and
 * `ScheduleWindows.modeIdActiveAt` are now the *same predicate* (see `ScheduleWindows.covers`),
 * and the two test classes that pin them are only mutually meaningful if they are testing it
 * against the same windows. Sharing the fixtures is what makes "these two agree" something the
 * files demonstrate rather than something a reader has to check by comparing numbers.
 *
 * Kept as a peer of `engine/Fakes.kt` rather than inside any one test package, for the same
 * reason: no single package owns it.
 *
 * Dates are real, and the test bodies say so: 2026-09-28 is a Monday, 2026-10-03 a Saturday.
 */

/** A zone with DST, so nothing here can accidentally pass only because it is UTC. */
internal val TEST_ZONE: ZoneId = ZoneId.of("Europe/Brussels")

internal val MONDAY: LocalDate = LocalDate.parse("2026-09-28")
internal val TUESDAY: LocalDate = LocalDate.parse("2026-09-29")
internal val FRIDAY: LocalDate = LocalDate.parse("2026-10-02")
internal val SATURDAY: LocalDate = LocalDate.parse("2026-10-03")
internal val SUNDAY: LocalDate = LocalDate.parse("2026-10-04")

/** Monday to Friday, and every day: the production constants, not copies of them. */
internal val WEEKDAY_DAYS: Set<Int> = WEEKDAYS
internal val EVERY_DAY: Set<Int> = ISO_WEEK.toSet()

/** This date at a wall-clock time, e.g. `MONDAY.at("09:00")`. */
internal fun LocalDate.at(time: String): ZonedDateTime =
    ZonedDateTime.of(this, LocalTime.parse(time), TEST_ZONE)

/**
 * A window, with ids the caller chooses.
 *
 * Both are parameters because the two test classes assert on different halves: the engine's
 * arithmetic answers with a *mode* id, while the UI's overlap note names *trigger* ids.
 */
internal fun testWindow(
    triggerId: String,
    modeId: String,
    start: Int,
    end: Int,
    days: Set<Int>,
): ScheduleWindow = ScheduleWindow(
    triggerId = triggerId,
    modeId = modeId,
    startMinuteOfDay = start,
    endMinuteOfDay = end,
    daysOfWeek = days,
)
