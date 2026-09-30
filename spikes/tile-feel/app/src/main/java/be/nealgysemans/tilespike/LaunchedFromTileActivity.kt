package be.nealgysemans.tilespike

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Target of `startActivityAndCollapse(PendingIntent)`.
 *
 * Reports tile-tap -> first-composition latency, which is the number that decides
 * whether "tile opens a screen" can ever feel like Control Center (spoiler: an activity
 * launch is an order of magnitude slower than a dialog, which is why the dialog paths
 * exist at all).
 */
class LaunchedFromTileActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val arrived = SystemClock.elapsedRealtimeNanos()
        val t0 = intent?.getLongExtra(EXTRA_T0_NANOS, -1L) ?: -1L
        val deltaMs = if (t0 > 0) (arrived - t0) / 1_000_000.0 else -1.0

        val store = SpikeStore(applicationContext)
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                store.log(
                    if (deltaMs >= 0) {
                        "startActivityAndCollapse -> onCreate in %.1fms".format(deltaMs)
                    } else {
                        "startActivityAndCollapse target opened (no t0 extra)"
                    },
                )
            }
        }

        setContent {
            SpikeTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(24.dp)) {
                        Text("Launched from the tile", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "via startActivityAndCollapse(PendingIntent)",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            if (deltaMs >= 0) {
                                "tile tap -> onCreate: %.1f ms".format(deltaMs)
                            } else {
                                "no t0 timestamp in the intent"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                        Text(
                            "The Intent overload of startActivityAndCollapse throws " +
                                "IllegalArgumentException on targetSdk 34+; only the " +
                                "PendingIntent overload is legal, and the PendingIntent's " +
                                "Intent needs FLAG_ACTIVITY_NEW_TASK.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                        Button(onClick = { finish() }, modifier = Modifier.padding(top = 24.dp)) {
                            Text("Close")
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_T0_NANOS = "t0_nanos"
    }
}
