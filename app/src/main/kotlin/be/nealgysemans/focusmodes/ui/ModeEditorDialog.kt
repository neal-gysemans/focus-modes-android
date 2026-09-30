package be.nealgysemans.focusmodes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import be.nealgysemans.focusmodes.R
import be.nealgysemans.focusmodes.data.ModeEntity

/**
 * Rename a mode, pick its glyph, pick its colour.
 *
 * Scoped to the three things that change how a mode *looks on every surface* — the
 * tile glyph, the notification accent, the row in the list. People filters, effects and
 * schedules are edits to what a mode *does*, which is a bigger screen and a different
 * job; this one exists so the tile can stop being three interchangeable circles.
 *
 * Edits are local until Save: [onSave] is handed a copy of the entity and the caller
 * owns persisting it, invalidating the engine's mode snapshot and repainting the tile.
 * A blank name is rejected by disabling Save rather than by silently keeping the old
 * one — a mode with no name is unusable on an icon-only tile.
 */
@Composable
fun ModeEditorDialog(
    mode: ModeEntity,
    onDismiss: () -> Unit,
    onSave: (ModeEntity) -> Unit,
) {
    var name by remember(mode.id) { mutableStateOf(mode.name) }
    var iconKey by remember(mode.id) { mutableStateOf(mode.iconKey) }
    // An ARGB Int, so the specialised state avoids boxing it on every recomposition.
    var color by remember(mode.id) { mutableIntStateOf(mode.color) }

    val accent = Color(color)
    val trimmedName = name.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mode_editor_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.mode_editor_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.mode_editor_glyph),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    // Chunked into fixed rows rather than scrolled or lazily laid out: a
                    // dialog is too narrow for the whole set on one line, and a glyph the
                    // user has to discover by swiping may as well not be there.
                    ModeGlyphs.ALL.chunked(CHOICES_PER_ROW).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { glyph ->
                                GlyphChoice(
                                    glyph = glyph,
                                    accent = accent,
                                    selected = glyph.key == iconKey,
                                    onClick = { iconKey = glyph.key },
                                )
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.mode_editor_color),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    ModePalette.COLORS.chunked(CHOICES_PER_ROW).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { candidate ->
                                ColorChoice(
                                    color = Color(candidate),
                                    selected = candidate == color,
                                    onClick = { color = candidate },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(mode.copy(name = trimmedName, iconKey = iconKey, color = color))
                },
                enabled = trimmedName.isNotEmpty(),
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun GlyphChoice(
    glyph: ModeGlyph,
    accent: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(percent = 50)
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(shape)
            .background(
                if (selected) accent.copy(alpha = 0.20f)
                else MaterialTheme.colorScheme.surfaceContainerHighest,
            )
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = if (selected) accent else Color.Transparent,
                shape = shape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(glyph.res),
            contentDescription = stringResource(glyph.labelRes),
            tint = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun ColorChoice(
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(percent = 50)
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(shape)
            .background(color)
            .border(
                width = if (selected) 3.dp else 0.dp,
                color = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                shape = shape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = stringResource(R.string.picker_selected),
                tint = MaterialTheme.colorScheme.surface,
                modifier = Modifier.size(16.dp).padding(1.dp),
            )
        }
    }
}

/** Keeps both choice grids the same shape and inside a dialog's width. */
private const val CHOICES_PER_ROW = 4
