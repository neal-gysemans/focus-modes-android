package be.nealgysemans.focusmodes.data

import be.nealgysemans.focusmodes.EVERY_DAY
import be.nealgysemans.focusmodes.WEEKDAY_DAYS
import be.nealgysemans.focusmodes.engine.ScheduleWindow
import be.nealgysemans.focusmodes.schedule.toWindowOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seam between the schedule editor and the engine.
 *
 * `params_json` has two halves in two packages: [scheduleParamsJson] writes it from the
 * UI, and `schedule/TriggerScheduleSource.toWindowOrNull` reads it back on every
 * reconcile. They are the only place in the app where a feature can break without any
 * code failing — a writer that emits `"from"` instead of `"start"` compiles, runs, saves,
 * and produces a schedule that silently never fires. Nothing at runtime would say so:
 * the reader drops undecodable rows on purpose, because one corrupt row must not stop
 * the whole reconcile.
 *
 * So the round trip is asserted through the **real** reader, not a copy of it. That is
 * also why `org.json` is on the unit-test classpath: android.jar ships it as stubs that
 * throw, which would make every decode here fail into `runCatching` and pass a test
 * that proves nothing.
 *
 * Two of these tests are about characters rather than values. The seeded rows in [Seed]
 * are raw SQL string literals that no Kotlin function produced, so "the writer agrees
 * with the reader" is not enough — the writer also has to agree with what is already in
 * every installed database.
 */
class ScheduleParamsTest {

    // --- the exact bytes ------------------------------------------------------

    @Test
    fun `the encoded shape matches the documented schema exactly`() {
        assertEquals(
            """{"start":540,"end":1020,"days":[1,2,3,4,5]}""",
            scheduleParamsJson(
                startMinuteOfDay = 9 * 60,
                endMinuteOfDay = 17 * 60,
                daysOfWeek = setOf(1, 2, 3, 4, 5),
            ),
        )
    }

    @Test
    fun `the encoder reproduces the seeded rows character for character`() {
        // If this fails, an existing install's schedules and a newly created one are two
        // different formats sharing one column.
        assertTrue(
            "seeded Work schedule",
            Seed.TRIGGERS.any {
                it.contains(scheduleParamsJson(540, 1020, setOf(1, 2, 3, 4, 5)))
            },
        )
        assertTrue(
            "seeded Sleep schedule, which wraps midnight",
            Seed.TRIGGERS.any {
                it.contains(scheduleParamsJson(1380, 420, setOf(1, 2, 3, 4, 5, 6, 7)))
            },
        )
    }

    @Test
    fun `days are sorted so two equal schedules encode identically`() {
        assertEquals(
            scheduleParamsJson(0, 60, setOf(1, 2, 3)),
            scheduleParamsJson(0, 60, setOf(3, 1, 2)),
        )
    }

    // --- round trips ----------------------------------------------------------

    @Test
    fun `a plain window survives the round trip`() {
        val decoded = roundTrip(startMinuteOfDay = 9 * 60, endMinuteOfDay = 17 * 60, days = WEEKDAY_DAYS)

        assertEquals(9 * 60, decoded?.startMinuteOfDay)
        assertEquals(17 * 60, decoded?.endMinuteOfDay)
        assertEquals(WEEKDAY_DAYS, decoded?.daysOfWeek)
    }

    @Test
    fun `a midnight-wrapping window survives the round trip`() {
        val decoded = roundTrip(startMinuteOfDay = 23 * 60, endMinuteOfDay = 7 * 60, days = EVERY_DAY)

        assertEquals(23 * 60, decoded?.startMinuteOfDay)
        assertEquals(7 * 60, decoded?.endMinuteOfDay)
        assertEquals(EVERY_DAY, decoded?.daysOfWeek)
    }

    @Test
    fun `a single-day window survives the round trip`() {
        assertEquals(setOf(7), roundTrip(0, 8 * 60, setOf(7))?.daysOfWeek)
    }

    @Test
    fun `the first and last minute of the day survive the round trip`() {
        val decoded = roundTrip(startMinuteOfDay = 0, endMinuteOfDay = 1439, days = WEEKDAY_DAYS)

        assertEquals(0, decoded?.startMinuteOfDay)
        assertEquals(1439, decoded?.endMinuteOfDay)
    }

    @Test
    fun `the trigger and mode ids come through on the decoded window`() {
        val trigger = scheduleTrigger(
            id = "schedule-abc",
            modeId = "mode-work",
            startMinuteOfDay = 60,
            endMinuteOfDay = 120,
            daysOfWeek = setOf(1),
        )
        val window = trigger.toWindowOrNull()

        assertEquals("schedule-abc", window?.triggerId)
        assertEquals("mode-work", window?.modeId)
    }

    // --- normalising and refusing ---------------------------------------------

    @Test
    fun `midnight tomorrow is stored as zero, which reads as until midnight`() {
        // 1440 is not representable in a 0..1439 field, and 0 is not a loss: an end of 0
        // is already "wraps to the next day at minute zero", i.e. runs until midnight.
        assertEquals(
            scheduleParamsJson(9 * 60, 0, WEEKDAY_DAYS),
            scheduleParamsJson(9 * 60, 24 * 60, WEEKDAY_DAYS),
        )
    }

    @Test
    fun `days outside Monday to Sunday are dropped, not clamped`() {
        assertEquals(
            """{"start":0,"end":60,"days":[1,7]}""",
            scheduleParamsJson(0, 60, setOf(0, 1, 7, 8, -3)),
        )
    }

    @Test
    fun `an empty day set encodes and decodes as a schedule that never fires`() {
        val decoded = roundTrip(startMinuteOfDay = 540, endMinuteOfDay = 600, days = emptySet())

        assertEquals("""{"start":540,"end":600,"days":[]}""", encode(540, 600, emptySet()))
        assertEquals(emptySet<Int>(), decoded?.daysOfWeek)
    }

    @Test
    fun `an unreadable row decodes to null rather than throwing`() {
        // The editor relies on this: a row it cannot decode is shown as unreadable with a
        // delete button, which only works if the reader hands back null.
        assertNull(triggerWith("not json at all").toWindowOrNull())
        assertNull(triggerWith("""{"start":540,"end":600}""").toWindowOrNull())
        assertNull(triggerWith("""{"end":600,"days":[1]}""").toWindowOrNull())
        assertNull(triggerWith("").toWindowOrNull())
    }

    // --- helpers --------------------------------------------------------------

    private fun encode(start: Int, end: Int, days: Set<Int>) =
        scheduleParamsJson(start, end, days)

    private fun roundTrip(
        startMinuteOfDay: Int,
        endMinuteOfDay: Int,
        days: Set<Int>,
    ): ScheduleWindow? = scheduleTrigger(
        id = "schedule-test",
        modeId = "mode-test",
        startMinuteOfDay = startMinuteOfDay,
        endMinuteOfDay = endMinuteOfDay,
        daysOfWeek = days,
    ).toWindowOrNull()

    private fun triggerWith(paramsJson: String) = TriggerEntity(
        id = "schedule-broken",
        modeId = "mode-test",
        type = TriggerType.SCHEDULE,
        paramsJson = paramsJson,
    )
}
