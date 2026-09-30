package be.nealgysemans.focusmodes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.nealgysemans.focusmodes.R
import be.nealgysemans.focusmodes.data.ModeEntity
import be.nealgysemans.focusmodes.engine.ActivationSource
import be.nealgysemans.focusmodes.engine.ActiveState
import be.nealgysemans.focusmodes.engine.Direction
import be.nealgysemans.focusmodes.engine.TriggerEvent
import be.nealgysemans.focusmodes.health.PermissionHealth
import kotlinx.coroutines.flow.Flow

/**
 * Placeholder screen: the seeded modes from Room, with a toggle each.
 *
 * Intentionally thin — it exists to prove the wiring end to end (Room seed reaches
 * Compose, a toggle reaches `ModeEngine` with `ActivationSource.USER`) rather than
 * to be the shipping UI. The real screens (mode editor, people picker, schedule
 * editor, permission onboarding) come later.
 *
 * No ViewModel yet: there is no state to survive configuration changes that Room
 * and DataStore are not already the source of.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeListScreen(
    modes: Flow<List<ModeEntity>>,
    activeState: Flow<ActiveState>,
    health: PermissionHealth,
    onToggle: (TriggerEvent) -> Unit,
) {
    val modeList by modes.collectAsStateWithLifecycle(initialValue = emptyList())
    val active by activeState.collectAsStateWithLifecycle(initialValue = ActiveState.IDLE)

    // Recomputed on each composition on purpose: these grants can be revoked while
    // the app is backgrounded, so a cached value would lie.
    val blockingGrantMissing = remember(modeList) { !health.isOperational() }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (blockingGrantMissing) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Text(
                            text = stringResource(R.string.health_dnd_access_missing),
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            items(modeList, key = ModeEntity::id) { mode ->
                ModeRow(
                    mode = mode,
                    isActive = active.activeModeId == mode.id,
                    onToggle = { wantOn ->
                        onToggle(
                            TriggerEvent(
                                source = ActivationSource.USER,
                                modeId = mode.id,
                                direction = if (wantOn) Direction.ACTIVATE else Direction.DEACTIVATE,
                            ),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun ModeRow(
    mode: ModeEntity,
    isActive: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(text = mode.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(
                        R.string.mode_people_summary,
                        mode.callsFrom.name,
                        mode.messagesFrom.name,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = isActive, onCheckedChange = onToggle)
        }
    }
}
