package be.nealgysemans.focusmodes.schedule

import be.nealgysemans.focusmodes.EVERY_DAY
import be.nealgysemans.focusmodes.FRIDAY
import be.nealgysemans.focusmodes.MONDAY
import be.nealgysemans.focusmodes.SATURDAY
import be.nealgysemans.focusmodes.SUNDAY
import be.nealgysemans.focusmodes.TUESDAY
import be.nealgysemans.focusmodes.WEEKDAY_DAYS
import be.nealgysemans.focusmodes.at
import be.nealgysemans.focusmodes.engine.ScheduleWindow
import be.nealgysemans.focusmodes.testWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The clock arithmetic every schedule depends on.
 *
 * Three things are pinned here. First, the wrap rule itself — `end <= start`, in one place
 * now, because it had been written four different ways and one of them was inverted. Second,
 * midnight-wrapping windows ("23:00 to 07:00"), where an off-by-one-day bug shows up as a
 * phone that stays silent all morning — and where the day-of-week set applies to the day the
 * window *starts*, not the day it ends. Third, that the next-boundary alarm is only armed for
 * days the window actually runs, so a weekdays-only mode does not wake the device on Sunday.
 *
 * Fixtures come from the shared `ScheduleFixtures`, which `ui/ScheduleRulesTest` also uses:
 * the two files now exercise the *same* predicate through its two callers, and sharing the
 * windows is what makes that comparable.
 *
 * Dates are real: 2026-09-28 is a Monday, 2026-10-03 a Saturday.
 */
class ScheduleWindowsTest {

    private val monday = MONDAY
    private val tuesday = TUESDAY
    private val friday = FRIDAY
    private val saturday = SATURDAY
    private val sunday = SUNDAY

    /** Weekdays 09:00-17:00. */
    private val work = testWindow("trigger-work", "work", 9 * 60, 17 * 60, WEEKDAY_DAYS)

    /** Every night 23:00-07:00. */
    private val nightly = testWindow("trigger-sleep", "sleep", 23 * 60, 7 * 60, EVERY_DAY)

    /** Friday night only — the case that separates "starts on" from "runs on". */
    private val fridayNight = nightly.copy(daysOfWeek = setOf(5))

    // --- the wrap rule itself -------------------------------------------------

    @Test
    fun `a window whose end is after its start does not wrap`() {
        assertFalse(wrapsMidnight(9 * 60, 17 * 60))
        assertFalse(work.wrapsMidnight)
    }

    @Test
    fun `a window whose end is before its start wraps`() {
        assertTrue(wrapsMidnight(23 * 60, 7 * 60))
        assertTrue(nightly.wrapsMidnight)
    }

    @Test
    fun `equal start and end wrap, i e mean a whole day`() {
        // `end <= start`, not `end < start`, and this is the case that separates them. The
        // editor's row label used to get this backwards — it called a 09:00-09:00 window an
        // ordinary same-day range while the engine ran it for 24 hours.
        assertTrue(wrapsMidnight(9 * 60, 9 * 60))
        assertTrue(wrapsMidnight(0, 0))
    }

    @Test
    fun `a whole-day window covers every minute of the days it runs`() {
        val allDay = testWindow("trigger-all", "all", 9 * 60, 9 * 60, EVERY_DAY)
        val windows = listOf(allDay)

        assertEquals("the start minute itself", "all", windows.modeIdActiveAt(monday.at("09:00")))
        assertEquals("later the same day", "all", windows.modeIdActiveAt(monday.at("23:59")))
        assertEquals(
            "and the small hours, which belong to the previous day's occurrence",
            "all",
            windows.modeIdActiveAt(tuesday.at("08:59")),
        )
    }

    // --- the shared predicate, asked directly ---------------------------------

    @Test
    fun `covers treats the end minute as exclusive`() {
        assertTrue(work.covers(day = 1, minute = 16 * 60 + 59, previousDay = 7))
        assertFalse(work.covers(day = 1, minute = 17 * 60, previousDay = 7))
    }

    @Test
    fun `covers reads the previous day it is given, not one it works out`() {
        // This is why [previousDay] is a parameter: the engine derives it from a real date and
        // the UI wraps it within a synthetic week, and both have to be able to answer here.
        assertTrue(
            "Saturday 06:00 is in Friday night's window",
            fridayNight.covers(day = 6, minute = 6 * 60, previousDay = 5),
        )
        assertFalse(
            "the same clock time with a previous day that is not in the set is not",
            fridayNight.covers(day = 6, minute = 6 * 60, previousDay = 4),
        )
    }

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
}
