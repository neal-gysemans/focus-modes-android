package be.nealgysemans.tilespike

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService

/**
 * Out-of-band tile refresh.
 *
 * An ACTIVE_TILE only gets `onStartListening()` when the shade opens or when we ask for
 * it. Any state change that happens outside the tile (the app, the long-press activity,
 * a schedule later on) has to call this, or the tile shows stale state the next time the
 * user pulls the shade down.
 */
object TileRefresher {

    fun refreshFocusTile(context: Context) {
        runCatching {
            TileService.requestListeningState(
                context,
                ComponentName(context, FocusTileService::class.java),
            )
        }
    }

    fun refreshSecondTile(context: Context) {
        runCatching {
            TileService.requestListeningState(
                context,
                ComponentName(context, SecondTileService::class.java),
            )
        }
    }
}
