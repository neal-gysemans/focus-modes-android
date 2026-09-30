package be.nealgysemans.focusmodes

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import be.nealgysemans.focusmodes.di.AppGraph
import be.nealgysemans.focusmodes.notification.SurfaceSync
import be.nealgysemans.focusmodes.zen.ZenStatusReceiver

/**
 * Process entry point. Owns the single [AppGraph].
 *
 * Nothing heavy happens in `onCreate`: the graph's members are all `by lazy`, and
 * the first reconcile is deferred until a real entry point asks for it (tile,
 * boot, alarm, or the app coming to the foreground). A focus app that opens SQLite on
 * every cold start of every process would show up as a launch regression on the OEM
 * builds this app has to survive.
 *
 * The two things that *are* wired up here both exist because the app cannot trust the
 * system to tell it everything:
 *  - a process-lifetime registration of [ZenStatusReceiver], because an
 *    activity-scoped receiver misses every change made while the UI is closed; and
 *  - a reconcile when the app is brought to the foreground, because grants and system
 *    rules change behind the app's back (Settings edits, OEM battery managers).
 */
class FocusModesApplication : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph.create(this)
        graph.statusNotifier.ensureChannel()
        ZenStatusReceiver.register(this)
        registerActivityLifecycleCallbacks(ForegroundReconciler(graph))
        // Cold-process coverage for the surfaces: an alarm- or boot-driven mode change
        // must still update the tile cache and the ongoing notification even when no
        // activity, tile, or receiver of the surface module ever starts. Idempotent.
        SurfaceSync.start(this)
    }
}

/**
 * Reconciles when the app comes to the foreground.
 *
 * `ActivityLifecycleCallbacks` rather than `ProcessLifecycleOwner`: it is already
 * part of `Application`, so it costs no extra dependency, and the one thing
 * `ProcessLifecycleOwner` buys — not firing twice across a configuration change — is
 * covered here by the time guard, which also collapses activity-to-activity hops into
 * a single reconcile. A reconcile is idempotent, so the guard is an efficiency
 * measure, not a correctness one.
 */
private class ForegroundReconciler(private val graph: AppGraph) :
    Application.ActivityLifecycleCallbacks {

    private var lastReconcileAt = 0L

    override fun onActivityResumed(activity: Activity) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastReconcileAt < MIN_INTERVAL_MS) return
        lastReconcileAt = now
        Log.d(TAG, "app in foreground; reconciling")
        graph.reconcileAsync()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    private companion object {
        const val TAG = "ForegroundReconciler"

        /** Long enough to swallow a rotation or an activity hop, short enough to be invisible. */
        const val MIN_INTERVAL_MS = 2_000L
    }
}
