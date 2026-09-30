package be.nealgysemans.focusmodes.di

import android.content.Context
import android.util.Log
import be.nealgysemans.focusmodes.FocusModesApplication
import be.nealgysemans.focusmodes.data.ActiveStatePreferences
import be.nealgysemans.focusmodes.data.FocusModesDatabase
import be.nealgysemans.focusmodes.data.RoomModeCatalog
import be.nealgysemans.focusmodes.engine.ActivationSource
import be.nealgysemans.focusmodes.engine.Direction
import be.nealgysemans.focusmodes.engine.ModeEngine
import be.nealgysemans.focusmodes.engine.Transition
import be.nealgysemans.focusmodes.engine.TriggerEvent
import be.nealgysemans.focusmodes.health.PermissionHealth
import be.nealgysemans.focusmodes.notification.StatusNotifier
import be.nealgysemans.focusmodes.schedule.AlarmScheduler
import be.nealgysemans.focusmodes.schedule.TriggerScheduleSource
import be.nealgysemans.focusmodes.zen.SystemZenAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.util.concurrent.Executors

/**
 * Manual dependency graph — a service locator, on purpose.
 *
 * No Hilt: the object graph is a dozen singletons with no scoping beyond "one per
 * process", and the entry points are broadcast receivers and a `TileService`, which
 * an annotation processor buys nothing for. Keeping it a plain class means the whole
 * wiring is readable in one screen and the build has no extra codegen step.
 *
 * Every entry point (tile, alarm, boot, notification action, UI) reaches the engine
 * through [submitAsync] or [reconcileAsync], never by touching [zenAdapter].
 */
class AppGraph private constructor(private val appContext: Context) {

    /**
     * The engine's home thread.
     *
     * Single-threaded and explicit: it serialises every trigger, which is what lets
     * `ModeEngine`, [RoomModeCatalog] and [ActiveStatePreferences] all present
     * synchronous APIs without locking. Two triggers racing (tile tap during a
     * schedule boundary) would otherwise be able to interleave a read and a write
     * and break the single-active invariant.
     */
    private val engineDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "focus-engine")
    }.asCoroutineDispatcher()

    private val engineScope = CoroutineScope(SupervisorJob() + engineDispatcher)

    val database: FocusModesDatabase by lazy { FocusModesDatabase.build(appContext) }

    val modeCatalog: RoomModeCatalog by lazy { RoomModeCatalog(database.modeDao()) }

    val activeStateStore: ActiveStatePreferences by lazy { ActiveStatePreferences(appContext) }

    val scheduleSource: TriggerScheduleSource by lazy {
        TriggerScheduleSource(database.triggerDao())
    }

    val zenAdapter: SystemZenAdapter by lazy {
        SystemZenAdapter(
            context = appContext,
            catalog = modeCatalog,
            onRuleIdResolved = { modeId, ruleId ->
                runBlocking { database.modeDao().setZenRuleId(modeId, ruleId) }
                modeCatalog.invalidate()
            },
        )
    }

    val engine: ModeEngine by lazy {
        ModeEngine(
            clock = Clock.systemDefaultZone(),
            catalog = modeCatalog,
            schedules = scheduleSource,
            state = activeStateStore,
            zen = zenAdapter,
        )
    }

    val alarmScheduler: AlarmScheduler by lazy {
        AlarmScheduler(appContext, scheduleSource, Clock.systemDefaultZone())
    }

    val statusNotifier: StatusNotifier by lazy { StatusNotifier(appContext) }

    val permissionHealth: PermissionHealth by lazy { PermissionHealth(appContext) }

    /** Hand a trigger to the engine on its own thread. Fire and forget. */
    fun submitAsync(event: TriggerEvent) {
        engineScope.launch {
            val transition = engine.onEvent(event)
            Log.d(TAG, "onEvent($event) -> $transition")
            afterTransition(transition)
        }
    }

    /** Ask the engine to recompute and converge. Fire and forget. */
    fun reconcileAsync() {
        engineScope.launch {
            val transition = engine.reconcile()
            Log.d(TAG, "reconcile() -> $transition")
            afterTransition(transition)
        }
    }

    /**
     * Toggle the tile's target mode.
     *
     * Reading the current state happens on the engine thread, not the caller's, so
     * the tile's `onClick` never blocks the main thread on DataStore or SQLite.
     */
    fun toggleDefaultModeAsync() {
        engineScope.launch {
            val modeId = modeCatalog.modes().firstOrNull()?.id ?: run {
                Log.w(TAG, "toggle requested with no modes defined")
                return@launch
            }
            val alreadyOn = activeStateStore.read().activeModeId == modeId
            val transition = engine.onEvent(
                TriggerEvent(
                    source = ActivationSource.USER,
                    modeId = modeId,
                    direction = if (alreadyOn) Direction.DEACTIVATE else Direction.ACTIVATE,
                ),
            )
            Log.d(TAG, "toggle($modeId) -> $transition")
            afterTransition(transition)
        }
    }

    /**
     * Side effects that follow a state change but are not part of the decision:
     * the status notification, the tile's label and the next alarm.
     *
     * Deliberately outside `ModeEngine` so the reducer stays pure and testable.
     */
    private fun afterTransition(transition: Transition) {
        // TODO(skeleton): post/clear the StatusNotifier, call
        //  TileService.requestListeningState so the active tile repaints, and
        //  alarmScheduler.rearm() after any change that could move the next boundary.
        when (transition) {
            is Transition.Activated, is Transition.Deactivated -> alarmScheduler.rearm()
            else -> Unit
        }
    }

    companion object {
        private const val TAG = "AppGraph"

        @Volatile
        private var fallback: AppGraph? = null

        /**
         * The process-wide graph.
         *
         * Normally the [FocusModesApplication] instance owns it. The fallback path
         * exists because a manifest-registered receiver can be started in a process
         * where a custom Application subclass is not guaranteed to be the one
         * responding (multi-process OEM builds), and a receiver silently doing
         * nothing is worse than a second graph.
         */
        fun from(context: Context): AppGraph {
            (context.applicationContext as? FocusModesApplication)?.let { return it.graph }
            return fallback ?: synchronized(this) {
                fallback ?: AppGraph(context.applicationContext).also { fallback = it }
            }
        }

        /** Called once by [FocusModesApplication]. */
        internal fun create(context: Context): AppGraph = AppGraph(context.applicationContext)
    }
}
