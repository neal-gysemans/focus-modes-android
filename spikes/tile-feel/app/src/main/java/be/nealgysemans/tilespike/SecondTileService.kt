package be.nealgysemans.tilespike

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Second tile, `android:enabled="false"` in the manifest.
 *
 * This exists to probe the "one tile per mode" pattern that iOS users expect from
 * Control Center. The app flips this component on and off with
 * [PackageManager.setComponentEnabledSetting]; the questions we are answering on device:
 *
 *   - does it appear in / vanish from the QS edit list without a reboot?
 *   - does disabling it while it is *placed* silently drop it, and does re-enabling
 *     restore its position or dump it at the end?
 *   - does HyperOS's Control Center behave differently from AOSP QS here?
 *
 * It deliberately does NOT touch the zen rule. Its only job is to be enabled/disabled
 * and to log its own lifecycle callbacks.
 */
class SecondTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var store: SpikeStore

    @Volatile
    private var localOn = false

    override fun onCreate() {
        super.onCreate()
        store = SpikeStore(applicationContext)
        log("SecondTile onCreate")
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        log("SecondTile onTileAdded")
        render()
    }

    override fun onTileRemoved() {
        super.onTileRemoved()
        log("SecondTile onTileRemoved")
    }

    override fun onStartListening() {
        super.onStartListening()
        render()
        log("SecondTile onStartListening")
    }

    override fun onStopListening() {
        super.onStopListening()
        log("SecondTile onStopListening")
    }

    override fun onClick() {
        localOn = !localOn
        render()
        log("SecondTile onClick -> ${if (localOn) "ACTIVE" else "INACTIVE"} (local flag only)")
    }

    private fun render() {
        val tile = qsTile ?: return
        tile.state = if (localOn) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = if (localOn) "On" else "Off"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_mode_sleep)
        tile.updateTile()
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        scope.launch { runCatching { store.log(line) } }
    }

    companion object {
        private const val TAG = "SecondTile"

        fun component(context: Context) = ComponentName(context, SecondTileService::class.java)

        fun isEnabled(context: Context): Boolean =
            when (context.packageManager.getComponentEnabledSetting(component(context))) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
                // DEFAULT means "whatever the manifest says", and the manifest says false.
                else -> false
            }

        fun setEnabled(context: Context, enabled: Boolean) {
            context.packageManager.setComponentEnabledSetting(
                component(context),
                if (enabled) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                },
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
