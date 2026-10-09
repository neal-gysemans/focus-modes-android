package be.nealgysemans.focusmodes.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * Turning a scheduled mode off by hand. The bug these pin: the off lasted only until the
 * next reconcile, which saw the window still open and turned the mode straight back on —
 * so the user and the schedule fought in an on/off loop. Off now holds until the user
 * turns the mode back on, or until the next scheduled period.
 */
class ModeEngineDismissTest {

    private val work = testMode("work")
    private val sleep = testMode("sleep")

    // 2026-09-30 is a Wednesday (ISO day 3).
    private val clock = MutableClock(Instant.parse("2026-09-30T10:00:00Z"))

    private lateinit var zen: FakeZenAdapter
    private lateinit var store: FakeActiveStateStore
    private lateinit var schedules: FakeScheduleSource
    private lateinit var engine: ModeEngine

    @Before
    fun setUp() {
        val fixture = testEngine(clock, listOf(work, sleep))
        engine = fixture.engine
        zen = fixture.zen
        store = fixture.store
        schedules = fixture.schedules
        // Work, every day 09:00–17:00.
        schedules.windows = listOf(window("w1", work.id, start = 9 * 60, end = 17 * 60))
    }

    @Test
    fun `a scheduled mode turned off by hand stays off for the rest of its window`() {
        engine.reconcile()
        assertEquals(work.id, store.state.activeModeId)

        engine.onEvent(userDeactivate(work.id))
        zen.clearCalls()

        clock.instant = Instant.parse("2026-09-30T12:00:00Z")
        repeat(3) { assertEquals(Transition.NoChange, engine.reconcile()) }

        assertTrue(store.state.isIdle)
        assertEquals("nothing may turn it back on", emptyList<FakeZenAdapter.Call>(), zen.stateCalls)
    }

    @Test
    fun `the next scheduled period turns it on again`() {
        engine.reconcile()
        engine.onEvent(userDeactivate(work.id))

        clock.instant = Instant.parse("2026-09-30T17:00:00Z")
        assertEquals(Transition.NoChange, engine.reconcile())

        clock.instant = Instant.parse("2026-10-01T09:00:00Z")
        assertEquals(Transition.Activated(work.id, ActivationSource.SCHEDULE), engine.reconcile())
    }

    @Test
    fun `the user can turn a dismissed mode back on, and the schedule then ends it as usual`() {
        engine.reconcile()
        engine.onEvent(userDeactivate(work.id))

        val transition = engine.onEvent(TriggerEvent(ActivationSource.USER, work.id, Direction.ACTIVATE))

        assertEquals(Transition.Activated(work.id, ActivationSource.USER), transition)
        assertEquals("turning it on ends the dismissal", null, store.state.dismissedModeId)
    }

    @Test
    fun `a schedule activation of a dismissed mode is ignored`() {
        engine.reconcile()
        engine.onEvent(userDeactivate(work.id))

        val event = TriggerEvent(ActivationSource.SCHEDULE, work.id, Direction.ACTIVATE)

        assertEquals(Transition.Ignored(event, IgnoreReason.USER_DISMISSED), engine.onEvent(event))
    }

    @Test
    fun `a system-side off of a scheduled mode is a dismissal too`() {
        engine.reconcile()
        // What zen/ZenStatusReceiver feeds in when the user turns the mode off in the shade.
        zen.forceInactive(work.id)
        engine.onEvent(userDeactivate(work.id))
        zen.clearCalls()

        engine.reconcile()

        assertEquals(emptyList<FakeZenAdapter.Call>(), zen.stateCalls)
    }

    @Test
    fun `switching to another mode and back off does not resurrect the dismissed one`() {
        engine.reconcile()
        engine.onEvent(userDeactivate(work.id))

        engine.onEvent(TriggerEvent(ActivationSource.USER, sleep.id, Direction.ACTIVATE))
        engine.onEvent(userDeactivate(sleep.id))

        assertEquals(Transition.NoChange, engine.reconcile())
        assertTrue(store.state.isIdle)
    }

    @Test
    fun `back-to-back windows for the same mode count as one run`() {
        schedules.windows = listOf(
            window("w1", work.id, start = 9 * 60, end = 12 * 60),
            window("w2", work.id, start = 12 * 60, end = 17 * 60),
        )
        engine.reconcile()
        engine.onEvent(userDeactivate(work.id))

        clock.instant = Instant.parse("2026-09-30T13:00:00Z")

        assertEquals(Transition.NoChange, engine.reconcile())
        assertEquals(Instant.parse("2026-09-30T17:00:00Z").toEpochMilli(), store.state.dismissedUntil)
    }

    @Test
    fun `turning off a mode the schedule is not asking for records no dismissal`() {
        engine.onEvent(TriggerEvent(ActivationSource.USER, sleep.id, Direction.ACTIVATE))
        engine.onEvent(userDeactivate(sleep.id))

        assertEquals(null, store.state.dismissedModeId)
    }

    private fun userDeactivate(modeId: String) =
        TriggerEvent(ActivationSource.USER, modeId, Direction.DEACTIVATE)

    private fun window(id: String, modeId: String, start: Int, end: Int) = ScheduleWindow(
        triggerId = id,
        modeId = modeId,
        startMinuteOfDay = start,
        endMinuteOfDay = end,
        daysOfWeek = (1..7).toSet(),
    )
}
