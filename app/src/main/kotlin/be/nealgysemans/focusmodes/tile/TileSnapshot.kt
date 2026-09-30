package be.nealgysemans.focusmodes.tile

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
 * Process-wide warm copy of [TileSnapshot].
 *
 * `TileService.onClick()` has a hard latency budget before the shade feels broken,
 * and a DataStore read inside it costs tens of milliseconds on a cold page cache.
 * So the durable stores are mirrored here: warmed in `onStartListening` (which the
 * platform always calls before `onClick`), refreshed by `notification/SurfaceSync`
 * on every state change from any surface, and read with a single volatile load on
 * the click path.
 *
 * Writes are whole-value replacements of an immutable snapshot, so a reader either
 * sees the old state or the new one — never a half-updated one — without a lock.
 */
object TileStateCache {

    @Volatile
    var value: TileSnapshot = TileSnapshot()
        private set

    /** True once a real read has landed. False means [value] is still the empty default. */
    @Volatile
    var warm: Boolean = false
        private set

    fun publish(snapshot: TileSnapshot) {
        value = snapshot
        warm = true
    }

    /**
     * Apply a tap's expected outcome locally, before the engine has confirmed it.
     *
     * The engine is the authority and `SurfaceSync` will overwrite this within
     * milliseconds; this exists so that a *second* tap arriving before the first one
     * lands still sees the state the user can see on screen.
     */
    fun flipTo(modeId: String?) {
        val current = value
        value = current.copy(
            activeModeId = modeId,
            lastUsedModeId = modeId ?: current.lastUsedModeId,
        )
    }
}
