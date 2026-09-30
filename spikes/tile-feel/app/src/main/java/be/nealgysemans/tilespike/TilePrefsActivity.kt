package be.nealgysemans.tilespike

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The long-press target (`QS_TILE_PREFERENCES`).
 *
 * Deliberately NOT a settings screen. On iOS, press-and-hold on a Control Center
 * control gives you the *thing itself*, larger — so this is the mode picker, floating
 * over a dimmed background, one tap to commit and gone. Whether that reads as fast on
 * HyperOS (which animates activity launches out of Control Center differently) is the
 * question this screen exists to answer.
 */
class TilePrefsActivity : ComponentActivity() {

    private lateinit var store: SpikeStore
    private lateinit var zen: ZenController
    private lateinit var engine: ToggleEngine
    private lateinit var watcher: ZenBroadcastWatcher

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val t0 = SystemClock.elapsedRealtimeNanos()
        store = SpikeStore(applicationContext)
        zen = ZenController(applicationContext)
        watcher = ZenBroadcastWatcher(applicationContext) { line -> log(line) }
        watcher.register()
        engine = ToggleEngine(store, zen, watcher)

        log("QS_TILE_PREFERENCES activity opened (action=${intent?.action})")

        setContent {
            SpikeTheme {
                Box(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    ModePickerContent(
                        title = "Focus",
                        footer = "long-press target · QS_TILE_PREFERENCES",
                        current = StateCache.value.activeMode,
                        onPick = { picked -> commit(picked, t0) },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        watcher.unregister()
        super.onDestroy()
    }

    private fun commit(picked: FakeMode?, t0: Long) {
        val tapNanos = SystemClock.elapsedRealtimeNanos()
        StateCache.setActiveModeOptimistically(picked)
        // No qsTile here (we are an Activity, not the TileService), so nudge the tile
        // out of band. This is the requestListeningState path an ACTIVE_TILE needs.
        TileRefresher.refreshFocusTile(this)
        lifecycleScope.launch {
            log("prefs pick ${picked?.label ?: "OFF"} ${FocusTileService.ms(t0, tapNanos)} after open")
            withContext(Dispatchers.IO) {
                engine.applyAndMeasure("PREFS_ACTIVITY", picked, tapNanos, -1L)
            }
        }
        finish()
    }

    private fun log(line: String) {
        lifecycleScope.launch(Dispatchers.IO) { runCatching { store.log(line) } }
    }
}
