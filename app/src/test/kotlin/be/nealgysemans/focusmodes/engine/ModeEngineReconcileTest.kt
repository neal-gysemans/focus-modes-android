package be.nealgysemans.focusmodes.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * [ModeEngine.reconcile] is the recovery path: after a reboot, a doze window or an
 * OEM-killed alarm it recomputes from the clock instead of replaying lost events.
 * These tests pin the two properties that makes it safe to call at any time —
 * idempotence, and never overriding a user pin.
 */
class ModeEngineReconcileTest {

    private val work = testMode("work")
    private val sleep = testMode("sleep")

    private lateinit var zen: FakeZenAdapter
    private lateinit var store: FakeActiveStateStore
    private lateinit var schedules: FakeScheduleSource
    private lateinit var engine: ModeEngine

    private val clock: Clock = Clock.fixed(Instant.parse("2026-09-30T23:30:00Z"), ZoneOffset.UTC)

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

    @Test
    fun `reconcile activates the mode the clock says should be on`() {
        schedules.desiredModeId = sleep.id

        val transition = engine.reconcile()

        assertEquals(Transition.Activated(sleep.id, ActivationSource.SCHEDULE), transition)
        assertEquals(sleep.id, store.state.activeModeId)
        assertEquals(ActivationSource.SCHEDULE, store.state.source)
    }

    @Test
    fun `reconcile is idempotent`() {
        schedules.desiredModeId = sleep.id
        engine.reconcile()
        zen.clearCalls()

        assertEquals(Transition.NoChange, engine.reconcile())
        assertEquals(
            "a second pass must not touch the phone",
            emptyList<FakeZenAdapter.Call>(),
            zen.stateCalls,
        )
    }

    @Test
    fun `reconcile turns a scheduled mode off once its window has passed`() {
        schedules.desiredModeId = sleep.id
        engine.reconcile()

        schedules.desiredModeId = null
        val transition = engine.reconcile()

        assertEquals(Transition.Deactivated(sleep.id, ActivationSource.SCHEDULE), transition)
        assertTrue(store.state.isIdle)
    }

    @Test
    fun `reconcile leaves a user-pinned mode alone even when the clock disagrees`() {
        engine.onEvent(TriggerEvent(ActivationSource.USER, work.id, Direction.ACTIVATE))
        schedules.desiredModeId = sleep.id
        zen.clearCalls()

        assertEquals(Transition.NoChange, engine.reconcile())
        assertEquals(work.id, store.state.activeModeId)
        assertTrue(store.state.pinnedByUser)
        assertEquals(emptyList<FakeZenAdapter.Call>(), zen.stateCalls)
    }

    @Test
    fun `reconcile re-asserts a mode the system dropped behind our back`() {
        engine.onEvent(TriggerEvent(ActivationSource.USER, work.id, Direction.ACTIVATE))
        // e.g. the user disabled the rule in Settings, or an OEM cleanup tool did.
        zen.forceInactive(work.id)
        zen.clearCalls()

        assertEquals(Transition.NoChange, engine.reconcile())
        assertEquals(
            listOf(FakeZenAdapter.Call.Activate(work.id, ActivationSource.USER)),
            zen.stateCalls,
        )
    }

    @Test
    fun `reconcile ensures a system rule exists for every mode before deciding`() {
        engine.reconcile()

        assertEquals(
            listOf(
                FakeZenAdapter.Call.Ensure(work.id),
                FakeZenAdapter.Call.Ensure(sleep.id),
            ),
            zen.calls.filterIsInstance<FakeZenAdapter.Call.Ensure>(),
        )
    }

    @Test
    fun `reconcile on a fresh install with no schedules does nothing`() {
        assertEquals(Transition.NoChange, engine.reconcile())
        assertTrue(store.state.isIdle)
        assertEquals(emptyList<FakeZenAdapter.Call>(), zen.stateCalls)
    }
}
