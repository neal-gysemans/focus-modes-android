package be.nealgysemans.focusmodes.ui

import be.nealgysemans.focusmodes.data.TriggerEntity
import be.nealgysemans.focusmodes.data.TriggerType
import be.nealgysemans.focusmodes.data.scheduleTrigger
import be.nealgysemans.focusmodes.engine.ScheduleWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * The two things the schedule UI claims that it could get wrong without anything
 * crashing: which schedules fight each other, and when the next one fires.
 *
 * Both are honesty features. The overlap note exists because only one mode can be on at
 * a time, so two windows covering the same minute means one of them loses and the user
 * deserves to know which pair is ambiguous. The "Next:" label exists because a mode that
 * turns itself on is a change to the phone the user did not make, and a wrong time there
 * is worse than none — so it is asserted against the same `nextBoundaryAfter` the alarm
 * is armed from.
 *
 * The clock style is pinned rather than taken from the environment: production reads it
 * from the composition and the device setting, and passing it in is exactly what lets
 * these assertions name a string instead of depending on whose machine runs them.
 * `Locale.US` because that is what the test phone runs, and because the AM/PM markers
 * differ in case between `en_US` and `en_GB` in CLDR — a difference worth not asserting
 * by accident.
 *
 * Dates are real: 2026-09-28 is a Monday, 2026-10-03 a Saturday.
 */
class ScheduleRulesTest {

    private val zone: ZoneId = ZoneId.of("Europe/Brussels")
    /** 24-hour: "Mon 09:00" is then a stable expectation. */
    private val clock = ClockStyle(locale = Locale.US, is24Hour = true)

    /** Same language, 12-hour clock — the other half of the device setting. */
    private val clock12 = clock.copy(is24Hour = false)

    private val monday = LocalDate.parse("2026-09-28")
    private val friday = LocalDate.parse("2026-10-02")
    private val saturday = LocalDate.parse("2026-10-03")

    /** Weekdays 09:00-17:00. */
    private val work = window("work", 9 * 60, 17 * 60, setOf(1, 2, 3, 4, 5))

    /** Every night 23:00-07:00 — the wrapping case. */
    private val nightly = window("sleep", 23 * 60, 7 * 60, setOf(1, 2, 3, 4, 5, 6, 7))

    // --- which minutes a window covers ----------------------------------------

    @Test
    fun `a plain window covers its own day between start and end`() {
        assertFalse(work.coversWeekMinute(weekMinute(day = 1, hour = 8, minute = 59)))
        assertTrue(work.coversWeekMinute(weekMinute(day = 1, hour = 9, minute = 0)))
        assertTrue(work.coversWeekMinute(weekMinute(day = 1, hour = 16, minute = 59)))
        assertFalse(
            "the end minute is exclusive, same as modeIdActiveAt",
            work.coversWeekMinute(weekMinute(day = 1, hour = 17, minute = 0)),
        )
        assertFalse(
            "Saturday is not in the day set",
            work.coversWeekMinute(weekMinute(day = 6, hour = 10, minute = 0)),
        )
    }

    @Test
    fun `a wrapping window covers both sides of midnight`() {
        assertTrue(nightly.coversWeekMinute(weekMinute(day = 1, hour = 23, minute = 30)))
        assertTrue(
            "Tuesday 06:00 still belongs to Monday's window",
            nightly.coversWeekMinute(weekMinute(day = 2, hour = 6, minute = 0)),
        )
        assertFalse(nightly.coversWeekMinute(weekMinute(day = 2, hour = 7, minute = 0)))
    }

    @Test
    fun `a wrapping window on Sunday only reaches back into Monday morning`() {
        // The week wrap: Sunday's tail lands on the *first* minutes of the sampled week,
        // which is where naive interval arithmetic loses it.
        val sundayNight = nightly.copy(daysOfWeek = setOf(7))

        assertTrue(sundayNight.coversWeekMinute(weekMinute(day = 7, hour = 23, minute = 30)))
        assertTrue(sundayNight.coversWeekMinute(weekMinute(day = 1, hour = 6, minute = 0)))
        assertFalse(sundayNight.coversWeekMinute(weekMinute(day = 1, hour = 7, minute = 0)))
    }

    // --- overlap honesty ------------------------------------------------------

    @Test
    fun `one schedule cannot overlap anything`() {
        assertEquals(emptySet<String>(), overlappingTriggerIds(listOf(work)))
        assertEquals(emptySet<String>(), overlappingTriggerIds(emptyList()))
    }

    @Test
    fun `schedules that do not share a minute are not flagged`() {
        val evening = window("evening", 18 * 60, 22 * 60, setOf(1, 2, 3, 4, 5))

        assertEquals(emptySet<String>(), overlappingTriggerIds(listOf(work, evening)))
    }

    @Test
    fun `touching schedules do not overlap, because the end minute is exclusive`() {
        val afternoon = window("afternoon", 17 * 60, 19 * 60, setOf(1, 2, 3, 4, 5))

        assertEquals(emptySet<String>(), overlappingTriggerIds(listOf(work, afternoon)))
    }

    @Test
    fun `both sides of a clash are flagged, not just the later one`() {
        val lunch = window("lunch", 12 * 60, 13 * 60, setOf(1))

        assertEquals(
            setOf("trigger-work", "trigger-lunch"),
            overlappingTriggerIds(listOf(work, lunch)),
        )
    }

    @Test
    fun `a clash across midnight between two different modes is found`() {
        // Work 23:00-07:00 nightly vs a Monday-only 06:00-08:00: they share 06:00-07:00 on
        // Monday morning, which belongs to *Sunday's* occurrence of the wrapping window.
        val earlyMonday = window("early", 6 * 60, 8 * 60, setOf(1))

        assertEquals(
            setOf("trigger-sleep", "trigger-early"),
            overlappingTriggerIds(listOf(nightly, earlyMonday)),
        )
    }

    @Test
    fun `an unclashing third schedule stays unflagged`() {
        val lunch = window("lunch", 12 * 60, 13 * 60, setOf(1))
        val weekend = window("weekend", 10 * 60, 12 * 60, setOf(6, 7))

        assertEquals(
            setOf("trigger-work", "trigger-lunch"),
            overlappingTriggerIds(listOf(work, lunch, weekend)),
        )
    }

    @Test
    fun `a schedule with no days overlaps nothing`() {
        val orphan = window("orphan", 10 * 60, 11 * 60, emptySet())

        assertEquals(emptySet<String>(), overlappingTriggerIds(listOf(work, orphan)))
    }

    // --- the next boundary, as the list shows it ------------------------------

    @Test
    fun `the next boundary is the coming start`() {
        assertEquals(
            "Mon 09:00",
            boundaryLabel(work.nextBoundary(monday.at("08:00"))!!, clock),
        )
    }

    @Test
    fun `standing on a start boundary, the next one shown is the end`() {
        assertEquals(
            "Mon 17:00",
            boundaryLabel(work.nextBoundary(monday.at("09:00"))!!, clock),
        )
    }

    @Test
    fun `a weekdays-only schedule skips the weekend`() {
        assertEquals(
            "Mon 09:00",
            boundaryLabel(work.nextBoundary(saturday.at("12:00"))!!, clock),
        )
    }

    @Test
    fun `a wrapping schedule's end boundary is labelled with the following day`() {
        assertEquals(
            "Tue 07:00",
            boundaryLabel(nightly.nextBoundary(monday.at("23:30"))!!, clock),
        )
    }

    @Test
    fun `a schedule that runs on no days has no next boundary`() {
        assertNull(work.copy(daysOfWeek = emptySet()).nextBoundary(monday.at("08:00")))
    }

    @Test
    fun `the label reads the same whichever schedule asks, only the instant differs`() {
        val friday1700 = work.nextBoundary(friday.at("16:00"))
        assertNotNull(friday1700)
        assertEquals("Fri 17:00", boundaryLabel(friday1700!!, clock))
    }

    // --- labels ---------------------------------------------------------------

    @Test
    fun `recognisable day sets are named rather than spelled out`() {
        assertEquals(DaySet.EVERY_DAY, daySetOf(setOf(1, 2, 3, 4, 5, 6, 7)))
        assertEquals(DaySet.WEEKDAYS, daySetOf(setOf(1, 2, 3, 4, 5)))
        assertEquals(DaySet.WEEKENDS, daySetOf(setOf(6, 7)))
        assertEquals(DaySet.NONE, daySetOf(emptySet()))
        assertEquals(DaySet.CUSTOM, daySetOf(setOf(1, 3, 5)))
        assertEquals(DaySet.CUSTOM, daySetOf(setOf(1, 2, 3, 4, 5, 6)))
    }

    @Test
    fun `custom day sets are listed in week order, not set order`() {
        assertEquals("Mon, Wed, Fri", daysLabel(setOf(5, 1, 3), clock))
    }

    @Test
    fun `times follow the device's hour setting, not the locale's default`() {
        assertEquals("00:00", timeLabel(0, clock))
        assertEquals("09:00", timeLabel(9 * 60, clock))
        assertEquals("23:59", timeLabel(23 * 60 + 59, clock))
    }

    @Test
    fun `the same minute renders 12-hour when the device setting says so`() {
        // The regression this pins: the list used ofLocalizedTime(SHORT) while the
        // Material picker followed the setting, so a time entered on a 24-hour dial on an
        // en_US phone read back as "9:00 AM". One source of truth now — the setting.
        assertEquals("9:00 AM", timeLabel(9 * 60, clock12))
        assertEquals("5:00 PM", timeLabel(17 * 60, clock12))
        assertEquals("12:00 AM", timeLabel(0, clock12))
        assertEquals("12:30 PM", timeLabel(12 * 60 + 30, clock12))
    }

    @Test
    fun `the next-boundary label follows the hour setting too`() {
        assertEquals("Mon 9:00 AM", boundaryLabel(work.nextBoundary(monday.at("08:00"))!!, clock12))
    }

    @Test
    fun `midnight tomorrow renders as midnight, not as an hour that does not exist`() {
        // The writer normalises 1440 to 0, but a row from elsewhere could hold either and
        // LocalTime.of(24, 0) throws.
        assertEquals("00:00", timeLabel(24 * 60, clock))
    }

    @Test
    fun `day toggles carry a full name behind their initial`() {
        // The narrow name repeats across days in several locales ("M T W T F S S"), which
        // is why the full name is what a screen reader gets.
        assertEquals("Monday", dayName(1, clock))
        assertEquals("Sunday", dayName(7, clock))
    }

    // --- decoding rows for display -------------------------------------------

    @Test
    fun `rows keep their trigger even when the params cannot be decoded`() {
        val good = scheduleTrigger("schedule-good", "mode-work", 540, 1020, setOf(1))
        val bad = TriggerEntity(
            id = "schedule-bad",
            modeId = "mode-work",
            type = TriggerType.SCHEDULE,
            paramsJson = "{",
        )

        val rows = listOf(good, bad).toScheduleRows()

        assertEquals(2, rows.size)
        assertNotNull("a decodable row has a window", rows[0].window)
        assertNull("an undecodable row is kept so it can be deleted", rows[1].window)
        assertEquals("schedule-bad", rows[1].trigger.id)
    }

    @Test
    fun `the list is ordered by start time, not by trigger id`() {
        // The bug this pins cost a wrong deletion during device testing: the DAO orders by
        // primary key, and a generated "schedule-<uuid>" sorts before a seeded
        // "trigger-work-weekdays" regardless of what time either one starts.
        val evening = scheduleTrigger("trigger-work-weekdays", "mode-work", 23 * 60, 7 * 60, setOf(1))
        val morning = scheduleTrigger("schedule-9f3c", "mode-work", 7 * 60, 9 * 60, setOf(1))

        val ordered = listOf(evening, morning).toScheduleRows().sortedForDisplay()

        assertEquals(listOf("schedule-9f3c", "trigger-work-weekdays"), ordered.map { it.trigger.id })
    }

    @Test
    fun `unreadable schedules sink to the bottom of the list`() {
        val readable = scheduleTrigger("schedule-ok", "mode-work", 23 * 60, 7 * 60, setOf(1))
        val broken = TriggerEntity(
            id = "schedule-aaa",
            modeId = "mode-work",
            type = TriggerType.SCHEDULE,
            paramsJson = "{",
        )

        val ordered = listOf(broken, readable).toScheduleRows().sortedForDisplay()

        assertEquals(listOf("schedule-ok", "schedule-aaa"), ordered.map { it.trigger.id })
    }

    // --- helpers --------------------------------------------------------------

    private fun window(name: String, start: Int, end: Int, days: Set<Int>) = ScheduleWindow(
        triggerId = "trigger-$name",
        modeId = "mode-$name",
        startMinuteOfDay = start,
        endMinuteOfDay = end,
        daysOfWeek = days,
    )

    /** ISO day (1 = Monday) plus a clock time, as an offset from Monday 00:00. */
    private fun weekMinute(day: Int, hour: Int, minute: Int) =
        (day - 1) * 24 * 60 + hour * 60 + minute

    private fun LocalDate.at(time: String): ZonedDateTime =
        ZonedDateTime.of(this, LocalTime.parse(time), zone)
}
