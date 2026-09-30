package be.nealgysemans.focusmodes.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the widget's wide layouts divide themselves up, and when a mode stops being
 * reachable from the widget at all.
 *
 * Pinned here because it is the one piece of the widget that is policy rather than
 * drawing, and because getting it wrong is invisible in a screenshot of a three-mode
 * setup: the failure mode is a user with nine modes whose last two were squeezed to
 * nothing with no way to reach them. [gridPlan] is a pure function of the size bracket
 * and the mode count precisely so this is a JVM test.
 *
 * The sizes below are the two wide brackets `FocusWidget.sizeMode` declares. They are
 * literals rather than references because the brackets are private to the widget — and
 * because the point of the assertions is that *these* two shapes behave as designed, not
 * that the function agrees with whatever the constants currently say.
 */
class GridPlanTest {

    /** The `ROW_SIZE` bracket: 4x1, one line of buttons. */
    private val row = DpSize(220.dp, 48.dp)

    /** The `GRID_SIZE` bracket: 4x2 and up, two lines. */
    private val grid = DpSize(220.dp, 110.dp)

    // --- no overflow: every mode gets a cell ---------------------------------

    @Test
    fun `a short row gives one cell per mode up to four`() {
        for (modeCount in 1..4) {
            val plan = gridPlan(row, modeCount)
            assertEquals("columns for $modeCount", modeCount, plan.columns)
            assertEquals("cells for $modeCount", modeCount, plan.cells)
            assertFalse("no overflow for $modeCount", plan.overflow)
        }
    }

    @Test
    fun `a tall grid holds two lines of four`() {
        for (modeCount in 5..8) {
            val plan = gridPlan(grid, modeCount)
            assertEquals("columns for $modeCount", 4, plan.columns)
            assertEquals("cells for $modeCount", modeCount, plan.cells)
            assertFalse("no overflow for $modeCount", plan.overflow)
        }
    }

    @Test
    fun `a tall grid with few modes still puts them all on one line`() {
        // Three modes in a 4x2 widget are three cells, not three above nothing: the
        // columns are capped by the mode count, so the line has no holes in it.
        val plan = gridPlan(grid, 3)
        assertEquals(3, plan.columns)
        assertEquals(3, plan.cells)
        assertFalse(plan.overflow)
    }

    // --- overflow: the last cell becomes the chevron -------------------------

    @Test
    fun `a fifth mode on one line spends the last cell on the chevron`() {
        val plan = gridPlan(row, 5)
        assertTrue(plan.overflow)
        assertEquals(4, plan.columns)
        // Three modes drawn, not five squeezed into four cells' worth of width.
        assertEquals(3, plan.cells)
    }

    @Test
    fun `a ninth mode in the grid spends the last cell on the chevron`() {
        val plan = gridPlan(grid, 9)
        assertTrue(plan.overflow)
        assertEquals(4, plan.columns)
        assertEquals(7, plan.cells)
    }

    @Test
    fun `overflow never eats the last line's only cell`() {
        // The chevron takes one cell of the last line, so `cells` has to leave that line
        // with at least one mode on it — otherwise a line of three modes plus a chevron
        // would render as a chevron under a full line, which looks like a bug.
        for (modeCount in 5..40) {
            listOf(row, grid).forEach { size ->
                val plan = gridPlan(size, modeCount)
                if (plan.overflow) {
                    val lastLine = plan.cells % plan.columns
                    assertTrue(
                        "$modeCount in $size left ${plan.cells} cells over ${plan.columns}",
                        lastLine == plan.columns - 1,
                    )
                }
            }
        }
    }

    @Test
    fun `every mode is either drawn or behind the chevron`() {
        // The invariant the whole thing exists for: a plan that draws fewer modes than
        // exist must say so, because `overflow` is what puts a way to reach them on the
        // widget. A silent truncation is the bug this replaced.
        for (modeCount in 1..40) {
            listOf(row, grid).forEach { size ->
                val plan = gridPlan(size, modeCount)
                assertEquals(
                    "$modeCount in $size",
                    plan.cells < modeCount,
                    plan.overflow,
                )
            }
        }
    }

    // --- degenerate ----------------------------------------------------------

    @Test
    fun `no modes is a plan with no cells and nothing hidden`() {
        // Unreachable in practice — the card short-circuits to MainActivity before any
        // wide layout composes — but `columns` feeds a chunk size, and a zero there is
        // an exception rather than an empty grid.
        val plan = gridPlan(grid, 0)
        assertEquals(1, plan.columns)
        assertEquals(0, plan.cells)
        assertFalse(plan.overflow)
    }
}
