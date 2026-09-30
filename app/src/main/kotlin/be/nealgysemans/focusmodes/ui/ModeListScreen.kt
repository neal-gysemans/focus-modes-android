package be.nealgysemans.focusmodes.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.nealgysemans.focusmodes.R
import be.nealgysemans.focusmodes.data.ModeEntity
import be.nealgysemans.focusmodes.engine.ActivationSource
import be.nealgysemans.focusmodes.engine.ActiveState
import be.nealgysemans.focusmodes.engine.Direction
import be.nealgysemans.focusmodes.engine.TriggerEvent
import be.nealgysemans.focusmodes.health.Grant
import be.nealgysemans.focusmodes.health.HealthCheck
import be.nealgysemans.focusmodes.health.PermissionHealth
import be.nealgysemans.focusmodes.tile.TilePrefs
import kotlinx.coroutines.flow.Flow

/**
 * Everything the screen can ask the Activity to do.
 *
 * Grouped into one value rather than passed as eight parameters: these are all
 * "reach outside Compose" operations (engine, DAOs, StatusBarManager, the permission
 * launcher) and bundling them keeps the boundary between the screen and the platform
 * visible in one place.
 */
class ModeListActions(
    /** Hand a user toggle to `ModeEngine`. Never writes state directly. */
    val onToggle: (TriggerEvent) -> Unit,
    /** Persist an edited mode, invalidate the engine's snapshot, repaint the tile. */
    val onSaveMode: (ModeEntity) -> Unit,
    /** Store the tile's "always ask" preference. */
    val onAlwaysAskChange: (Boolean) -> Unit,
    /** `StatusBarManager.requestAddTileService`. */
    val onAddTile: () -> Unit,
    /** Launch the `POST_NOTIFICATIONS` runtime request. */
    val onRequestNotifications: () -> Unit,
    /** Open a Settings screen for a grant that cannot be requested in-app. */
    val onOpenSettings: (Intent) -> Unit,
)

/**
 * The app's one screen: the modes, what is on, and the handful of things only a real
 * screen can do.
 *
 * The Quick Settings tile is the primary surface, so this is deliberately not where
 * users are expected to live. It earns its place by doing four things the tile cannot:
 * fix permissions, edit what a mode looks like, put the tile on the user's shade, and
 * show all modes at once with the active one unmistakable.
 *
 * Reactive throughout — Room and DataStore flows straight into Compose — so a change
 * made on the tile or in the picker is already reflected here by the time the user
 * switches back, with no refresh path of its own.
 *
 * No ViewModel: there is no state to survive a configuration change that Room and
 * DataStore are not already the source of. The one exception is which mode is being
 * edited, which is transient by design.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeListScreen(
    modes: Flow<List<ModeEntity>>,
    activeState: Flow<ActiveState>,
    tilePrefs: Flow<TilePrefs>,
    health: PermissionHealth,
    actions: ModeListActions,
) {
    val modeList by modes.collectAsStateWithLifecycle(initialValue = emptyList())
    val active by activeState.collectAsStateWithLifecycle(initialValue = ActiveState.IDLE)
    val prefs by tilePrefs.collectAsStateWithLifecycle(initialValue = TilePrefs())

    // Recomputed on each composition on purpose: these grants can be revoked while
    // the app is backgrounded, so a cached value would lie.
    val checks = health.checkAll()

    var editing by remember { mutableStateOf<ModeEntity?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(checks.filterNot(HealthCheck::granted), key = { it.id.name }) { check ->
                HealthCard(check, health, actions)
            }

            items(modeList, key = ModeEntity::id) { mode ->
                ModeRow(
                    mode = mode,
                    isActive = active.activeModeId == mode.id,
                    onEdit = { editing = mode },
                    onToggle = { wantOn ->
                        actions.onToggle(
                            TriggerEvent(
                                source = ActivationSource.USER,
                                modeId = mode.id,
                                direction = if (wantOn) Direction.ACTIVATE else Direction.DEACTIVATE,
                            ),
                        )
                    },
                )
            }

            item {
                TileCard(
                    alwaysAsk = prefs.alwaysAsk,
                    onAlwaysAskChange = actions.onAlwaysAskChange,
                    onAddTile = actions.onAddTile,
                )
            }
        }
    }

    editing?.let { mode ->
        ModeEditorDialog(
            mode = mode,
            onDismiss = { editing = null },
            onSave = { edited ->
                actions.onSaveMode(edited)
                editing = null
            },
        )
    }
}

/**
 * One mode.
 *
 * The active one is marked three ways at once — a filled accent badge, the card tinted
 * with the mode's own colour, and the name in that colour — because on a list of
 * near-identical rows a switch position alone is easy to misread. The colour is the
 * mode's, not the theme's: it is the same colour the tile glyph and the notification
 * accent use, so "purple means Sleep" holds across all three surfaces.
 *
 * Tapping the row opens the editor; the switch is the toggle. Two targets, and the
 * bigger one is the safer one.
 */
@Composable
private fun ModeRow(
    mode: ModeEntity,
    isActive: Boolean,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val accent = Color(mode.color)
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) {
                accent.copy(alpha = ACTIVE_CARD_ALPHA)
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlyphBadge(
                glyphRes = ModeGlyphs.resFor(mode.iconKey),
                accent = accent,
                filled = isActive,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = mode.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isActive) accent else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        R.string.mode_people_summary,
                        mode.callsFrom.name,
                        mode.messagesFrom.name,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = isActive, onCheckedChange = onToggle)
        }
    }
}

/**
 * The tile's own card: put it on the shade, and decide what a tap does.
 *
 * `requestAddTileService` is here rather than buried in a settings screen because a
 * tile-first app whose tile the user never found is a tile-first app that does nothing.
 * It shows a system dialog and works on HyperOS (verified in spike #2).
 */
@Composable
private fun TileCard(
    alwaysAsk: Boolean,
    onAlwaysAskChange: (Boolean) -> Unit,
    onAddTile: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.tile_card_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Button(onClick = onAddTile) {
                Text(stringResource(R.string.action_add_tile))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.always_ask_title),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(R.string.always_ask_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = alwaysAsk, onCheckedChange = onAlwaysAskChange)
            }
        }
    }
}

/**
 * One missing grant, with the button that fixes it.
 *
 * Says which feature is lost rather than just "grant this", and the button is only
 * offered when it would actually land somewhere — OEM skins do remove Settings
 * screens, and firing an unresolvable intent throws.
 */
@Composable
private fun HealthCard(
    check: HealthCheck,
    health: PermissionHealth,
    actions: ModeListActions,
) {
    val message = when (check.id) {
        Grant.DND_ACCESS -> R.string.health_dnd_access_missing
        Grant.EXACT_ALARM -> R.string.health_exact_alarm_missing
        Grant.POST_NOTIFICATIONS -> R.string.health_post_notifications_missing
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (check.blocking) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = stringResource(message), style = MaterialTheme.typography.bodyMedium)
            when {
                // POST_NOTIFICATIONS is a normal runtime permission: ask in-app.
                check.settingsIntent == null -> TextButton(onClick = actions.onRequestNotifications) {
                    Text(stringResource(R.string.action_allow))
                }

                health.canOpen(check.settingsIntent) -> TextButton(
                    onClick = { actions.onOpenSettings(check.settingsIntent) },
                ) {
                    Text(stringResource(R.string.action_fix))
                }

                else -> Unit
            }
        }
    }
}

private const val ACTIVE_CARD_ALPHA = 0.16f
