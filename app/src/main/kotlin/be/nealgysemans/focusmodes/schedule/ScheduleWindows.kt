package be.nealgysemans.focusmodes.schedule

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
 * DAO.
 */

/** True when the window runs past midnight, e.g. 23:00 to 07:00. */
internal val ScheduleWindow.wrapsMidnight: Boolean
    get() = endMinuteOfDay <= startMinuteOfDay

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
        if (window.wrapsMidnight) {
            // A wrapping window is two intervals: today from its start to midnight,
            // and the tail of *yesterday's* occurrence up to its end.
            (today in window.daysOfWeek && minute >= window.startMinuteOfDay) ||
                (yesterday in window.daysOfWeek && minute < window.endMinuteOfDay)
        } else {
            today in window.daysOfWeek &&
                minute >= window.startMinuteOfDay &&
                minute < window.endMinuteOfDay
        }
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
                                if (window.wrapsMidnight) MINUTES_PER_DAY else 0L,
                        ),
                    )
                }
        }
        .filter { it.isAfter(now) }
        .minOrNull()
}

/** A full week plus a day, so a weekly-only window always yields a candidate. */
private const val DAYS_TO_SCAN = 8

private const val MINUTES_PER_DAY = 24L * 60L
