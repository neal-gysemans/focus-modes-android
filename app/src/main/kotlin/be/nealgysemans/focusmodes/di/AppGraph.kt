package be.nealgysemans.focusmodes.di

import android.app.NotificationManager
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
import be.nealgysemans.focusmodes.zen.ZenRuleStatus
import be.nealgysemans.focusmodes.zen.zenRuleStatusOf
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

    /**
     * Hand a trigger to the engine on its own thread.
     *
     * [onComplete] exists for broadcast receivers: a receiver must hold its
     * `goAsync` result open until the engine is done, or the process can be killed
     * mid-transition. UI and tile callers pass nothing.
     */
    fun submitAsync(event: TriggerEvent, onComplete: (() -> Unit)? = null) {
        onEngineThread(onComplete) {
            val transition = engine.onEvent(event)
            Log.d(TAG, "onEvent($event) -> $transition")
            afterTransition(transition)
        }
    }

    /** Ask the engine to recompute and converge, then re-arm the next boundary. */
    fun reconcileAsync(onComplete: (() -> Unit)? = null) {
        onEngineThread(onComplete) {
            val transition = engine.reconcile()
            Log.d(TAG, "reconcile() -> $transition")
            afterTransition(transition)
        }
    }

    /** Re-plant the boundary alarm without touching mode state. */
    fun rearmAsync(onComplete: (() -> Unit)? = null) {
        onEngineThread(onComplete) { alarmScheduler.rearm() }
    }

    /**
     * React to `ACTION_AUTOMATIC_ZEN_RULE_STATUS_CHANGED` (see `zen/ZenStatusReceiver`).
     *
     * The mapping from rule id to mode is a catalog lookup, not adapter state, so it
     * works in a process that was just woken by the broadcast.
     *
     * Three cases, and the asymmetry between them is the point:
     *  - **Off** (snoozed by the user, disabled or deleted in Settings): feed a
     *    `USER`/`DEACTIVATE` event. That clears the pin — `healDrift` must never
     *    fight a user who turned a mode off from a system surface — and the engine's
     *    own `STATE_FALSE` write is what lifts the platform's snooze so the next
     *    schedule cycle can activate again.
     *  - **On**, and the app does not already believe this mode is on: a human turned
     *    it on from a system surface (`setManualInvocationAllowed(true)` makes that
     *    possible), so adopt it as a user activation rather than letting the app and
     *    the phone disagree.
     *  - anything else: reconcile, which is always safe.
     */
    fun onZenRuleStatusChanged(ruleId: String?, status: Int, onComplete: (() -> Unit)? = null) {
        onEngineThread(onComplete) {
            if (ruleId == null) {
                Log.w(TAG, "zen status change without a rule id; reconciling")
                reconcileNow()
                return@onEngineThread
            }
            val modeId = modeCatalog.modes().firstOrNull { it.zenRuleId == ruleId }?.id
            if (modeId == null) {
                // Another app's rule, or one of ours whose id we have not cached yet.
                Log.d(TAG, "zen status change for unknown rule $ruleId; ignored")
                return@onEngineThread
            }

            when (zenRuleStatusOf(status)) {
                ZenRuleStatus.OFF -> {
                    if (status == NotificationManager.AUTOMATIC_RULE_STATUS_REMOVED) {
                        // The rule object is gone; drop the stale id so the next
                        // reconcile re-creates it instead of updating a ghost.
                        Log.i(TAG, "rule $ruleId for $modeId was removed; clearing cached id")
                        runBlocking { database.modeDao().setZenRuleId(modeId, null) }
                        modeCatalog.invalidate()
                    }
                    val transition = engine.onEvent(
                        TriggerEvent(ActivationSource.USER, modeId, Direction.DEACTIVATE),
                    )
                    Log.d(TAG, "system turned $modeId off -> $transition")
                    afterTransition(transition)
                }

                ZenRuleStatus.ON -> when {
                    zenAdapter.wasSelfInitiated(ruleId) ->
                        Log.d(TAG, "ignoring the echo of our own activation of $modeId")

                    activeStateStore.read().activeModeId != modeId -> {
                        val transition = engine.onEvent(
                            TriggerEvent(ActivationSource.USER, modeId, Direction.ACTIVATE),
                        )
                        Log.i(TAG, "adopting a system-side activation of $modeId -> $transition")
                        afterTransition(transition)
                    }

                    else -> reconcileNow()
                }

                ZenRuleStatus.RECONCILE -> reconcileNow()
            }
        }
    }

    private fun reconcileNow() {
        val transition = engine.reconcile()
        Log.d(TAG, "reconcile() -> $transition")
        afterTransition(transition)
    }

    /**
     * Serialise one unit of work onto the engine thread.
     *
     * Failures are logged rather than thrown: these run inside broadcast receivers,
     * and [onComplete] must fire even when the work blew up, or the receiver's
     * `goAsync` result is never released.
     */
    private fun onEngineThread(onComplete: (() -> Unit)?, work: () -> Unit) {
        engineScope.launch {
            try {
                work()
            } catch (t: Throwable) {
                Log.e(TAG, "engine work failed", t)
            } finally {
                onComplete?.invoke()
            }
        }
    }

    /**
     * Toggle the tile's target mode.
     *
     * Reading the current state happens on the engine thread, not the caller's, so
     * the tile's `onClick` never blocks the main thread on DataStore or SQLite.
     */
    fun toggleDefaultModeAsync() {
        onEngineThread(onComplete = null) {
            val modeId = modeCatalog.modes().firstOrNull()?.id ?: run {
                Log.w(TAG, "toggle requested with no modes defined")
                return@onEngineThread
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
        // TODO(skeleton): post/clear the StatusNotifier and call
        //  TileService.requestListeningState so the active tile repaints.
        //
        // Re-arming is unconditional, including after Transition.NoChange. A boot, a
        // doze-killed alarm or a revoked exact-alarm grant all leave nothing armed
        // while the state is already correct, so "only re-arm when something changed"
        // is exactly the case that silently stops every schedule.
        alarmScheduler.rearm()
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
