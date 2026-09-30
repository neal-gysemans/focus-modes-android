package be.nealgysemans.focusmodes.tile

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import android.util.Log

/**
 * Asks the platform to give [FocusTileService] a listening window so it can repaint.
 *
 * The tile is declared `ACTIVE_TILE`, which means it is only handed `qsTile` when the
 * shade opens *or* when the app asks for it. Every state change that happens outside
 * the tile — the app's switches, the long-press grid, the notification's "Turn off",
 * a schedule boundary — therefore has to come through here, or the tile shows
 * yesterday's state the next time the user pulls the shade down.
 *
 * Wrapped in `runCatching` because this is a call into the system UI process: on OEM
 * builds it can throw or be a no-op, and a failed repaint must never take down the
 * surface that triggered it.
 */
object TileNudge {

    fun refresh(context: Context) {
        runCatching {
            TileService.requestListeningState(
                context.applicationContext,
                ComponentName(context.applicationContext, FocusTileService::class.java),
            )
        }.onFailure { Log.w(TAG, "requestListeningState failed", it) }
    }

    private const val TAG = "TileNudge"
}
