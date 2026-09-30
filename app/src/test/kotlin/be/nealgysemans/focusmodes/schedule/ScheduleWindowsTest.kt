package be.nealgysemans.focusmodes.schedule

import be.nealgysemans.focusmodes.engine.ScheduleWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The clock arithmetic every schedule depends on.
 *
 * Two things are pinned here. First, midnight-wrapping windows ("23:00 to 07:00"),
 * where an off-by-one-day bug shows up as a phone that stays silent all morning —
 * and where the day-of-week set applies to the day the window *starts*, not the day
 * it ends. Second, that the next-boundary alarm is only armed for days the window
 * actually runs, so a weekdays-only mode does not wake the device on Sunday.
 *
 * Dates are real: 2026-09-28 is a Monday, 2026-10-03 a Saturday.
 */
class ScheduleWindowsTest {

    private val zone: ZoneId = ZoneId.of("Europe/Brussels")

    private val monday = LocalDate.parse("2026-09-28")
    private val tuesday = LocalDate.parse("2026-09-29")
    private val friday = LocalDate.parse("2026-10-02")
    private val saturday = LocalDate.parse("2026-10-03")
    private val sunday = LocalDate.parse("2026-10-04")

    /** Weekdays 09:00-17:00. */
    private val work = ScheduleWindow(
        triggerId = "trigger-work",
        modeId = "work",
        startMinuteOfDay = 9 * 60,
        endMinuteOfDay = 17 * 60,
        daysOfWeek = setOf(1, 2, 3, 4, 5),
    )

    /** Every night 23:00-07:00. */
    private val nightly = ScheduleWindow(
        triggerId = "trigger-sleep",
        modeId = "sleep",
        startMinuteOfDay = 23 * 60,
        endMinuteOfDay = 7 * 60,
        daysOfWeek = setOf(1, 2, 3, 4, 5, 6, 7),
    )

    /** Friday night only — the case that separates "starts on" from "runs on". */
    private val fridayNight = nightly.copy(daysOfWeek = setOf(5))

    // --- which mode is on right now ------------------------------------------

    @Test
    fun `a plain window covers its own day between start and end`() {
        val windows = listOf(work)

        assertNull("08:59 is before the window", windows.modeIdActiveAt(monday.at("08:59")))
        assertEquals("work", windows.modeIdActiveAt(monday.at("09:00")))
        assertEquals("work", windows.modeIdActiveAt(monday.at("16:59")))
        assertNull("the end minute is exclusive", windows.modeIdActiveAt(monday.at("17:00")))
    }

    @Test
    fun `a plain window ignores days it does not run on`() {
        assertNull(listOf(work).modeIdActiveAt(saturday.at("10:00")))
    }

    @Test
    fun `a wrapping window covers both sides of midnight`() {
        val windows = listOf(nightly)

        assertNull(windows.modeIdActiveAt(monday.at("22:59")))
        assertEquals("sleep", windows.modeIdActiveAt(monday.at("23:30")))
        assertEquals("sleep", windows.modeIdActiveAt(tuesday.at("06:59")))
        assertNull(windows.modeIdActiveAt(tuesday.at("07:00")))
    }

    @Test
    fun `a wrapping window's day set names the day it starts on`() {
        val windows = listOf(fridayNight)

        assertNull("Friday evening before the start", windows.modeIdActiveAt(friday.at("22:00")))
        assertEquals("sleep", windows.modeIdActiveAt(friday.at("23:30")))
        assertEquals(
            "Saturday morning still belongs to Friday's window",
            "sleep",
            windows.modeIdActiveAt(saturday.at("06:00")),
        )
        assertNull(
            "Saturday night is a different day and not in the set",
            windows.modeIdActiveAt(saturday.at("23:30")),
        )
    }

    @Test
    fun `overlapping windows resolve to the last one`() {
        val focus = work.copy(triggerId = "trigger-focus", modeId = "focus")

        assertEquals("focus", listOf(work, focus).modeIdActiveAt(monday.at("10:00")))
        assertEquals("work", listOf(focus, work).modeIdActiveAt(monday.at("10:00")))
    }

    // --- when to wake up next ------------------------------------------------

    @Test
    fun `no windows means no alarm to arm`() {
        assertNull(emptyList<ScheduleWindow>().nextBoundaryAfter(monday.at("10:00")))
    }

    @Test
    fun `the next boundary is the coming start`() {
        assertEquals(monday.at("09:00"), listOf(work).nextBoundaryAfter(monday.at("08:00")))
    }

    @Test
    fun `a boundary exactly now is already behind us`() {
        assertEquals(
            "standing on the start boundary, the next one is the end",
            monday.at("17:00"),
            listOf(work).nextBoundaryAfter(monday.at("09:00")),
        )
    }

    @Test
    fun `boundaries skip days the window does not run on`() {
        assertEquals(
            "after Friday's end the next weekday boundary is Monday morning",
            LocalDate.parse("2026-10-05").at("09:00"),
            listOf(work).nextBoundaryAfter(friday.at("17:30")),
        )
    }

    @Test
    fun `a wrapping window's end boundary lands on the following day`() {
        assertEquals(tuesday.at("07:00"), listOf(nightly).nextBoundaryAfter(monday.at("23:30")))
    }

    @Test
    fun `a weekly window is still found a week out`() {
        assertEquals(
            LocalDate.parse("2026-10-09").at("23:00"),
            listOf(fridayNight).nextBoundaryAfter(saturday.at("08:00")),
        )
    }

    @Test
    fun `the earliest boundary across all windows wins`() {
        assertEquals(
            monday.at("23:00"),
            listOf(work, nightly).nextBoundaryAfter(monday.at("22:00")),
        )
    }

    @Test
    fun `a window that runs on no days never fires`() {
        val orphan = work.copy(daysOfWeek = emptySet())

        assertNull(listOf(orphan).nextBoundaryAfter(monday.at("08:00")))
        assertNull(listOf(orphan).modeIdActiveAt(monday.at("10:00")))
        assertNull(listOf(orphan).modeIdActiveAt(sunday.at("10:00")))
    }

    private fun LocalDate.at(time: String): ZonedDateTime =
        ZonedDateTime.of(this, LocalTime.parse(time), zone)
}
