package be.nealgysemans.focusmodes.schedule

import be.nealgysemans.focusmodes.data.MINUTES_PER_DAY
import be.nealgysemans.focusmodes.engine.ScheduleWindow
import java.time.ZonedDateTime

/**
 * The clock arithmetic behind `ScheduleSource`, as pure functions over decoded
 * windows.
 *
 * Kept in its own file — with no Room, no JSON and no Android imports — so it can be
 * unit-tested on the JVM. Midnight-wrapping windows ("23:00 to 07:00") are where
 * this kind of code goes wrong, and those bugs surface as a phone that stays silent
 * all morning, so the arithmetic is worth testing directly rather than through the
 * DAO. The one thing it takes from `data/` is `MINUTES_PER_DAY`, a plain `Int` constant in a
 * file that is itself free of Android — a shared calendar fact rather than a dependency.
 */

/**
 * True when a window running from [start] to [end] runs past midnight.
 *
 * **The** spelling of the rule, in one place, because it was written four different ways
 * across the engine and the UI and one of them had it backwards. `end <= start` rather
 * than `end < start` is the load-bearing part: equal times are not an empty window but a
 * *whole day* — a 09:00–09:00 schedule starts at nine and runs until nine tomorrow,
 * which is what [covers] and [nextBoundaryAfter] both already do. A label that called
 * that "09:00 – 09:00" on one line was the one real disagreement between what the
 * schedule does and what the screen said it does.
 *
 * Takes two Ints rather than a [ScheduleWindow] so the editor's in-progress draft — which
 * has no window yet — asks the same function rather than restating it.
 */
internal fun wrapsMidnight(start: Int, end: Int): Boolean = end <= start

/** True when the window runs past midnight, e.g. 23:00 to 07:00. */
internal val ScheduleWindow.wrapsMidnight: Boolean
    get() = wrapsMidnight(startMinuteOfDay, endMinuteOfDay)

/**
 * True when this window is in force on ISO day [day] at [minute] of that day.
 *
 * The one predicate behind both "which mode is on now" ([modeIdActiveAt]) and the UI's
 * "which windows all apply" ([be.nealgysemans.focusmodes.ui.coversWeekMinute]). Those two
 * ask different questions — one mode wins versus every window that clashes — but they
 * have to agree minute for minute, or the overlap note claims a collision the engine
 * would never see.
 *
 * [previousDay] is passed in rather than derived because the two callers derive it
 * differently and both are right: the engine asks `now.minusDays(1)`, which crosses month
 * and year ends correctly, while the UI wraps within a synthetic week that has no dates
 * in it at all.
 *
 * The end minute is exclusive, and a wrapping window is two intervals: its own day from
 * the start to midnight, plus the tail of the *previous* day's occurrence up to the end.
 */
internal fun ScheduleWindow.covers(day: Int, minute: Int, previousDay: Int): Boolean =
    if (wrapsMidnight) {
        (day in daysOfWeek && minute >= startMinuteOfDay) ||
            (previousDay in daysOfWeek && minute < endMinuteOfDay)
    } else {
        day in daysOfWeek && minute >= startMinuteOfDay && minute < endMinuteOfDay
    }

/**
 * Which mode a set of windows covers at [now], or null if none applies.
 *
 * Last match wins, which makes the answer deterministic when the user has
 * overlapping windows; the DAO's sort order fixes what "last" means.
 */
internal fun List<ScheduleWindow>.modeIdActiveAt(now: ZonedDateTime): String? {
    val minute = now.hour * 60 + now.minute
    val today = now.dayOfWeek.value
    val yesterday = now.minusDays(1).dayOfWeek.value

    return lastOrNull { window ->
        window.covers(day = today, minute = minute, previousDay = yesterday)
    }?.modeId
}

/**
 * The next instant after [now] at which [modeIdActiveAt] could change, or null when
 * there is nothing to wait for.
 *
 * Brute-forces the next week of boundaries rather than doing arithmetic on wrapping
 * windows: a few dozen candidates is free, and it is immune to the off-by-one-day
 * bugs that the alternative invites. Only days the window actually applies to
 * produce candidates, so a weekdays-only mode does not wake the device on Sunday.
 */
internal fun List<ScheduleWindow>.nextBoundaryAfter(now: ZonedDateTime): ZonedDateTime? {
    if (isEmpty()) return null

    return (0..DAYS_TO_SCAN).asSequence()
        .flatMap { dayOffset ->
            val date = now.toLocalDate().plusDays(dayOffset.toLong())
            val midnight = date.atStartOfDay(now.zone)
            asSequence()
                .filter { window -> date.dayOfWeek.value in window.daysOfWeek }
                .flatMap { window ->
                    sequenceOf(
                        midnight.plusMinutes(window.startMinuteOfDay.toLong()),
                        // A wrapping window's end belongs to the following day.
                        midnight.plusMinutes(
                            window.endMinuteOfDay.toLong() +
                                if (window.wrapsMidnight) MINUTES_PER_DAY.toLong() else 0L,
                        ),
                    )
                }
        }
        .filter { it.isAfter(now) }
        .minOrNull()
}

/** A full week plus a day, so a weekly-only window always yields a candidate. */
private const val DAYS_TO_SCAN = 8
