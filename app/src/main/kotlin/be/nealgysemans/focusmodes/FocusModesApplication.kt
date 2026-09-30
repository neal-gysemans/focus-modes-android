package be.nealgysemans.focusmodes

import android.app.Application
import be.nealgysemans.focusmodes.di.AppGraph

/**
 * Process entry point. Owns the single [AppGraph].
 *
 * Nothing heavy happens in `onCreate`: the graph's members are all `by lazy`, and
 * the first reconcile is deferred until a real entry point asks for it (tile,
 * boot, alarm, or the activity resuming). A focus app that opens SQLite on every
 * cold start of every process would show up as a launch regression on the OEM
 * builds this app has to survive.
 */
class FocusModesApplication : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph.create(this)
        graph.statusNotifier.ensureChannel()
    }
}
