package be.nealgysemans.tilespike

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Keeps [StateCache] mirroring DataStore for the whole process.
 *
 * The TileService lives in this same process, so one collector serves both it and the
 * activities: a change made in the app is visible to `onClick()` without the tile ever
 * touching disk. The tile still force-warms the cache in `onStartListening()`, because
 * this collector is asynchronous and the very first tap after a cold service start could
 * otherwise race it.
 */
class SpikeApp : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val store = SpikeStore(this)
        scope.launch {
            store.state.onEach { StateCache.publish(it) }.collect { }
        }
    }
}
