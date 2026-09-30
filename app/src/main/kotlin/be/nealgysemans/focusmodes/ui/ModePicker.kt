package be.nealgysemans.focusmodes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import be.nealgysemans.focusmodes.R
import be.nealgysemans.focusmodes.tile.TileMode

/**
 * What the row — or cell — for the mode that is *already on* means.
 *
 * One choice rather than the two booleans this replaced (`activeValueLabel` and
 * `activeTapTurnsOff`). They were never independent: a row that reads "On" has to be a
 * switch, and a row that only ticks has nothing for a tap on it to undo. Two flags that
 * must agree are two flags that can disagree, and the pair `(false, true)` in particular
 * would have drawn a tick on a row that silently turned the mode off.
 */
enum class ActiveRowStyle {

    /**
     * A **selection**: the active entry is marked with a checkmark, and tapping it is a
     * no-op re-selection. What the Quick Settings tile's dialog does — one row must always
     * be the chosen one, and "Off" is a row like any other.
     */
    TICK_SELECT,

    /**
     * A **switch**: the active entry says "On" where there is room for a word, and tapping
     * it reports null, i.e. turns the mode off. iOS labels the Focus that is on rather than
     * ticking it — a value, not a selection — and the word also says what a tap will undo.
     *
     * [ModeGrid] takes this too, and there the word is the only part that does not apply: a
     * square cell has no room for one, so being on is drawn as the mode's own colour
     * instead. The tap semantics are what the style is really about.
     */
    ON_SWITCH,
}

/**
 * The picker, as a list of rows — the dialog the Quick Settings tile shows, and the card
 * the home-screen widget's chevron opens.
 *
 * Rows rather than a grid here: the tile's dialog sits inside the collapsed shade with the
 * status bar and the user's thumb both in the way, and a vertical list of full-width
 * targets is the one layout that stays hittable there. It is also the shape of iOS's own
 * Focus selector, which is why `widget/FocusPickerActivity` reuses it rather than the
 * grid. The long-press surface, which owns the whole screen, uses [ModeGrid] instead.
 *
 * Selecting a mode reports its id; selecting "Off" reports null. The caller decides
 * what that means — this composable never touches the engine, so the same body works
 * from a Service-hosted dialog and from an Activity.
 *
 * @param activeRowStyle whether the active row is a selection or a switch. Defaults to
 *   [ActiveRowStyle.TICK_SELECT], which is what the tile's dialog wants and what keeps its
 *   behaviour unchanged by anything the widget's picker needed.
 * @param onOpenSettings the one row that is not a mode, pinned to the bottom. Null omits
 *   it, which is what the tile's dialog wants — the tile has its own way into the app.
 */
@Composable
fun ModePickerSheet(
    modes: List<TileMode>,
    activeModeId: String?,
    onPick: (String?) -> Unit,
    modifier: Modifier = Modifier,
    activeRowStyle: ActiveRowStyle = ActiveRowStyle.TICK_SELECT,
    onOpenSettings: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(vertical = 20.dp)) {
            Text(
                text = stringResource(R.string.picker_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.size(12.dp))
            val isSwitch = activeRowStyle == ActiveRowStyle.ON_SWITCH
            modes.forEach { mode ->
                val selected = mode.id == activeModeId
                PickerRow(
                    label = mode.name,
                    glyphRes = mode.glyphRes,
                    accent = Color(mode.color),
                    selected = selected,
                    valueLabel = isSwitch,
                    onClick = { onPick(if (selected && isSwitch) null else mode.id) },
                )
            }
            PickerRow(
                label = stringResource(R.string.picker_off),
                glyphRes = ModeGlyphs.OFF_RES,
                accent = MaterialTheme.colorScheme.onSurfaceVariant,
                selected = activeModeId == null,
                // Never the value label, even under [ActiveRowStyle.ON_SWITCH]: "Off … On"
                // is nonsense. This row is a selection whatever the others are, so it keeps
                // the tick — and tapping it already reports null, which is the whole point.
                valueLabel = false,
                onClick = { onPick(null) },
            )
            if (onOpenSettings != null) {
                // A hairline above it, because this row leaves the picker and the ones
                // above it commit inside it — without the seam it reads as a fifth mode.
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                PickerRow(
                    label = stringResource(R.string.picker_settings),
                    glyphRes = R.drawable.ic_settings,
                    accent = MaterialTheme.colorScheme.onSurfaceVariant,
                    selected = false,
                    valueLabel = false,
                    onClick = onOpenSettings,
                )
            }
        }
    }
}

@Composable
private fun PickerRow(
    label: String,
    glyphRes: Int,
    accent: Color,
    selected: Boolean,
    valueLabel: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphBadge(glyphRes = glyphRes, accent = accent, filled = selected)
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (selected) {
            Spacer(Modifier.width(8.dp))
            if (valueLabel) {
                Text(
                    text = stringResource(R.string.picker_on),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = stringResource(R.string.picker_selected),
                    tint = accent,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * The picker, as a grid of square cells — the long-press (`QS_TILE_PREFERENCES`)
 * surface.
 *
 * Modelled on what press-and-hold gives you on iOS: the control itself, larger, with
 * every option visible at once and one tap to commit. Two columns, laid out
 * eagerly rather than with a lazy grid, because there are a handful of modes and a
 * lazy grid inside a floating card fights its own measurement.
 *
 * @param activeRowStyle what a tap on the cell that is already on does. Required rather
 *   than defaulted, and required rather than hard-coded here as it used to be: whether a
 *   control is a switch or a selector is the surface's decision, not the grid's.
 */
@Composable
fun ModeGrid(
    modes: List<TileMode>,
    activeModeId: String?,
    onPick: (String?) -> Unit,
    onOpenApp: () -> Unit,
    activeRowStyle: ActiveRowStyle,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 4.dp,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(R.string.picker_title),
                style = MaterialTheme.typography.titleLarge,
            )

            val isSwitch = activeRowStyle == ActiveRowStyle.ON_SWITCH
            modes.chunked(GRID_COLUMNS).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { mode ->
                        val selected = mode.id == activeModeId
                        GridCell(
                            label = mode.name,
                            glyphRes = mode.glyphRes,
                            accent = Color(mode.color),
                            selected = selected,
                            modifier = Modifier.weight(1f),
                            onClick = { onPick(if (selected && isSwitch) null else mode.id) },
                        )
                    }
                    // Keep the last row's cells the same width as every other row's.
                    repeat(GRID_COLUMNS - row.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }

            GridCell(
                label = stringResource(R.string.picker_off),
                glyphRes = ModeGlyphs.OFF_RES,
                accent = MaterialTheme.colorScheme.onSurfaceVariant,
                selected = activeModeId == null,
                modifier = Modifier.fillMaxWidth(),
                onClick = { onPick(null) },
            )

            TextButton(onClick = onOpenApp, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.action_open_app))
            }
        }
    }
}

@Composable
private fun GridCell(
    label: String,
    glyphRes: Int,
    accent: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SelectableSwatch(
        shape = RoundedCornerShape(20.dp),
        selected = selected,
        accent = accent,
        onClick = onClick,
        modifier = modifier.heightIn(min = 96.dp),
        contentPadding = 12.dp,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                painter = painterResource(glyphRes),
                contentDescription = null,
                tint = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * One tappable cell in a "pick one of these" row or grid, selected or not.
 *
 * The presentation is the app's *tinted-and-ringed* selection language: a selected swatch is
 * filled with its own accent at [SELECTED_FILL_ALPHA] and ringed in that accent at full
 * strength, an unselected one is a plain `surfaceContainerHighest` tile with no ring. Two
 * sites drew exactly that by hand — the editor's glyph picker and the long-press grid's mode
 * cells — with the fill alpha differing by 0.02 between them for no reason anyone chose.
 *
 * Shape, sizing and content stay the caller's, because those genuinely differ: a 36dp circle
 * for a glyph, a 96dp-tall rounded square for a mode. What is shared is the part that has to
 * look like one decision.
 *
 * Deliberately **not** used by the editor's colour swatches or the schedule dialog's day
 * toggles, which look like this and are not: a colour swatch is filled with the colour it
 * *is* and marked with a heavy neutral ring, and a day toggle is filled solid when on and
 * outlined when off — the inverse of the rule here. Forcing either through this would need
 * enough parameters to express three policies, which is a configuration object pretending to
 * be a component.
 *
 * @param contentPadding inset between the ring and [content]. Zero for a swatch whose content
 *   is already sized to sit inside it.
 */
@Composable
internal fun SelectableSwatch(
    shape: Shape,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                if (selected) {
                    accent.copy(alpha = SELECTED_FILL_ALPHA)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
            )
            .border(
                width = if (selected) SELECTED_RING_WIDTH else 0.dp,
                color = if (selected) accent else Color.Transparent,
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/**
 * A mode's glyph in a tinted circle.
 *
 * Shared by the picker, the grid and the mode list so "this is Sleep" looks the same
 * everywhere. [filled] is what marks the active one: a solid accent disc, which reads
 * at a glance where a checkmark alone does not.
 */
@Composable
fun GlyphBadge(
    glyphRes: Int,
    accent: Color,
    filled: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(percent = 50))
            .background(if (filled) accent else accent.copy(alpha = IDLE_FILL_ALPHA)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(glyphRes),
            contentDescription = null,
            tint = if (filled) MaterialTheme.colorScheme.surface else accent,
            modifier = Modifier.size(size * GLYPH_TO_BADGE_RATIO),
        )
    }
}

/**
 * How much of a glyph badge the glyph itself takes.
 *
 * Shared with `widget/FocusWidget`'s chip, which is the same presentation drawn in Glance
 * — so "the chips look alike" is a fact about one number rather than a hope about two.
 */
internal const val GLYPH_TO_BADGE_RATIO = 0.55f

private const val GRID_COLUMNS = 2

/**
 * How strongly a selected [SelectableSwatch] is tinted with its own accent, and how thick its
 * ring is. One pair for every site, which is the point — the editor's glyph picker used 0.20
 * and the grid 0.18, a difference nobody chose and nobody could see side by side because the
 * two are never on screen together.
 */
private const val SELECTED_FILL_ALPHA = 0.18f
private val SELECTED_RING_WIDTH = 2.dp

private const val IDLE_FILL_ALPHA = 0.16f
