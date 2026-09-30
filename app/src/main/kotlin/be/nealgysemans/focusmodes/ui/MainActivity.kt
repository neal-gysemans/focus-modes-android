package be.nealgysemans.focusmodes.ui

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import be.nealgysemans.focusmodes.R
import be.nealgysemans.focusmodes.data.ModeEntity
import be.nealgysemans.focusmodes.data.TriggerEntity
import be.nealgysemans.focusmodes.di.AppGraph
import be.nealgysemans.focusmodes.engine.Direction
import be.nealgysemans.focusmodes.engine.TriggerEvent
import be.nealgysemans.focusmodes.notification.SurfaceSync
import be.nealgysemans.focusmodes.tile.FocusTileService
import be.nealgysemans.focusmodes.tile.TileNudge
import be.nealgysemans.focusmodes.tile.TilePreferences
import be.nealgysemans.focusmodes.tile.TileStateCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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

    private val graph: AppGraph by lazy { AppGraph.from(applicationContext) }
    private val tilePreferences: TilePreferences by lazy { TilePreferences(applicationContext) }

    /**
     * `POST_NOTIFICATIONS`. Registered unconditionally (the contract has to exist before
     * the activity is started) but only launched at the moment the notification would
     * otherwise have been shown and swallowed.
     */
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        Log.i(TAG, "POST_NOTIFICATIONS granted=$granted")
        // Granting mid-session leaves the notification missing for the mode that is
        // already on; a reconcile walks the state through SurfaceSync again and posts it.
        if (granted) graph.reconcileAsync()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        SurfaceSync.start(applicationContext)

        setContent {
            FocusModesTheme {
                ModeListScreen(
                    modes = graph.database.modeDao().observeModes(),
                    schedules = graph.database.triggerDao().observeSchedules(),
                    activeState = graph.activeStateStore.flow,
                    tilePrefs = tilePreferences.flow,
                    health = graph.permissionHealth,
                    actions = ModeListActions(
                        onToggle = ::toggle,
                        onSaveMode = ::saveMode,
                        onSaveSchedule = ::saveSchedule,
                        onDeleteSchedule = ::deleteSchedule,
                        onAlwaysAskChange = ::setAlwaysAsk,
                        onAddTile = ::requestAddTile,
                        onRequestNotifications = ::requestNotificationPermission,
                        onOpenSettings = ::openSettings,
                    ),
                )
            }
        }
    }

    // Foreground reconciliation happens in FocusModesApplication's
    // ForegroundReconciler for every activity; no per-activity onResume call needed.

    // ------------------------------------------------------------------ mode control

    /**
     * Toggle a mode through the engine.
     *
     * The optimistic cache flip and tile nudge mirror what the tile does to itself: the
     * user may pull the shade down a moment later, and `requestListeningState` is the
     * only thing that makes an `ACTIVE_TILE` repaint. `SurfaceSync` still has the last
     * word once the engine has actually decided.
     *
     * Turning a mode *on* is also the first moment the ongoing notification matters, so
     * that is where the runtime permission is asked for — not at launch, where the user
     * has no idea what they would be saying yes to.
     */
    private fun toggle(event: TriggerEvent) {
        if (event.direction == Direction.ACTIVATE) {
            TileStateCache.flipTo(event.modeId)
            requestNotificationPermissionIfNeeded()
        } else {
            TileStateCache.flipTo(null)
        }
        graph.submitAsync(event)
        TileNudge.refresh(applicationContext)
    }

    /**
     * Persist an edited mode.
     *
     * Three follow-ups, all necessary: [be.nealgysemans.focusmodes.data.RoomModeCatalog]
     * holds a snapshot the engine reads synchronously and must be invalidated by hand;
     * `reconcile` pushes the new name and colour into the mode's `AutomaticZenRule` so
     * the system's own Modes screen agrees with ours; and the tile has to repaint because
     * its glyph and subtitle may just have changed. The Room flow takes care of this
     * screen on its own.
     */
    private fun saveMode(mode: ModeEntity) {
        lifecycleScope.launch(Dispatchers.IO) {
            graph.database.modeDao().upsert(mode)
            graph.modeCatalog.invalidate()
            graph.reconcileAsync()
            TileNudge.refresh(applicationContext)
        }
    }

    /**
     * Persist one schedule row and let the engine work out what it means.
     *
     * Deliberately shorter than [saveMode]: there is no catalog to invalidate, because
     * `RoomModeCatalog` snapshots *modes* and `TriggerScheduleSource` re-reads the
     * trigger table on every reconcile, and there is no alarm to arm by hand, because
     * `AppGraph.afterTransition` re-arms unconditionally after every reconcile. So the
     * write plus a reconcile is the whole operation — and if that ever stops being true,
     * the schedule that silently never fires is the symptom.
     *
     * The tile repaint waits for the reconcile to finish rather than firing alongside it:
     * a schedule the user just switched on may turn a mode on *now*, and nudging the tile
     * before the engine has decided repaints it with the old answer.
     */
    private fun saveSchedule(trigger: TriggerEntity) {
        lifecycleScope.launch(Dispatchers.IO) {
            graph.database.triggerDao().upsert(trigger)
            graph.reconcileAsync { TileNudge.refresh(applicationContext) }
        }
    }

    private fun deleteSchedule(trigger: TriggerEntity) {
        lifecycleScope.launch(Dispatchers.IO) {
            graph.database.triggerDao().delete(trigger)
            graph.reconcileAsync { TileNudge.refresh(applicationContext) }
        }
    }

    private fun setAlwaysAsk(alwaysAsk: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) { tilePreferences.setAlwaysAsk(alwaysAsk) }
    }

    // ---------------------------------------------------------------------- platform

    /**
     * Ask the system to offer the user our tile.
     *
     * Shows a platform dialog and requires the app to be in the foreground, which is why
     * it lives on this screen. The result code is logged rather than surfaced: the user
     * already saw the system's own dialog, so a second message from us would only
     * restate it — but the code is the only way to tell "declined" from "already there"
     * when a bug report says the tile never appeared.
     */
    private fun requestAddTile() {
        val statusBar = getSystemService(StatusBarManager::class.java) ?: run {
            Log.w(TAG, "no StatusBarManager; cannot request tile add")
            return
        }
        runCatching {
            statusBar.requestAddTileService(
                ComponentName(this, FocusTileService::class.java),
                getString(R.string.tile_label),
                Icon.createWithResource(this, R.drawable.ic_tile_focus),
                mainExecutor,
            ) { result -> Log.i(TAG, "requestAddTileService -> ${addTileResult(result)} ($result)") }
        }.onFailure { Log.w(TAG, "requestAddTileService threw", it) }
    }

    /**
     * Name the result code.
     *
     * The ERROR_* values are the ones worth naming: they are the difference between "the
     * user said no" and "we called this wrong" — and `APP_NOT_IN_FOREGROUND` in
     * particular is the mistake this call is easiest to make.
     */
    private fun addTileResult(result: Int): String = when (result) {
        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "TILE_ADDED"
        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "TILE_ALREADY_ADDED"
        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> "TILE_NOT_ADDED"
        StatusBarManager.TILE_ADD_REQUEST_ERROR_APP_NOT_IN_FOREGROUND -> "ERROR_APP_NOT_IN_FOREGROUND"
        StatusBarManager.TILE_ADD_REQUEST_ERROR_REQUEST_IN_PROGRESS -> "ERROR_REQUEST_IN_PROGRESS"
        StatusBarManager.TILE_ADD_REQUEST_ERROR_MISMATCHED_PACKAGE -> "ERROR_MISMATCHED_PACKAGE"
        StatusBarManager.TILE_ADD_REQUEST_ERROR_BAD_COMPONENT -> "ERROR_BAD_COMPONENT"
        StatusBarManager.TILE_ADD_REQUEST_ERROR_NOT_CURRENT_USER -> "ERROR_NOT_CURRENT_USER"
        StatusBarManager.TILE_ADD_REQUEST_ERROR_NO_STATUS_BAR_SERVICE -> "ERROR_NO_STATUS_BAR_SERVICE"
        else -> "UNKNOWN"
    }

    private fun requestNotificationPermissionIfNeeded() {
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotificationPermission()
    }

    private fun requestNotificationPermission() {
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * Open a Settings screen for a grant that cannot be requested in-app.
     *
     * Guarded: `PermissionHealth.canOpen` has already been consulted by the caller, but a
     * screen can disappear between the check and the tap on an OEM build.
     */
    private fun openSettings(intent: Intent) {
        runCatching { startActivity(intent) }
            .onFailure { Log.w(TAG, "cannot open ${intent.action}", it) }
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
