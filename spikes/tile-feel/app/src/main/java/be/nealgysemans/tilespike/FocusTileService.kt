package be.nealgysemans.tilespike

import android.app.Dialog
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * The tile under test.
 *
 * Latency discipline, in order:
 *   1. `onClick()` does nothing but read volatile fields and flip the Tile.
 *   2. `updateTile()` is the last thing before we take the t1 stamp.
 *   3. Disk writes, zen-rule calls and confirmation polling all happen on Dispatchers.IO
 *      afterwards, where they cannot inflate the number the user actually feels.
 *
 * Icons are pre-created in [onCreate] because `Icon.createWithResource` inside onClick
 * would be an avoidable allocation on the hot path.
 */
class FocusTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var store: SpikeStore
    private lateinit var zen: ZenController
    private lateinit var engine: ToggleEngine
    private lateinit var watcher: ZenBroadcastWatcher

    private val iconCache = HashMap<Int, Icon>()

    override fun onCreate() {
        super.onCreate()
        store = SpikeStore(applicationContext)
        zen = ZenController(applicationContext)
        watcher = ZenBroadcastWatcher(applicationContext) { line -> logAsync(line) }
        watcher.register()
        engine = ToggleEngine(store, zen, watcher)
        listOf(
            R.drawable.ic_focus_off,
            R.drawable.ic_focus_on,
            R.drawable.ic_mode_work,
            R.drawable.ic_mode_sleep,
            R.drawable.ic_mode_personal,
        ).forEach { iconCache[it] = Icon.createWithResource(this, it) }
        logAsync("FocusTile onCreate")
    }

    override fun onDestroy() {
        watcher.unregister()
        scope.cancel()
        super.onDestroy()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        logAsync("FocusTile onTileAdded  (user placed it / requestAddTileService accepted)")
        warmCacheBlocking()
        render()
    }

    override fun onTileRemoved() {
        super.onTileRemoved()
        logAsync("FocusTile onTileRemoved")
    }

    override fun onStartListening() {
        super.onStartListening()
        // Not latency-critical: onStartListening always precedes onClick, so this is
        // where we pay the disk read once and keep the click path pure memory.
        warmCacheBlocking()
        render()
        logAsync(
            "FocusTile onStartListening  behavior=${StateCache.value.behavior.name} " +
                "mode=${StateCache.value.activeMode?.label ?: "OFF"} " +
                "isSecure=$isSecure isLocked=${isLocked}",
        )
    }

    override fun onStopListening() {
        super.onStopListening()
        logAsync("FocusTile onStopListening")
    }

    override fun onClick() {
        // ---- HOT PATH BEGINS ----
        val t0 = SystemClock.elapsedRealtimeNanos()
        val state = StateCache.value
        when (state.behavior) {
            TileBehavior.TOGGLE -> handleToggle(t0, state)
            TileBehavior.DIALOG_CLASSIC -> handleDialog(t0, state, compose = false)
            TileBehavior.DIALOG_COMPOSE -> handleDialog(t0, state, compose = true)
            TileBehavior.ACTIVITY -> handleActivityLaunch(t0)
        }
    }

    // ------------------------------------------------------------------ toggle

    private fun handleToggle(t0: Long, state: SpikeState) {
        // Cycle OFF -> WORK -> SLEEP -> PERSONAL -> OFF so repeated taps exercise both
        // directions and every icon.
        val next = when (state.activeMode) {
            null -> FakeMode.WORK
            FakeMode.WORK -> FakeMode.SLEEP
            FakeMode.SLEEP -> FakeMode.PERSONAL
            FakeMode.PERSONAL -> null
        }
        applyOptimisticFlip(next)
        val t1 = SystemClock.elapsedRealtimeNanos()
        // ---- HOT PATH ENDS ----
        StateCache.setActiveModeOptimistically(next)
        scope.launch {
            engine.applyAndMeasure(TileBehavior.TOGGLE.name, next, t0, t1)
        }
    }

    /** Sets state + subtitle + icon and pushes them. Everything here is pre-allocated. */
    private fun applyOptimisticFlip(next: FakeMode?) {
        val tile = qsTile ?: return
        tile.state = if (next != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = next?.label ?: "Off"
        tile.stateDescription = next?.let { "On, ${it.label}" } ?: "Off"
        tile.contentDescription = "Focus ${next?.label ?: "off"}"
        tile.icon = iconCache[next?.iconRes ?: R.drawable.ic_focus_off]
        tile.updateTile()
    }

    private fun render() {
        val state = StateCache.value
        val tile = qsTile ?: return
        tile.state = if (state.activeMode != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = state.activeMode?.label ?: "Off"
        tile.stateDescription = state.activeMode?.let { "On, ${it.label}" } ?: "Off"
        tile.icon = iconCache[state.activeMode?.iconRes ?: R.drawable.ic_focus_off]
            ?: Icon.createWithResource(this, R.drawable.ic_focus_off)
        tile.updateTile()
    }

    // ------------------------------------------------------------------ dialog

    private fun handleDialog(t0: Long, state: SpikeState, compose: Boolean) {
        val behavior = if (compose) TileBehavior.DIALOG_COMPOSE else TileBehavior.DIALOG_CLASSIC

        val show = Runnable {
            val built = SystemClock.elapsedRealtimeNanos()
            val dialog: Dialog = if (compose) {
                ComposeModePickerDialog(this, state.activeMode) { picked ->
                    onDialogPick(behavior, picked)
                }
            } else {
                ClassicModePicker.create(this, state.activeMode) { picked ->
                    onDialogPick(behavior, picked)
                }
            }
            val constructed = SystemClock.elapsedRealtimeNanos()
            // showDialog() also collapses the shade for us.
            showDialog(dialog)
            val shown = SystemClock.elapsedRealtimeNanos()
            logAsync(
                "showDialog(${if (compose) "compose" else "classic"}): " +
                    "toRunnable=${ms(t0, built)} construct=${ms(built, constructed)} " +
                    "show=${ms(constructed, shown)} total=${ms(t0, shown)}",
            )
        }

        // showDialog() renders UNDER the keyguard when the device is locked, so gate it.
        if (isSecure && isLocked) {
            logAsync("tile tap while locked+secure -> unlockAndRun (dialog deferred)")
            unlockAndRun(show)
        } else {
            logAsync("tile tap unlocked (isSecure=$isSecure isLocked=$isLocked) -> showDialog now")
            show.run()
        }
    }

    private fun onDialogPick(behavior: TileBehavior, picked: FakeMode?) {
        val t0 = SystemClock.elapsedRealtimeNanos()
        applyOptimisticFlip(picked)
        val t1 = SystemClock.elapsedRealtimeNanos()
        StateCache.setActiveModeOptimistically(picked)
        scope.launch { engine.applyAndMeasure(behavior.name, picked, t0, t1) }
    }

    // ---------------------------------------------------------------- activity

    private fun handleActivityLaunch(t0: Long) {
        val intent = Intent(this, LaunchedFromTileActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(LaunchedFromTileActivity.EXTRA_T0_NANOS, t0)
        val pi = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // The Intent overload of startActivityAndCollapse throws on targetSdk 34+.
        // PendingIntent is the only legal form now.
        runCatching { startActivityAndCollapse(pi) }
            .onFailure { logAsync("startActivityAndCollapse FAILED: ${it.javaClass.simpleName}: ${it.message}") }
            .onSuccess { logAsync("startActivityAndCollapse(PendingIntent) issued in ${ms(t0, SystemClock.elapsedRealtimeNanos())}") }
    }

    // ------------------------------------------------------------------- utils

    private fun warmCacheBlocking() {
        runCatching {
            runBlocking { StateCache.publish(store.state.first()) }
        }.onFailure { Log.w(TAG, "cache warm failed", it) }
    }

    private fun logAsync(line: String) {
        Log.i(TAG, line)
        scope.launch { runCatching { store.log(line) } }
    }

    companion object {
        private const val TAG = "FocusTile"

        fun ms(from: Long, to: Long): String = "%.2fms".format((to - from) / 1_000_000.0)
    }
}
