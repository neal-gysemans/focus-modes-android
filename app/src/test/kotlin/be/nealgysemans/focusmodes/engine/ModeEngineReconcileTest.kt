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
        val fixture = testEngine(clock, listOf(work, sleep))
        engine = fixture.engine
        zen = fixture.zen
        store = fixture.store
        schedules = fixture.schedules
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

    // --- the snooze the platform applies after a user turns a mode off -------
    //
    // `ZenAdapter.activate` reports what the *system* says afterwards, because a
    // SOURCE_SCHEDULE activation is silently refused while the rule is snoozed. The
    // engine does not consult that answer yet; these two tests pin what that means so
    // the gap is visible rather than folklore.

    @Test
    fun `a refused schedule activation is still recorded, and reconcile keeps re-asserting it`() {
        zen.refuseActivation = true
        schedules.desiredModeId = sleep.id

        assertEquals(Transition.Activated(sleep.id, ActivationSource.SCHEDULE), engine.reconcile())
        assertEquals(
            "known gap: the engine trusts its own write rather than the system's answer",
            sleep.id,
            store.state.activeModeId,
        )

        zen.clearCalls()
        engine.reconcile()
        assertEquals(
            "healDrift retries with the schedule source, which the snooze keeps refusing",
            listOf(FakeZenAdapter.Call.Activate(sleep.id, ActivationSource.SCHEDULE)),
            zen.stateCalls,
        )
    }

    @Test
    fun `clearing the pin after a system-side off stops reconcile from resurrecting the mode`() {
        engine.onEvent(TriggerEvent(ActivationSource.USER, work.id, Direction.ACTIVATE))
        // The user turned the mode off from a system surface: the rule is inactive and
        // the platform has snoozed it, while the app is still pinned.
        zen.forceInactive(work.id)
        zen.clearCalls()

        engine.reconcile()

        assertEquals(
            "a pinned mode re-asserts as SOURCE_USER_ACTION, the one source that overrides a " +
                "snooze — so zen/ZenStatusReceiver has to feed the user's system-side off back " +
                "in as a DEACTIVATE, or the app resurrects a mode the user just turned off",
            listOf(FakeZenAdapter.Call.Activate(work.id, ActivationSource.USER)),
            zen.stateCalls,
        )

        // Exactly what the receiver does with AUTOMATIC_RULE_STATUS_DEACTIVATED.
        engine.onEvent(TriggerEvent(ActivationSource.USER, work.id, Direction.DEACTIVATE))
        zen.clearCalls()
        engine.reconcile()

        assertTrue("the pin is gone", store.state.isIdle)
        assertEquals(
            "and nothing is re-asserted afterwards",
            emptyList<FakeZenAdapter.Call>(),
            zen.stateCalls,
        )
    }
}
