package be.nealgysemans.focusmodes.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The priority policy. These are the rules that decide whether the phone is quiet,
 * so they are pinned down here rather than left to emerge from the call sites.
 */
class ModeEnginePriorityTest {

    private val work = testMode("work")
    private val sleep = testMode("sleep")

    private lateinit var zen: FakeZenAdapter
    private lateinit var store: FakeActiveStateStore
    private lateinit var schedules: FakeScheduleSource
    private lateinit var engine: ModeEngine

    private val clock: Clock = Clock.fixed(Instant.parse("2026-09-30T10:00:00Z"), ZoneOffset.UTC)

    @Before
    fun setUp() {
        zen = FakeZenAdapter()
        store = FakeActiveStateStore()
        schedules = FakeScheduleSource()
        engine = ModeEngine(
            clock = clock,
            catalog = FakeModeCatalog(listOf(work, sleep)),
            schedules = schedules,
            state = store,
            zen = zen,
        )
    }

    // --- manual beats schedule ---------------------------------------------

    @Test
    fun `user activation pins the mode and records the user source`() {
        val transition = engine.onEvent(userActivate(work.id))

        assertEquals(Transition.Activated(work.id, ActivationSource.USER), transition)
        assertEquals(work.id, store.state.activeModeId)
        assertTrue("a user activation must pin", store.state.pinnedByUser)
        assertEquals(ActivationSource.USER, store.state.source)
        assertEquals(clock.millis(), store.state.since)
    }

    @Test
    fun `schedule activation of another mode is ignored while the user has pinned one`() {
        engine.onEvent(userActivate(work.id))
        zen.clearCalls()

        val scheduleEvent = TriggerEvent(ActivationSource.SCHEDULE, sleep.id, Direction.ACTIVATE)
        val transition = engine.onEvent(scheduleEvent)

        assertEquals(Transition.Ignored(scheduleEvent, IgnoreReason.USER_PIN_HOLDS), transition)
        assertEquals("the pinned mode must survive", work.id, store.state.activeModeId)
        assertEquals("nothing may touch the phone", emptyList<FakeZenAdapter.Call>(), zen.stateCalls)
    }

    @Test
    fun `schedule deactivation of the pinned mode is ignored`() {
        engine.onEvent(userActivate(work.id))
        zen.clearCalls()

        // The pin is what stops "my focus turned itself off at 17:00" — the
        // schedule's end boundary must not release a mode the user turned on.
        val scheduleEvent = TriggerEvent(ActivationSource.SCHEDULE, work.id, Direction.DEACTIVATE)
        val transition = engine.onEvent(scheduleEvent)

        assertEquals(Transition.Ignored(scheduleEvent, IgnoreReason.USER_PIN_HOLDS), transition)
        assertEquals(work.id, store.state.activeModeId)
        assertEquals(emptyList<FakeZenAdapter.Call>(), zen.stateCalls)
    }

    @Test
    fun `context triggers yield to a user pin just like schedules do`() {
        engine.onEvent(userActivate(work.id))

        val contextEvent = TriggerEvent(ActivationSource.CONTEXT, sleep.id, Direction.ACTIVATE)

        assertEquals(
            Transition.Ignored(contextEvent, IgnoreReason.USER_PIN_HOLDS),
            engine.onEvent(contextEvent),
        )
    }

    @Test
    fun `user deactivation clears the pin so a schedule can take over again`() {
        engine.onEvent(userActivate(work.id))
        engine.onEvent(TriggerEvent(ActivationSource.USER, work.id, Direction.DEACTIVATE))

        assertTrue(store.state.isIdle)
        assertFalse(store.state.pinnedByUser)

        val transition = engine.onEvent(
            TriggerEvent(ActivationSource.SCHEDULE, sleep.id, Direction.ACTIVATE),
        )

        assertEquals(Transition.Activated(sleep.id, ActivationSource.SCHEDULE), transition)
        assertEquals(sleep.id, store.state.activeModeId)
        assertFalse("a schedule must never pin", store.state.pinnedByUser)
    }

    @Test
    fun `a user tap on an already-scheduled mode upgrades it to a pin`() {
        engine.onEvent(TriggerEvent(ActivationSource.SCHEDULE, work.id, Direction.ACTIVATE))
        assertFalse(store.state.pinnedByUser)

        engine.onEvent(userActivate(work.id))

        assertTrue(store.state.pinnedByUser)
        assertEquals(ActivationSource.USER, store.state.source)
    }

    // --- single active mode ------------------------------------------------

    @Test
    fun `activating a mode deactivates the one that was on`() {
        engine.onEvent(userActivate(work.id))
        zen.clearCalls()

        val transition = engine.onEvent(userActivate(sleep.id))

        assertEquals(
            Transition.Activated(sleep.id, ActivationSource.USER, listOf(work.id)),
            transition,
        )
        assertEquals(sleep.id, store.state.activeModeId)
        assertEquals(
            "the outgoing mode must go off before the incoming one comes on",
            listOf(
                FakeZenAdapter.Call.Deactivate(work.id, ActivationSource.USER),
                FakeZenAdapter.Call.Activate(sleep.id, ActivationSource.USER),
            ),
            zen.stateCalls,
        )
        assertFalse("the displaced mode must not still be active", zen.isActive(work.id))
        assertTrue(zen.isActive(sleep.id))
    }

    @Test
    fun `re-activating the already active mode is a no-op`() {
        engine.onEvent(userActivate(work.id))
        zen.clearCalls()

        val event = userActivate(work.id)
        assertEquals(
            Transition.Ignored(event, IgnoreReason.ALREADY_IN_DESIRED_STATE),
            engine.onEvent(event),
        )
        assertEquals(emptyList<FakeZenAdapter.Call>(), zen.stateCalls)
    }

    @Test
    fun `deactivating a mode that is not on is a no-op`() {
        val event = TriggerEvent(ActivationSource.USER, sleep.id, Direction.DEACTIVATE)

        assertEquals(
            Transition.Ignored(event, IgnoreReason.ALREADY_IN_DESIRED_STATE),
            engine.onEvent(event),
        )
    }

    @Test
    fun `an event for a deleted mode is ignored rather than crashing`() {
        val event = userActivate("deleted-mode")

        assertEquals(
            Transition.Ignored(event, IgnoreReason.UNKNOWN_MODE),
            engine.onEvent(event),
        )
        assertTrue(store.state.isIdle)
    }

    private fun userActivate(modeId: String) =
        TriggerEvent(ActivationSource.USER, modeId, Direction.ACTIVATE)
}
