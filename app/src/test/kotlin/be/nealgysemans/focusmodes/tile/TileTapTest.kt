package be.nealgysemans.focusmodes.tile

import be.nealgysemans.focusmodes.engine.ActivationSource
import be.nealgysemans.focusmodes.engine.Direction
import be.nealgysemans.focusmodes.engine.TriggerEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
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

    @Before
    fun resetCache() = TileStateCache.resetForTest()

    private val work = TileMode(id = "work", name = "Work", glyphRes = 1, color = 0x1)
    private val sleep = TileMode(id = "sleep", name = "Sleep", glyphRes = 2, color = 0x2)
    private val gym = TileMode(id = "gym", name = "Gym", glyphRes = 3, color = 0x3)

    /**
     * `modes` is in the order the user arranged, because that is what the DAO returns
     * (`ORDER BY sort_order, name`) and what [TapBehavior.CYCLE] steps through.
     */
    private fun snapshot(
        modes: List<TileMode> = listOf(work, sleep),
        activeModeId: String? = null,
        lastUsedModeId: String? = null,
        tapBehavior: TapBehavior = TapBehavior.LAST_USED,
        dndGranted: Boolean = true,
    ) = TileSnapshot(
        modes = modes,
        activeModeId = activeModeId,
        lastUsedModeId = lastUsedModeId,
        tapBehavior = tapBehavior,
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

    @Test
    fun `blocked names the two reasons, and only those`() {
        // Three surfaces branch on this one predicate — [tap] returns Blocked, the tile paints
        // STATE_UNAVAILABLE and the widget replaces its card — so it is pinned on its own
        // rather than only through [tap]'s answer.
        assertTrue("no grant", snapshot(dndGranted = false).blocked)
        assertTrue("no modes", snapshot(modes = emptyList()).blocked)
        assertTrue("neither", snapshot(modes = emptyList(), dndGranted = false).blocked)
        assertFalse("a grant and a mode is enough", snapshot().blocked)
    }

    @Test
    fun `blocked does not depend on anything being on`() {
        assertFalse(snapshot(activeModeId = work.id).blocked)
        assertFalse(snapshot(activeModeId = null, lastUsedModeId = null).blocked)
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
        assertEquals(
            TileTap.Ask,
            snapshot(lastUsedModeId = work.id, tapBehavior = TapBehavior.ALWAYS_ASK).tap(),
        )
    }

    @Test
    fun `always ask does not stop a tap from turning the active mode off`() {
        // Otherwise the picker would be the only way off, which is two taps to undo one.
        assertEquals(
            TileTap.Deactivate(work.id),
            snapshot(activeModeId = work.id, tapBehavior = TapBehavior.ALWAYS_ASK).tap(),
        )
    }

    // --- cycle ---------------------------------------------------------------
    //
    // The behaviour the spike tile had and daily use asked for back: off → first → next
    // → … → last → off, in the user's sort order, never a dialog. The ring is closed
    // through off deliberately — a cycling tile that could not be turned off in one tap
    // would fail the one thing every tile has to do.

    private fun cycling(activeModeId: String?) = snapshot(
        modes = listOf(work, sleep, gym),
        activeModeId = activeModeId,
        // Set, and deliberately never the mode the assertions expect: cycling must step
        // from where the ring is, not from where the user last was.
        lastUsedModeId = gym.id,
        tapBehavior = TapBehavior.CYCLE,
    )

    @Test
    fun `cycling from off turns on the first mode in sort order`() {
        assertEquals(TileTap.Activate(work.id), cycling(activeModeId = null).tap())
    }

    @Test
    fun `cycling steps to the next mode in sort order`() {
        assertEquals(TileTap.Activate(sleep.id), cycling(activeModeId = work.id).tap())
        assertEquals(TileTap.Activate(gym.id), cycling(activeModeId = sleep.id).tap())
    }

    @Test
    fun `cycling past the last mode turns everything off`() {
        assertEquals(TileTap.Deactivate(gym.id), cycling(activeModeId = gym.id).tap())
    }

    @Test
    fun `cycling ignores the last used mode`() {
        // The whole ring, from off and back to off, never visits gym early even though it
        // is the last-used mode. A cycle that started from last-used would not be a cycle.
        assertEquals(TileTap.Activate(work.id), cycling(activeModeId = null).tap())
        assertEquals(TileTap.Activate(sleep.id), cycling(activeModeId = work.id).tap())
    }

    @Test
    fun `cycling never opens the picker`() {
        // Not even in the cases LAST_USED asks in: no last-used mode at all, and several
        // modes to choose from. Asking would defeat the point of picking "cycle".
        assertEquals(
            TileTap.Activate(work.id),
            snapshot(lastUsedModeId = null, tapBehavior = TapBehavior.CYCLE).tap(),
        )
    }

    @Test
    fun `cycling with one mode is a plain toggle`() {
        val single = listOf(work)
        assertEquals(
            TileTap.Activate(work.id),
            snapshot(modes = single, tapBehavior = TapBehavior.CYCLE).tap(),
        )
        assertEquals(
            TileTap.Deactivate(work.id),
            snapshot(modes = single, activeModeId = work.id, tapBehavior = TapBehavior.CYCLE).tap(),
        )
    }

    @Test
    fun `cycling restarts when the active id names a deleted mode`() {
        // Deactivating it would be ignored by the engine as UNKNOWN_MODE, so treating it
        // as "off" is what keeps the ring reachable instead of stuck on a ghost.
        assertEquals(TileTap.Activate(work.id), cycling(activeModeId = "deleted").tap())
    }

    @Test
    fun `cycling still cannot act without DND access`() {
        assertEquals(
            TileTap.Blocked,
            snapshot(tapBehavior = TapBehavior.CYCLE, dndGranted = false).tap(),
        )
    }

    @Test
    fun `cycling with no modes cannot act`() {
        assertEquals(
            TileTap.Blocked,
            snapshot(modes = emptyList(), tapBehavior = TapBehavior.CYCLE).tap(),
        )
    }

    // --- the stored preference ----------------------------------------------

    @Test
    fun `tap behaviour is resolved by name so reordering the enum is safe`() {
        TapBehavior.entries.forEach { behaviour ->
            assertEquals(behaviour, TapBehavior.ofName(behaviour.name))
        }
    }

    @Test
    fun `an absent or unknown stored behaviour falls back to last used`() {
        // A database or DataStore restored from a newer build can name a behaviour this
        // build does not have; falling back beats crashing the tile's warm-up.
        assertEquals(TapBehavior.LAST_USED, TapBehavior.ofName(null))
        assertEquals(TapBehavior.LAST_USED, TapBehavior.ofName("TEACH_ME_TO_MEDITATE"))
    }

    // --- migrating the boolean this setting used to be -----------------------
    //
    // The only place an upgrading user's setting can change without them touching it,
    // so the rule is pinned here rather than left to a read-through in TilePreferences.

    @Test
    fun `a stored alwaysAsk of true becomes always ask`() {
        assertEquals(
            TapBehavior.ALWAYS_ASK,
            tapBehaviorFrom(storedName = null, legacyAlwaysAsk = true),
        )
    }

    @Test
    fun `a stored alwaysAsk of false becomes last used`() {
        assertEquals(
            TapBehavior.LAST_USED,
            tapBehaviorFrom(storedName = null, legacyAlwaysAsk = false),
        )
    }

    @Test
    fun `a store that was never written becomes last used`() {
        assertEquals(
            TapBehavior.LAST_USED,
            tapBehaviorFrom(storedName = null, legacyAlwaysAsk = null),
        )
    }

    @Test
    fun `a chosen behaviour outranks a leftover alwaysAsk`() {
        // Choosing a behaviour clears the old key, but a store written by a build in
        // between could still hold both — and the explicit choice has to win.
        assertEquals(
            TapBehavior.CYCLE,
            tapBehaviorFrom(storedName = "CYCLE", legacyAlwaysAsk = true),
        )
        // Including when the name is one this build does not know: falling back to the
        // default is right, resurrecting the retired boolean is not.
        assertEquals(
            TapBehavior.LAST_USED,
            tapBehaviorFrom(storedName = "SOMETHING_NEWER", legacyAlwaysAsk = true),
        )
    }

    // --- the widget's own button --------------------------------------------
    //
    // The widget shows one named mode and toggles it, whatever the *tile's* preference
    // says — so these assertions are the point: a cycling tile must not turn the widget
    // into a cycling button.

    @Test
    fun `the widget button toggles last used regardless of tap behaviour`() {
        TapBehavior.entries.forEach { behaviour ->
            assertEquals(
                "off-tap under $behaviour",
                TileTap.Activate(sleep.id),
                snapshot(lastUsedModeId = sleep.id, tapBehavior = behaviour).toggleLastUsed(),
            )
            assertEquals(
                "on-tap under $behaviour",
                TileTap.Deactivate(work.id),
                snapshot(
                    activeModeId = work.id,
                    lastUsedModeId = work.id,
                    tapBehavior = behaviour,
                ).toggleLastUsed(),
            )
        }
    }

    @Test
    fun `the widget button asks when there is nothing to re-activate`() {
        // "Ask" from the widget's toggle zone means "start FocusPickerActivity" — the same
        // place its chevron zone goes. There is no dialog for a RemoteViews click to show,
        // so a translucent activity is the picker.
        assertEquals(TileTap.Ask, snapshot(lastUsedModeId = null).toggleLastUsed())
    }

    @Test
    fun `the widget button is blocked without DND access`() {
        assertEquals(
            TileTap.Blocked,
            snapshot(lastUsedModeId = work.id, dndGranted = false).toggleLastUsed(),
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

    // --- a pick from a picker ------------------------------------------------
    //
    // Shared by the tile's dialog, the tile's long-press grid and the widget's picker. The
    // user has already said which mode, so unlike [tap] there is nothing to infer and no
    // preference that can change the answer — which is exactly why all three can share it.

    @Test
    fun `picking a mode activates it`() {
        assertEquals(TileTap.Activate(sleep.id), snapshot().pick(sleep.id))
    }

    @Test
    fun `picking a mode while another is on still just activates it`() {
        // The engine's single-active rule takes the outgoing one down; the picker does not
        // have to say so, and must not send two events.
        assertEquals(
            TileTap.Activate(sleep.id),
            snapshot(activeModeId = work.id).pick(sleep.id),
        )
    }

    @Test
    fun `picking the mode that is already on re-activates it rather than toggling`() {
        // Turning off is the "Off" row's job. A pick names a mode, and naming the mode that
        // is on cannot mean "off" — the engine drops it as ALREADY_IN_DESIRED_STATE.
        assertEquals(
            TileTap.Activate(work.id),
            snapshot(activeModeId = work.id).pick(work.id),
        )
    }

    @Test
    fun `picking Off deactivates whatever is on`() {
        assertEquals(
            TileTap.Deactivate(work.id),
            snapshot(activeModeId = work.id).pick(null),
        )
    }

    @Test
    fun `picking Off while nothing is on is nothing to do`() {
        // Null, not Blocked and not Ask: the picker always offers "Off" including when it is
        // already the selected row, and there is no mode to name in an event.
        assertNull(snapshot().pick(null))
    }

    @Test
    fun `a pick ignores the tap behaviour entirely`() {
        // The whole point of [pick] being separate from [tap]: CYCLE and ALWAYS_ASK are about
        // resolving an ambiguous gesture, and a pick is not ambiguous.
        TapBehavior.entries.forEach { behaviour ->
            assertEquals(
                "under $behaviour",
                TileTap.Activate(sleep.id),
                snapshot(activeModeId = work.id, tapBehavior = behaviour).pick(sleep.id),
            )
        }
    }

    @Test
    fun `an active id naming a deleted mode still deactivates on an Off pick`() {
        // Deliberately unlike [tap], which treats a deleted active mode as off. Here the
        // engine will reject the id as UNKNOWN_MODE, which is the same outcome as doing
        // nothing — and reporting the id is what keeps the picker from having to know.
        assertEquals(
            TileTap.Deactivate("deleted"),
            snapshot(activeModeId = "deleted").pick(null),
        )
    }

    // --- a tap as an engine event --------------------------------------------

    @Test
    fun `the two taps that name a mode become USER events`() {
        assertEquals(
            TriggerEvent(ActivationSource.USER, work.id, Direction.ACTIVATE),
            TileTap.Activate(work.id).toUserEvent(),
        )
        assertEquals(
            TriggerEvent(ActivationSource.USER, work.id, Direction.DEACTIVATE),
            TileTap.Deactivate(work.id).toUserEvent(),
        )
    }

    @Test
    fun `the two taps that open a surface are not events`() {
        // Ask and Blocked are answered with a window, not with a state change. Null here is
        // what stops a caller submitting something the engine would have to invent a mode for.
        assertNull(TileTap.Ask.toUserEvent())
        assertNull(TileTap.Blocked.toUserEvent())
    }

    // --- the stale-read clobber ----------------------------------------------
    //
    // On-device regression: a snapshot read issued by onStartListening could complete
    // *after* a tap had flipped the tile, publish its pre-tap contents, and revert the
    // tile to the mode the user had just switched off — permanently, because nothing
    // repainted afterwards. A speculative read may only ever seed an empty cache.

    @Test
    fun `a speculative read cannot overwrite an optimistic flip`() {
        val readInFlight = snapshot(activeModeId = work.id, lastUsedModeId = work.id)
        TileStateCache.publish(readInFlight)

        TileStateCache.flipTo(null)
        val accepted = TileStateCache.primeIfCold(readInFlight)

        assertFalse("a warm cache must reject a speculative read", accepted)
        assertEquals(null, TileStateCache.value.activeModeId)
    }

    @Test
    fun `a speculative read seeds a cold cache`() {
        val accepted = TileStateCache.primeIfCold(snapshot(activeModeId = sleep.id))

        assertTrue("a cold cache must accept the first read", accepted)
        assertTrue(TileStateCache.warm)
        assertEquals(sleep.id, TileStateCache.value.activeModeId)
    }

    @Test
    fun `engine truth is published to the render flow`() {
        // The tile renders by collecting this flow, so a publish that does not reach it
        // is a tile that never repaints while the shade is open.
        TileStateCache.publish(snapshot(activeModeId = work.id))

        assertEquals(work.id, TileStateCache.snapshots.value.activeModeId)
    }
}
