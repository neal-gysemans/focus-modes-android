package be.nealgysemans.focusmodes.tile

import be.nealgysemans.focusmodes.engine.ActivationSource
import be.nealgysemans.focusmodes.engine.Direction
import be.nealgysemans.focusmodes.engine.TriggerEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A mode flattened to exactly what a tile or a picker needs to draw it.
 *
 * Not `FocusMode` and not `ModeEntity`: the tile's click path must not touch a type
 * that could tempt it into a database read, and it has no use for people filters or
 * device effects. Four fields, all already resolved.
 *
 * @property glyphRes resolved from `iconKey` by `ui/ModeGlyphs` off the hot path.
 */
data class TileMode(
    val id: String,
    val name: String,
    val glyphRes: Int,
    val color: Int,
)

/**
 * What a plain tap on the Quick Settings tile does.
 *
 * Replaces the earlier `alwaysAsk` boolean. It was a boolean because there were two
 * answers; daily use produced a third — cycling, which the spike tile had and which
 * the shipped tile lost — and "cycle" is not a shade of "ask", so the type had to grow
 * rather than gain a second flag. Persisted by [name] (see `TilePreferences`), never by
 * ordinal: reordering these constants must not silently change a user's setting.
 */
enum class TapBehavior {

    /** Re-activate [TileSnapshot.lastUsedModeId]. The iOS Control Center behaviour. */
    LAST_USED,

    /** Always open the picker, for users who never want an implicit choice made. */
    ALWAYS_ASK,

    /**
     * Step through the modes in sort order: off → first → next → … → last → off.
     *
     * One tap per step and never a dialog, which is what makes a two-or-three-mode
     * setup usable from a shade the user is already halfway through closing.
     */
    CYCLE,
    ;

    companion object {

        /** The behaviour named [name], or [LAST_USED] for anything unrecognised. */
        fun ofName(name: String?): TapBehavior =
            entries.firstOrNull { it.name == name } ?: LAST_USED
    }
}

/**
 * Everything the Quick Settings tile needs to render and to decide what a tap means,
 * in one immutable value.
 *
 * This type is deliberately free of Android imports. Spike #2 measured tap→flip at
 * 2 ms on HyperOS by making `onClick()` read *only* a pre-warmed in-memory snapshot
 * — no DataStore, no SQLite, no permission probe — so the shape of the snapshot is
 * what enforces that discipline: if a field is not in here, the click path cannot
 * ask for it.
 *
 * Being pure Kotlin also makes [tap] unit-testable on the JVM, which matters because
 * "which mode does a tap turn on" is policy, not plumbing.
 *
 * @property modes every defined mode, already in the user's sort order — which is
 *   what makes [TapBehavior.CYCLE] deterministic and what the widget's grid draws.
 * @property lastUsedModeId the mode an off-tap turns on. Persisted by
 *   [TilePreferences], updated whenever any surface activates a mode.
 * @property tapBehavior user preference: what a plain tap means.
 * @property dndGranted notification policy access. False makes the tile
 *   `STATE_UNAVAILABLE` rather than letting it claim to arm something it cannot.
 * @property activeSince epoch millis of the current activation, for the ongoing
 *   notification's chronometer.
 */
data class TileSnapshot(
    val modes: List<TileMode> = emptyList(),
    val activeModeId: String? = null,
    val activeSince: Long = 0L,
    val lastUsedModeId: String? = null,
    val tapBehavior: TapBehavior = TapBehavior.LAST_USED,
    val dndGranted: Boolean = false,
) {
    /** The active mode, or null when idle *or* when the active id names a deleted mode. */
    val activeMode: TileMode? get() = modes.firstOrNull { it.id == activeModeId }

    val lastUsedMode: TileMode? get() = modes.firstOrNull { it.id == lastUsedModeId }
}

/**
 * What one tap on the tile should do, decided from a snapshot alone.
 *
 * Modelled as a value so the decision is separable from the side effects: the tile
 * computes this, flips itself optimistically, and only then dispatches.
 */
sealed interface TileTap {

    /** Turn [modeId] on. */
    data class Activate(val modeId: String) : TileTap

    /** Turn [modeId] off. */
    data class Deactivate(val modeId: String) : TileTap

    /** Ask which mode — no last-used mode, or the user asked to always be asked. */
    data object Ask : TileTap

    /**
     * The tile cannot act: DND access is missing, or there are no modes to turn on.
     * The surface should send the user somewhere they can fix it, not fail silently.
     */
    data object Blocked : TileTap
}

/**
 * True when no surface can arm anything from this snapshot: notification policy access is
 * missing, or the user has not defined a mode to turn on.
 *
 * Named once because three surfaces branch on it and each drew its own conclusion from the
 * same two fields — [tap] returns [TileTap.Blocked], `FocusTileService` paints
 * `STATE_UNAVAILABLE`, and the widget replaces its whole card with a way into the app. All
 * three are the same question, and all three must answer it the same way or a tile that
 * says it is unavailable still dispatches.
 *
 * Both halves are fixable, and only in the app — which is why every surface's response is
 * to send the user there rather than to fail quietly.
 */
val TileSnapshot.blocked: Boolean get() = !dndGranted || modes.isEmpty()

/**
 * Resolve a tap against this snapshot.
 *
 * Nothing to do at all (no grant, no modes) is [TileTap.Blocked] whatever the
 * preference says; past that the answer is [TapBehavior]'s, and the three branches are
 * deliberately different shapes rather than one parameterised rule:
 *
 *  - [TapBehavior.CYCLE] is the only one where a tap on an *on* tile can turn something
 *    else on, so it owns the whole decision (see [cycleTap]).
 *  - [TapBehavior.ALWAYS_ASK] and [TapBehavior.LAST_USED] share "on means off": a tile
 *    that sometimes switched modes on a plain tap would be unpredictable, and making the
 *    picker the only way off would cost two taps to undo one.
 *  - Past that, ask-always asks; last-used re-activates, which is the iOS Control Center
 *    behaviour — the common tap costs one touch, not two — and falls back to asking when
 *    there is no last-used mode left to re-activate (unless there is only one mode, in
 *    which case there is nothing to ask about).
 */
fun TileSnapshot.tap(): TileTap {
    if (blocked) return TileTap.Blocked
    if (tapBehavior == TapBehavior.CYCLE) return cycleTap()
    activeMode?.let { return TileTap.Deactivate(it.id) }
    if (tapBehavior == TapBehavior.ALWAYS_ASK) return TileTap.Ask
    lastUsedMode?.let { return TileTap.Activate(it.id) }
    return modes.singleOrNull()?.let { TileTap.Activate(it.id) } ?: TileTap.Ask
}

/**
 * One step of `off → first → next → … → last → off`, in [TileSnapshot.modes] order.
 *
 * `modes` arrives sorted by the user's `sort_order` (see `ModeDao.observeModes`), so
 * "next" means next in the list the user sees in the app — not next by id, and not
 * whatever order Room happened to return.
 *
 * Off is a real stop on the ring, not an escape hatch: without it a cycling tile could
 * never be turned off in one tap, which is the one thing every tile must be able to do.
 * [lastUsedModeId] is deliberately ignored — a cycle that started from wherever the user
 * last was would not be a cycle.
 */
private fun TileSnapshot.cycleTap(): TileTap {
    // An activeModeId naming a deleted mode is *off* for cycling purposes: the engine
    // would ignore a deactivate of it as UNKNOWN_MODE, so the ring restarts instead.
    val index = modes.indexOfFirst { it.id == activeModeId }
    if (index < 0) return TileTap.Activate(modes.first().id)
    val next = modes.getOrNull(index + 1) ?: return TileTap.Deactivate(modes[index].id)
    return TileTap.Activate(next.id)
}

/**
 * What the home-screen widget's single "current Focus" button does: off ↔ last used.
 *
 * Deliberately **not** [tap]: [TapBehavior] is the *tile's* preference, chosen for a
 * control in a shade the user is halfway through closing. The widget is a different
 * control on a different surface — it already shows one named mode and, at its wider
 * sizes, a button per mode — so cycling or a dialog there would be surprising rather
 * than helpful. Expressed as a delegation rather than a copy so the branch the widget
 * takes is provably the same one the tile's last-used setting takes, including
 * [TileTap.Blocked] and the "nothing left to re-activate" fallback to [TileTap.Ask].
 */
fun TileSnapshot.toggleLastUsed(): TileTap = copy(tapBehavior = TapBehavior.LAST_USED).tap()

/**
 * What a choice made *in a picker* means: [picked] is a mode id, or null for the "Off" row.
 *
 * Deliberately not [tap] and not [toggleLastUsed]. Those two decide *which* mode a single
 * ambiguous gesture is about; here the user has already said which, so there is nothing
 * left to infer and no preference that could change the answer. What is left is only the
 * direction, and the one case that is neither — which is why this was open-coded as the
 * same three-branch `when` in the tile's dialog, the tile's long-press grid and the
 * widget's picker.
 *
 * @return the toggle to submit, or **null** when "Off" was chosen while nothing was on.
 *   That is a real outcome and not an error: the picker always offers "Off", including when
 *   it is already the selected row, and there is then no mode to name in an event. The
 *   engine would drop it as `ALREADY_IN_DESIRED_STATE` anyway, so the surface just closes.
 */
fun TileSnapshot.pick(picked: String?): TileTap? = when {
    picked != null -> TileTap.Activate(picked)
    activeModeId != null -> TileTap.Deactivate(activeModeId)
    else -> null
}

/**
 * The `USER` trigger this tap asks the engine for.
 *
 * Only [TileTap.Activate] and [TileTap.Deactivate] name a mode. [TileTap.Ask] and
 * [TileTap.Blocked] are answered by opening a surface rather than by changing state, so
 * they map to null — a caller that has one of those has a window to show, not an event to
 * submit.
 */
fun TileTap.toUserEvent(): TriggerEvent? = when (this) {
    is TileTap.Activate -> TriggerEvent(ActivationSource.USER, modeId, Direction.ACTIVATE)
    is TileTap.Deactivate -> TriggerEvent(ActivationSource.USER, modeId, Direction.DEACTIVATE)
    TileTap.Ask, TileTap.Blocked -> null
}

/**
 * Process-wide warm copy of [TileSnapshot], and the tile's render source.
 *
 * Two jobs, and they are the same job:
 *
 *  - **Warm read for the click path.** `TileService.onClick()` has a hard latency
 *    budget before the shade feels broken, and a DataStore read inside it costs tens
 *    of milliseconds on a cold page cache. [value] is a single volatile load.
 *  - **The thing the tile paints from.** [snapshots] is a `StateFlow`, collected by
 *    `FocusTileService` for as long as it is listening. This is what makes the tile
 *    repaint when some *other* surface changes the mode: `requestListeningState` only
 *    helps when the tile is not already listening, so a nudge alone leaves an open
 *    shade showing stale state indefinitely.
 *
 * ## Who may write, and why it matters
 *
 * There is exactly one authoritative writer — `notification/SurfaceSync`, via
 * [publish] — plus the tile's own optimistic [flipTo]. Everything else reads.
 *
 * That rule is not stylistic. It was a bug: the tile used to publish the result of a
 * *speculative* read started in `onStartListening`, which could land after a tap had
 * already flipped the tile and revert it to pre-tap state — a mode that was visibly
 * turned off would spring back to "on" 11 ms later and stay there. A read that began
 * before a write cannot be allowed to overwrite the result of that write, so the only
 * speculative path left ([primeIfCold]) is the one that runs when there is nothing to
 * overwrite.
 */
object TileStateCache {

    private val _snapshots = MutableStateFlow(TileSnapshot())

    /** Live state for the tile to render from. Conflated: equal snapshots do not repaint. */
    val snapshots: StateFlow<TileSnapshot> = _snapshots.asStateFlow()

    /** The current snapshot, for the click path's single volatile read. */
    val value: TileSnapshot get() = _snapshots.value

    /** True once a real read has landed. False means [value] is still the empty default. */
    @Volatile
    var warm: Boolean = false
        private set

    /**
     * Publish engine truth.
     *
     * Only `SurfaceSync` calls this, and only in response to an actual state change it
     * observed — so this value is by construction newer than any optimistic flip that
     * preceded the change.
     */
    fun publish(snapshot: TileSnapshot) {
        _snapshots.value = snapshot
        warm = true
    }

    /**
     * Seed the cache from a direct read, but only while it is still empty.
     *
     * The cold-start case: the tile is listening (or has been tapped) before
     * `SurfaceSync`'s first emission has arrived. Once anything real is in the cache
     * this is a no-op, which is what keeps a slow read from overwriting a fast tap.
     *
     * @return true if the snapshot was taken.
     */
    fun primeIfCold(snapshot: TileSnapshot): Boolean = synchronized(this) {
        if (warm) return false
        _snapshots.value = snapshot
        warm = true
        true
    }

    /**
     * Apply a tap's expected outcome locally, before the engine has confirmed it.
     *
     * The engine is the authority and `SurfaceSync` will overwrite this within
     * milliseconds; this exists so that a *second* tap arriving before the first one
     * lands still sees the state the user can see on screen.
     */
    fun flipTo(modeId: String?) = synchronized(this) {
        val current = _snapshots.value
        _snapshots.value = current.copy(
            activeModeId = modeId,
            lastUsedModeId = modeId ?: current.lastUsedModeId,
        )
        warm = true
    }

    /**
     * Return to the cold, empty state.
     *
     * Exists for tests only: [warm] is sticky by design (it is the flag that stops a
     * stale read from overwriting live state), so without this no test could exercise
     * the cold-start branch twice in one JVM.
     */
    internal fun resetForTest() = synchronized(this) {
        _snapshots.value = TileSnapshot()
        warm = false
    }
}
