package be.nealgysemans.focusmodes.engine

import be.nealgysemans.focusmodes.zen.ZenAdapter
import java.time.Clock
import java.time.ZonedDateTime

/**
 * In-memory [ZenAdapter] that both records calls and tracks resulting state, so a
 * test can assert on the call log *and* on `readBack` seeing the effect.
 */
class FakeZenAdapter : ZenAdapter {

    sealed interface Call {
        data class Ensure(val modeId: String) : Call
        data class Activate(val modeId: String, val source: ActivationSource) : Call
        data class Deactivate(val modeId: String, val source: ActivationSource) : Call
    }

    val calls = mutableListOf<Call>()
    private val activeModes = mutableSetOf<String>()
    private val knownRules = mutableSetOf<String>()

    /** Only the activate/deactivate calls — the ones that change the phone. */
    val stateCalls: List<Call>
        get() = calls.filter { it is Call.Activate || it is Call.Deactivate }

    override fun ensureRule(mode: FocusMode): String {
        calls += Call.Ensure(mode.id)
        knownRules += mode.id
        return "rule-${mode.id}"
    }

    /**
     * Whether the fake should refuse activations, standing in for the platform's
     * snooze: after a user turns a rule off, `STATE_TRUE` from a schedule is
     * silently ignored and the real adapter reports false from its read-back.
     */
    var refuseActivation = false

    override fun activate(modeId: String, source: ActivationSource): Boolean {
        calls += Call.Activate(modeId, source)
        if (refuseActivation && source != ActivationSource.USER) return false
        activeModes += modeId
        return true
    }

    override fun deactivate(modeId: String, source: ActivationSource): Boolean {
        calls += Call.Deactivate(modeId, source)
        activeModes -= modeId
        return true
    }

    override fun readBack(modeId: String): Boolean? {
        if (modeId !in knownRules) return null
        return modeId in activeModes
    }

    /**
     * Whether the fake believes the mode is on.
     *
     * Separate from [readBack], which deliberately returns null for a mode whose
     * rule was never ensured — that is the real adapter's behaviour and tests of
     * the `onEvent` path (which does not ensure rules) rely on it. [readBack] answering
     * null there rather than false is exactly what `healDrift`'s `!= true` is written for.
     */
    fun isActive(modeId: String): Boolean = modeId in activeModes

    /** Simulate the system dropping a rule's state behind the app's back. */
    fun forceInactive(modeId: String) {
        activeModes -= modeId
    }

    fun clearCalls() = calls.clear()
}

/** [ActiveStateStore] over a plain field. */
class FakeActiveStateStore(initial: ActiveState = ActiveState.IDLE) : ActiveStateStore {
    var state: ActiveState = initial
        private set

    override fun read(): ActiveState = state

    override fun write(state: ActiveState) {
        this.state = state
    }
}

/** [ModeCatalog] over a fixed list. */
class FakeModeCatalog(private val modes: List<FocusMode>) : ModeCatalog {
    override fun modes(): List<FocusMode> = modes
}

/** [ScheduleSource] that answers with whatever the test sets. */
class FakeScheduleSource(var desiredModeId: String? = null) : ScheduleSource {
    override fun modeIdActiveAt(now: ZonedDateTime): String? = desiredModeId

    override fun nextBoundaryAfter(now: ZonedDateTime): ZonedDateTime? = null
}

/** Minimal mode fixture; only the id matters to the priority policy. */
fun testMode(id: String): FocusMode = FocusMode(
    id = id,
    name = id.replaceFirstChar(Char::uppercase),
    iconKey = "moon",
    color = 0,
    callsFrom = PeopleFilter.STARRED,
    messagesFrom = PeopleFilter.STARRED,
    repeatCallers = true,
    effects = ModeEffects(),
)

/**
 * A [ModeEngine] over fresh fakes, with the fakes handed back so a test can drive and
 * inspect them.
 *
 * The two engine test classes had the same eighteen-line `@Before` — four `lateinit` fields
 * and a five-argument constructor call — differing only in the fixed instant. Kept here as a
 * function returning the fakes rather than as a base class the tests extend: inheritance
 * would hide which fake a test is talking to, and these tests are entirely about what the
 * fakes recorded.
 *
 * @param clock the fixed instant the engine reads `now` and `since` from.
 * @param modes the catalog. Anything not in here is an `UNKNOWN_MODE` to the engine.
 */
fun testEngine(clock: Clock, modes: List<FocusMode>): TestEngine {
    val zen = FakeZenAdapter()
    val store = FakeActiveStateStore()
    val schedules = FakeScheduleSource()
    return TestEngine(
        engine = ModeEngine(
            clock = clock,
            catalog = FakeModeCatalog(modes),
            schedules = schedules,
            state = store,
            zen = zen,
        ),
        zen = zen,
        store = store,
        schedules = schedules,
    )
}

/** An engine and the three fakes behind it. */
class TestEngine(
    val engine: ModeEngine,
    val zen: FakeZenAdapter,
    val store: FakeActiveStateStore,
    val schedules: FakeScheduleSource,
)
