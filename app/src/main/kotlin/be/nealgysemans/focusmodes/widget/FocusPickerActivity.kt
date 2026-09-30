package be.nealgysemans.focusmodes.widget

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import be.nealgysemans.focusmodes.tile.TileNudge
import be.nealgysemans.focusmodes.tile.TileSnapshot
import be.nealgysemans.focusmodes.tile.TileStateCache
import be.nealgysemans.focusmodes.tile.TileSnapshotSource
import be.nealgysemans.focusmodes.ui.FocusModesTheme
import be.nealgysemans.focusmodes.ui.MainActivity
import be.nealgysemans.focusmodes.ui.ModePickerSheet

/**
 * The widget's chevron target: the Focus selector, as a card in the middle of the screen.
 *
 * This is iOS's expanded Focus control. Tapping the chevron on the Control Center control
 * pushes the selector out of it — one row per Focus, the active one labelled "On", a tap
 * on that row turning it off, and a way into Settings at the bottom. A widget cannot
 * animate out of itself, so the substitute is a translucent activity with the dialog
 * window animation (see `Theme.FocusModes.Translucent`).
 *
 * ## Why not TilePrefsActivity
 *
 * That activity is the tile's `QS_TILE_PREFERENCES` target: it is exported with that
 * action, it bottom-anchors its card (a long-press starts at the shade, so the card
 * belongs under the thumb), and it shows [be.nealgysemans.focusmodes.ui.ModeGrid]. None of
 * the three is right here — the tap starts at the home screen, so the card is centred, and
 * a list of named rows is both iOS's shape and the one that reads with more modes than a
 * two-column grid of squares holds.
 *
 * ## Why not a Dialog from the widget
 *
 * It cannot be done. A RemoteViews click can only start an activity, send a broadcast or
 * run a Glance callback; there is no window for the widget's process to attach a dialog
 * to. An activity with a translucent theme *is* the dialog, which is why the old
 * "the widget cannot show a dialog, and the app is where the choice is already visible"
 * comment in `FocusWidget` is gone.
 *
 * ## State
 *
 * Renders from [TileSnapshotSource] like every other surface, seeded from
 * [TileStateCache]. The seed can be cold — a widget tap may be what starts the process —
 * so the card waits for a snapshot with modes in it rather than drawing an empty sheet and
 * resizing a frame later.
 */
class FocusPickerActivity : ComponentActivity() {

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
                    contentAlignment = Alignment.Center,
                ) {
                    // Nothing to choose between yet. The scrim alone for a frame or two
                    // beats a card containing only "Off" that then grows four rows.
                    if (snapshot.modes.isNotEmpty()) {
                        ModePickerSheet(
                            modes = snapshot.modes,
                            activeModeId = snapshot.activeModeId,
                            onPick = { picked -> commit(snapshot, picked) },
                            modifier = Modifier
                                .padding(24.dp)
                                // A picker is a list of labels, not a layout to fill: past
                                // this it stops reading as a card floating over the home
                                // screen and starts reading as a screen.
                                .widthIn(max = 420.dp),
                            activeValueLabel = true,
                            activeTapTurnsOff = true,
                            onOpenSettings = ::openApp,
                        )
                    }
                }
            }
        }
    }

    /**
     * Get out of the way, then toggle through the engine.
     *
     * [finish] *first*, deliberately, and this is the one ordering difference from
     * `TilePrefsActivity.commit`. The user's next glance is at the widget they tapped,
     * which is behind this window: the activity has to be on its way out before the
     * repaint `SurfaceSync` triggers lands, or the first thing they see moving is hidden
     * under a card that is still dismissing. Same reason `ComposeModePickerDialog`
     * dismisses before it reports a pick.
     *
     * The cache flip and the [TileNudge] are here rather than left to `SurfaceSync`
     * because the tile has a much tighter repaint window than anything else and may be
     * the next surface the user opens. `SurfaceSync` still has the last word.
     */
    private fun commit(snapshot: TileSnapshot, picked: String?) {
        val event = when {
            picked != null -> TriggerEvent(ActivationSource.USER, picked, Direction.ACTIVATE)
            snapshot.activeModeId != null ->
                TriggerEvent(ActivationSource.USER, snapshot.activeModeId, Direction.DEACTIVATE)
            // "Off" picked while already off: nothing to submit, just leave.
            else -> null
        }
        finish()
        if (event != null) {
            TileStateCache.flipTo(picked)
            AppGraph.from(applicationContext).submitAsync(event)
            TileNudge.refresh(applicationContext)
        }
    }

    private fun openApp() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        finish()
    }
}
