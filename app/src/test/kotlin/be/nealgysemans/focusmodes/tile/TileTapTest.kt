package be.nealgysemans.focusmodes.tile

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What one tap on the Quick Settings tile means.
 *
 * Pinned down here because this is policy the user feels directly and cannot see: the
 * tile is icon-only on HyperOS, the decision has to be made in under a millisecond from
 * memory alone, and getting it wrong shows up as "my tile turns on the wrong mode"
 * rather than as a crash. [TileSnapshot] is pure Kotlin precisely so this is a JVM test
 * and not an instrumented one.
 */
class TileTapTest {

    private val work = TileMode(id = "work", name = "Work", glyphRes = 1, color = 0x1)
    private val sleep = TileMode(id = "sleep", name = "Sleep", glyphRes = 2, color = 0x2)

    private fun snapshot(
        modes: List<TileMode> = listOf(work, sleep),
        activeModeId: String? = null,
        lastUsedModeId: String? = null,
        alwaysAsk: Boolean = false,
        dndGranted: Boolean = true,
    ) = TileSnapshot(
        modes = modes,
        activeModeId = activeModeId,
        lastUsedModeId = lastUsedModeId,
        alwaysAsk = alwaysAsk,
        dndGranted = dndGranted,
    )

    // --- blocked -------------------------------------------------------------

    @Test
    fun `without DND access the tile cannot act`() {
        assertEquals(TileTap.Blocked, snapshot(dndGranted = false).tap())
    }

    @Test
    fun `with no modes defined the tile cannot act`() {
        assertEquals(TileTap.Blocked, snapshot(modes = emptyList()).tap())
    }

    // --- on means off --------------------------------------------------------

    @Test
    fun `a tap while a mode is on turns that mode off`() {
        assertEquals(
            TileTap.Deactivate(sleep.id),
            snapshot(activeModeId = sleep.id, lastUsedModeId = work.id).tap(),
        )
    }

    @Test
    fun `an active id naming a deleted mode is not treated as on`() {
        // The mode was deleted while it was active; deactivating it would be ignored by
        // the engine as UNKNOWN_MODE, so the tile must fall through to the off branch.
        assertEquals(
            TileTap.Activate(work.id),
            snapshot(activeModeId = "deleted", lastUsedModeId = work.id).tap(),
        )
    }

    // --- off means last used -------------------------------------------------

    @Test
    fun `a tap while off re-activates the last used mode`() {
        assertEquals(TileTap.Activate(sleep.id), snapshot(lastUsedModeId = sleep.id).tap())
    }

    @Test
    fun `a last used mode that no longer exists falls back to asking`() {
        assertEquals(TileTap.Ask, snapshot(lastUsedModeId = "deleted").tap())
    }

    @Test
    fun `with no last used mode the tile asks`() {
        assertEquals(TileTap.Ask, snapshot().tap())
    }

    @Test
    fun `with a single mode there is nothing to ask about`() {
        assertEquals(TileTap.Activate(work.id), snapshot(modes = listOf(work)).tap())
    }

    // --- always ask ----------------------------------------------------------

    @Test
    fun `always ask outranks the last used mode`() {
        assertEquals(TileTap.Ask, snapshot(lastUsedModeId = work.id, alwaysAsk = true).tap())
    }

    @Test
    fun `always ask does not stop a tap from turning the active mode off`() {
        // Otherwise the picker would be the only way off, which is two taps to undo one.
        assertEquals(
            TileTap.Deactivate(work.id),
            snapshot(activeModeId = work.id, alwaysAsk = true).tap(),
        )
    }

    // --- the optimistic cache ------------------------------------------------

    @Test
    fun `an optimistic flip to a mode also records it as last used`() {
        TileStateCache.publish(snapshot())
        TileStateCache.flipTo(sleep.id)

        assertEquals(sleep.id, TileStateCache.value.activeModeId)
        assertEquals(sleep.id, TileStateCache.value.lastUsedModeId)
    }

    @Test
    fun `an optimistic flip to off keeps the last used mode`() {
        TileStateCache.publish(snapshot(activeModeId = work.id, lastUsedModeId = work.id))
        TileStateCache.flipTo(null)

        assertEquals(null, TileStateCache.value.activeModeId)
        assertEquals(work.id, TileStateCache.value.lastUsedModeId)
    }
}
