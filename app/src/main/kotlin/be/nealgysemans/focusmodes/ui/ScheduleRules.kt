package be.nealgysemans.focusmodes.ui

import be.nealgysemans.focusmodes.data.MINUTES_PER_DAY
import be.nealgysemans.focusmodes.data.TriggerEntity
import be.nealgysemans.focusmodes.engine.ScheduleWindow
import be.nealgysemans.focusmodes.schedule.nextBoundaryAfter
import be.nealgysemans.focusmodes.schedule.toWindowOrNull
import be.nealgysemans.focusmodes.schedule.wrapsMidnight
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Everything the schedule UI has to *work out* rather than draw.
 *
 * Deliberately free of Compose and of Android: no `stringResource`, no `Context`, no
 * `R`. That is what lets `ScheduleRulesTest` pin the two answers most easily got
 * wrong — which schedules overlap, and when the next boundary is — on the JVM, with
 * no emulator and no screenshot to squint at.
 *
 * The boundary arithmetic is not reimplemented here. [nextBoundary] delegates to the
 * same `schedule/ScheduleWindows.nextBoundaryAfter` that `AlarmScheduler` arms from,
 * so the "Next: Mon 09:00" the user reads is by construction the instant the alarm is
 * actually set for. [coversWeekMinute] does restate the wrap rule from
 * `modeIdActiveAt`, because that function answers "which *one* mode wins" and overlap
 * honesty needs "which windows *all* apply" — the shared half is the two-interval
 * treatment of a midnight-wrapping window, and it is spelled the same way on purpose.
 */

/** ISO day-of-week values in the order the UI shows them, Monday first. */
internal val ISO_WEEK: List<Int> = (1..7).toList()

/** Minutes in a week; the domain [coversWeekMinute] is sampled over. */
internal const val MINUTES_PER_WEEK: Int = 7 * MINUTES_PER_DAY

/**
 * One schedule as the editor deals with it: the row it will write back, and the
 * decoded window it draws — or null when the row cannot be decoded at all.
 *
 * Both halves are kept because they are needed for different jobs. Editing and
 * deleting address the [TriggerEntity]; every label and every overlap question is
 * answered from the [ScheduleWindow]. A null [window] is a real state, not an
 * impossible one: `params_json` is free text in the database, and a row written by a
 * future build or edited by hand has to be visible enough for the user to delete.
 */
internal data class ScheduleRow(
    val trigger: TriggerEntity,
    val window: ScheduleWindow?,
)

/** Decode rows for display, keeping unreadable ones so they can be deleted. */
internal fun List<TriggerEntity>.toScheduleRows(): List<ScheduleRow> =
    map { ScheduleRow(trigger = it, window = it.toWindowOrNull()) }

/**
 * Schedules in the order a person would list them: earliest start first.
 *
 * The DAO orders by primary key, which is the right thing for a query and the wrong
 * thing for a screen — trigger ids are a mix of seeded names and random UUIDs, so "07:00"
 * can sort below "23:00" for no reason the user can see. That is not cosmetic: two rows
 * whose only difference is their invisible id are two rows you can delete the wrong one
 * of, which is exactly what happened while testing this screen.
 *
 * Unreadable rows sink to the bottom, where a row you can only delete belongs. Ties break
 * on the id so the order is total and the list never reshuffles under a re-render.
 */
internal fun List<ScheduleRow>.sortedForDisplay(): List<ScheduleRow> = sortedWith(
    compareBy({ it.window?.startMinuteOfDay ?: Int.MAX_VALUE }, { it.trigger.id }),
)

/**
 * True when this window is in force at [weekMinute], an offset from Monday 00:00.
 *
 * Mirrors `ScheduleWindows.modeIdActiveAt`: the end minute is exclusive, and a
 * wrapping window is two intervals — its own day from the start to midnight, plus the
 * tail of the *previous* day's occurrence up to the end.
 */
internal fun ScheduleWindow.coversWeekMinute(weekMinute: Int): Boolean {
    val day = weekMinute / MINUTES_PER_DAY + 1
    val minute = weekMinute % MINUTES_PER_DAY
    val previousDay = if (day == 1) ISO_WEEK.last() else day - 1

    return if (wrapsMidnight) {
        (day in daysOfWeek && minute >= startMinuteOfDay) ||
            (previousDay in daysOfWeek && minute < endMinuteOfDay)
    } else {
        day in daysOfWeek && minute >= startMinuteOfDay && minute < endMinuteOfDay
    }
}

/**
 * The trigger ids of every window that shares at least one minute with another.
 *
 * Brute-forced a minute at a time over a whole week, for the same reason
 * `nextBoundaryAfter` brute-forces the next eight days: interval arithmetic on
 * windows that wrap midnight *and* wrap the week is exactly where off-by-one bugs
 * live, ten thousand iterations of a comparison are free, and sampling the same
 * predicate the engine uses means the note cannot claim an overlap the engine would
 * not actually see.
 *
 * Reported for *both* sides of a clash, because a note on only one of two rows reads
 * as "this one is the problem" when the truth is that the pair is ambiguous. Nothing
 * here blocks a save: overlapping schedules are a legitimate thing to want, the app
 * just owes the user the fact that only one mode can be on at a time.
 */
internal fun overlappingTriggerIds(windows: List<ScheduleWindow>): Set<String> {
    if (windows.size < 2) return emptySet()

    val overlapping = mutableSetOf<String>()
    val covering = ArrayList<String>(windows.size)

    for (weekMinute in 0 until MINUTES_PER_WEEK) {
        covering.clear()
        for (window in windows) {
            if (window.coversWeekMinute(weekMinute)) covering += window.triggerId
        }
        if (covering.size > 1) {
            overlapping += covering
            // Every window is already implicated; no minute can add anything.
            if (overlapping.size == windows.size) return overlapping
        }
    }
    return overlapping
}

/**
 * The next instant after [now] at which this one window starts or ends.
 *
 * Single-window on purpose: the list shows a "Next:" per row, and asking the whole
 * set would give every row the same answer — the earliest boundary of any of them.
 */
internal fun ScheduleWindow.nextBoundary(now: ZonedDateTime): ZonedDateTime? =
    listOf(this).nextBoundaryAfter(now)

// ------------------------------------------------------------------------ labels

/**
 * The recognisable day sets, so the list can say "Weekdays" instead of
 * "Mon, Tue, Wed, Thu, Fri" — five names in a row is a thing to decode, not read.
 */
internal enum class DaySet { NONE, EVERY_DAY, WEEKDAYS, WEEKENDS, CUSTOM }

internal fun daySetOf(days: Set<Int>): DaySet = when {
    days.isEmpty() -> DaySet.NONE
    days == ISO_WEEK.toSet() -> DaySet.EVERY_DAY
    days == WEEKDAYS -> DaySet.WEEKDAYS
    days == WEEKENDS -> DaySet.WEEKENDS
    else -> DaySet.CUSTOM
}

/** Monday to Friday. */
internal val WEEKDAYS: Set<Int> = setOf(1, 2, 3, 4, 5)

/** Saturday and Sunday. */
internal val WEEKENDS: Set<Int> = setOf(6, 7)

/**
 * How this UI renders clock values: which language names the days, and whether times
 * are 12- or 24-hour.
 *
 * The two travel together because every label needs both and neither has a sane
 * default at this layer — the locale comes from the composition's configuration (so a
 * per-app language override is honoured) and the hour convention from the device
 * *setting*, which is a different source and genuinely disagrees with the locale on
 * plenty of phones.
 *
 * Passed in rather than read from `Locale.getDefault()` inside these functions, which
 * is what lets the tests assert exact strings without depending on whose machine runs
 * them.
 */
internal data class ClockStyle(
    val locale: Locale,
    val is24Hour: Boolean,
)

/**
 * Abbreviated day names in week order, e.g. "Mon, Wed, Fri".
 *
 * Names come from `java.time`, not from `strings.xml`: the JDK already knows them in
 * every locale, and a hand-translated list of seven words is seven chances to be wrong
 * in a language nobody on the project reads.
 */
internal fun daysLabel(days: Set<Int>, style: ClockStyle): String =
    ISO_WEEK.filter { it in days }.joinToString(separator = ", ") { dayLabel(it, style) }

internal fun dayLabel(isoDay: Int, style: ClockStyle): String =
    DayOfWeek.of(isoDay).getDisplayName(TextStyle.SHORT, style.locale)

/** One or two characters, for the day toggles in the schedule dialog. */
internal fun dayInitial(isoDay: Int, style: ClockStyle): String =
    DayOfWeek.of(isoDay).getDisplayName(TextStyle.NARROW, style.locale)

/** The full day name, for the day toggles' content description. */
internal fun dayName(isoDay: Int, style: ClockStyle): String =
    DayOfWeek.of(isoDay).getDisplayName(TextStyle.FULL, style.locale)

/**
 * A minute-of-day as a clock time.
 *
 * The hour convention comes from `DateFormat.is24HourFormat`, the device *setting*, not
 * from the locale — and the two genuinely disagree. The test phone is `en_US` with the
 * 24-hour clock switched on: `ofLocalizedTime(SHORT)` renders "9:00 AM" while the
 * Material time picker the value was just set in shows a 24-hour dial. Reading a time
 * back in a different convention than it was entered in looks like the app lost it, so
 * the setting wins everywhere.
 *
 * The 12-hour branch forces `h:mm a` rather than deferring to the locale, for the
 * mirror-image reason: a user on a 24-hour locale who has switched the setting off must
 * get 12-hour times, and `ofLocalizedTime` would keep handing back 24-hour ones.
 */
internal fun timeLabel(minuteOfDay: Int, style: ClockStyle): String {
    val minute = ((minuteOfDay % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
    return LocalTime.of(minute / 60, minute % 60).format(timeFormatter(style))
}

/** e.g. "Mon 09:00" — the "Next:" value. */
internal fun boundaryLabel(boundary: ZonedDateTime, style: ClockStyle): String {
    val time = timeLabel(boundary.hour * 60 + boundary.minute, style)
    return "${dayLabel(boundary.dayOfWeek.value, style)} $time"
}

private fun timeFormatter(style: ClockStyle): DateTimeFormatter = DateTimeFormatter.ofPattern(
    if (style.is24Hour) PATTERN_24_HOUR else PATTERN_12_HOUR,
    style.locale,
)

private const val PATTERN_24_HOUR = "HH:mm"
private const val PATTERN_12_HOUR = "h:mm a"
