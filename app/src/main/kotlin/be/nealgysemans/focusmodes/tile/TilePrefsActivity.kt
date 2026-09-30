package be.nealgysemans.focusmodes.tile

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.nealgysemans.focusmodes.di.AppGraph
import be.nealgysemans.focusmodes.engine.ActivationSource
import be.nealgysemans.focusmodes.engine.Direction
import be.nealgysemans.focusmodes.engine.TriggerEvent
import be.nealgysemans.focusmodes.notification.SurfaceSync
import be.nealgysemans.focusmodes.ui.FocusModesTheme
import be.nealgysemans.focusmodes.ui.MainActivity
import be.nealgysemans.focusmodes.ui.ModeGrid

/**
 * The tile's long-press target (`QS_TILE_PREFERENCES`).
 *
 * Deliberately **not** a settings screen. Press-and-hold on an iOS Control Center
 * control gives you the control itself, larger — so this is the mode grid floating
 * over a dimmed background: every mode visible, one tap commits, and the activity
 * finishes. The one thing that is not a mode is a text button into [MainActivity],
 * for the editing and permission work that genuinely needs a screen.
 *
 * It renders from [TileStateCache] on the first frame (already warm, because the tile
 * was listening moments ago when the user long-pressed it) and then follows
 * [TileSnapshotSource] like every other surface.
 */
class TilePrefsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SurfaceSync.start(applicationContext)

        setContent {
            FocusModesTheme {
                val snapshot by remember { TileSnapshotSource.flow(applicationContext) }
                    .collectAsStateWithLifecycle(initialValue = TileStateCache.value)

                // Tapping the dimmed area outside the card dismisses, like a dialog.
                // No ripple: the scrim is not a control, it is the way out.
                val dismissInteraction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = dismissInteraction,
                            indication = null,
                            onClick = ::finish,
                        ),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    ModeGrid(
                        modes = snapshot.modes,
                        activeModeId = snapshot.activeModeId,
                        onPick = { picked -> commit(snapshot, picked) },
                        onOpenApp = ::openApp,
                        modifier = Modifier
                            .padding(16.dp)
                            .navigationBarsPadding(),
                    )
                }
            }
        }
    }

    /**
     * Toggle through the engine, then get out of the way.
     *
     * The cache flip and the [TileNudge] are here rather than left to `SurfaceSync`
     * because this activity is about to finish: the user's next glance is at the tile,
     * and it should already be right by the time the shade repaints. `SurfaceSync` still
     * runs and still has the last word.
     */
    private fun commit(snapshot: TileSnapshot, picked: String?) {
        val event = when {
            picked != null -> TriggerEvent(ActivationSource.USER, picked, Direction.ACTIVATE)
            snapshot.activeModeId != null ->
                TriggerEvent(ActivationSource.USER, snapshot.activeModeId, Direction.DEACTIVATE)
            else -> null
        }
        if (event != null) {
            TileStateCache.flipTo(picked)
            AppGraph.from(applicationContext).submitAsync(event)
            TileNudge.refresh(applicationContext)
        }
        finish()
    }

    private fun openApp() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        finish()
    }
}
