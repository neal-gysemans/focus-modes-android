package be.nealgysemans.focusmodes.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import be.nealgysemans.focusmodes.di.AppGraph

/**
 * The app's only Activity.
 *
 * Single-activity by design: the Quick Settings tile is the primary surface, and
 * the UI exists to define modes and fix permissions, not as a place users live.
 *
 * Reconciles on every resume, because grants and system rules can change while the
 * app is in the background (Settings edits, OEM battery managers revoking things).
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = AppGraph.from(applicationContext)
        setContent {
            FocusModesTheme {
                ModeListScreen(
                    modes = graph.database.modeDao().observeModes(),
                    activeState = graph.activeStateStore.flow,
                    health = graph.permissionHealth,
                    onToggle = graph::submitAsync,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AppGraph.from(applicationContext).reconcileAsync()
    }
}
