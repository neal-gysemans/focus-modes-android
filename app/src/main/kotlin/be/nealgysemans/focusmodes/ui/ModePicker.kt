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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import be.nealgysemans.focusmodes.R
import be.nealgysemans.focusmodes.tile.TileMode

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
 * The three optional parameters are what the widget's picker adds and the tile's dialog
 * deliberately does not, so that the tile's behaviour is unchanged by all of this:
 *
 * @param activeValueLabel mark the active row with a trailing "On" instead of a
 *   checkmark. iOS labels the row that is on rather than ticking it — a value, not a
 *   selection — and the word also says what tapping the row will undo.
 * @param activeTapTurnsOff let a tap on the active row report null, i.e. turn it off.
 *   Follows from [activeValueLabel]: a row that reads "On" has to be a switch. Without
 *   this a tap on the active row reports its own id, which every caller treats as a
 *   no-op re-activation.
 * @param onOpenSettings the one row that is not a mode, pinned to the bottom. Null omits
 *   it, which is what the tile's dialog wants — the tile has its own way into the app.
 */
@Composable
fun ModePickerSheet(
    modes: List<TileMode>,
    activeModeId: String?,
    onPick: (String?) -> Unit,
    modifier: Modifier = Modifier,
    activeValueLabel: Boolean = false,
    activeTapTurnsOff: Boolean = false,
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
            modes.forEach { mode ->
                val selected = mode.id == activeModeId
                PickerRow(
                    label = mode.name,
                    glyphRes = mode.glyphRes,
                    accent = Color(mode.color),
                    selected = selected,
                    valueLabel = activeValueLabel,
                    onClick = {
                        onPick(if (selected && activeTapTurnsOff) null else mode.id)
                    },
                )
            }
            PickerRow(
                label = stringResource(R.string.picker_off),
                glyphRes = ModeGlyphs.OFF_RES,
                accent = MaterialTheme.colorScheme.onSurfaceVariant,
                selected = activeModeId == null,
                // Never the value label, even under [activeValueLabel]: "Off … On" is
                // nonsense. This row is a selection, not a switch, so it keeps the tick.
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
 */
@Composable
fun ModeGrid(
    modes: List<TileMode>,
    activeModeId: String?,
    onPick: (String?) -> Unit,
    onOpenApp: () -> Unit,
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

            modes.chunked(GRID_COLUMNS).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { mode ->
                        GridCell(
                            label = mode.name,
                            glyphRes = mode.glyphRes,
                            accent = Color(mode.color),
                            selected = mode.id == activeModeId,
                            modifier = Modifier.weight(1f),
                            // Tapping the mode that is already on turns it off, so the
                            // grid is a toggle per cell rather than a one-way switch.
                            onClick = { onPick(if (mode.id == activeModeId) null else mode.id) },
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
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = modifier
            .heightIn(min = 96.dp)
            .clip(shape)
            .background(
                if (selected) accent.copy(alpha = SELECTED_FILL_ALPHA)
                else MaterialTheme.colorScheme.surfaceContainerHighest,
            )
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = if (selected) accent else Color.Transparent,
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
        contentAlignment = Alignment.Center,
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
            modifier = Modifier.size(size * 0.55f),
        )
    }
}

private const val GRID_COLUMNS = 2
private const val SELECTED_FILL_ALPHA = 0.18f
private const val IDLE_FILL_ALPHA = 0.16f
