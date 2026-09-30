package be.nealgysemans.focusmodes.tile

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
 * @property lastUsedModeId the mode an off-tap turns on. Persisted by
 *   [TilePreferences], updated whenever any surface activates a mode.
 * @property alwaysAsk user preference: tap always opens the picker instead of
 *   re-activating [lastUsedModeId].
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
    val alwaysAsk: Boolean = false,
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
 * Resolve a tap against this snapshot.
 *
 * Priority, in order:
 *  1. Nothing to do at all (no grant, no modes) → [TileTap.Blocked].
 *  2. Something is on → turn *that* off. An on-tile always means "off"; a tile that
 *     sometimes switched modes on a plain tap would be unpredictable.
 *  3. The user asked to always be asked → [TileTap.Ask].
 *  4. A last-used mode exists → re-activate it. This is the iOS Control Center
 *     behaviour: the common tap costs one touch, not two.
 *  5. Exactly one mode exists → no point asking.
 *  6. Otherwise → [TileTap.Ask].
 */
fun TileSnapshot.tap(): TileTap {
    if (!dndGranted || modes.isEmpty()) return TileTap.Blocked
    activeMode?.let { return TileTap.Deactivate(it.id) }
    if (alwaysAsk) return TileTap.Ask
    lastUsedMode?.let { return TileTap.Activate(it.id) }
    return modes.singleOrNull()?.let { TileTap.Activate(it.id) } ?: TileTap.Ask
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
