package be.nealgysemans.focusmodes.ui

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import be.nealgysemans.focusmodes.R
import be.nealgysemans.focusmodes.di.AppGraph
import be.nealgysemans.focusmodes.engine.Direction
import be.nealgysemans.focusmodes.engine.TriggerEvent
import be.nealgysemans.focusmodes.notification.SurfaceSync
import be.nealgysemans.focusmodes.tile.FocusTileService
import be.nealgysemans.focusmodes.tile.TapBehavior
import be.nealgysemans.focusmodes.tile.TilePreferences
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
                        onSaveMode = graph::saveModeAsync,
                        onTapBehaviorChange = ::setTapBehavior,
                        onSaveSchedule = graph::saveScheduleAsync,
                        onDeleteSchedule = graph::deleteScheduleAsync,
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
     * The flip-submit-nudge trio is `AppGraph.submitUserToggleAsync`'s, shared with the
     * four other surfaces that toggle by hand — this screen's only addition is the
     * permission ask. Turning a mode *on* is the first moment the ongoing notification
     * matters, so that is where the runtime permission is requested, not at launch where
     * the user has no idea what they would be saying yes to.
     */
    private fun toggle(event: TriggerEvent) {
        if (event.direction == Direction.ACTIVATE) requestNotificationPermissionIfNeeded()
        graph.submitUserToggleAsync(event)
    }

    private fun setTapBehavior(behaviour: TapBehavior) {
        lifecycleScope.launch(Dispatchers.IO) { tilePreferences.setTapBehavior(behaviour) }
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
        // Asked of `PermissionHealth`, which owns this question for every other surface
        // too — a second `checkSelfPermission` here is a second place the answer could be
        // computed differently from the card the user is looking at.
        if (!graph.permissionHealth.postNotifications().granted) requestNotificationPermission()
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

/**
 * The intent that opens the app, for the four surfaces that have to build one.
 *
 * `NEW_TASK` because every caller is outside an Activity context or is about to finish —
 * the tile service, the ongoing notification, and the two picker overlays. `CLEAR_TOP`
 * because there is only one Activity: a user arriving from any of those should land on it
 * rather than on a second copy stacked over the first.
 *
 * Shared rather than restated because it was restated four times, and the tile's copy had
 * drifted — it omitted `CLEAR_TOP`, which is the one flag that stops the duplicate.
 */
internal fun mainActivityIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
