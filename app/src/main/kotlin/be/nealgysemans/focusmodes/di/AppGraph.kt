package be.nealgysemans.focusmodes.di

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import be.nealgysemans.focusmodes.FocusModesApplication
import be.nealgysemans.focusmodes.data.ActiveStatePreferences
import be.nealgysemans.focusmodes.data.FocusModesDatabase
import be.nealgysemans.focusmodes.data.ModeEntity
import be.nealgysemans.focusmodes.data.RoomModeCatalog
import be.nealgysemans.focusmodes.data.TriggerEntity
import be.nealgysemans.focusmodes.engine.ActivationSource
import be.nealgysemans.focusmodes.engine.Direction
import be.nealgysemans.focusmodes.engine.ModeEngine
import be.nealgysemans.focusmodes.engine.Transition
import be.nealgysemans.focusmodes.engine.TriggerEvent
import be.nealgysemans.focusmodes.health.PermissionHealth
import be.nealgysemans.focusmodes.notification.StatusNotifier
import be.nealgysemans.focusmodes.schedule.AlarmScheduler
import be.nealgysemans.focusmodes.schedule.TriggerScheduleSource
import be.nealgysemans.focusmodes.tile.TileNudge
import be.nealgysemans.focusmodes.tile.TileStateCache
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
 * through [submitAsync], [submitUserToggleAsync] or [reconcileAsync], never by touching
 * the zen adapter — which is why it, the engine, the catalog, the schedule source and the
 * alarm scheduler are all private. What leaves this class is [database] (read-only flows
 * for the UI), the two DataStore-backed stores, the notifier, the health checks, and the
 * write protocols below.
 *
 * ## Write protocols
 *
 * A write to Room is never just a write. A mode edit has to invalidate [RoomModeCatalog]'s
 * snapshot — the engine reads it synchronously and it has no TTL — and push the new name
 * and colour into the mode's `AutomaticZenRule`; a schedule edit has to invalidate
 * `TriggerScheduleSource` and re-arm the boundary alarm. Getting one of those steps wrong
 * fails silently: the schedule simply never fires, and nothing logs anything. So the
 * sequences live here as [saveModeAsync], [saveScheduleAsync] and [deleteScheduleAsync]
 * rather than in the screen that happens to trigger them.
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

    /**
     * Room. Public for its **read** flows only — `ui/ModeListScreen` and
     * `tile/TileSnapshotSource` collect them straight into a composition. Writes go
     * through the protocols below, which is why nothing outside this class calls a DAO's
     * `upsert` or `delete` any more.
     */
    val database: FocusModesDatabase by lazy { FocusModesDatabase.build(appContext) }

    private val modeCatalog: RoomModeCatalog by lazy { RoomModeCatalog(database.modeDao()) }

    val activeStateStore: ActiveStatePreferences by lazy { ActiveStatePreferences(appContext) }

    private val scheduleSource: TriggerScheduleSource by lazy {
        TriggerScheduleSource(database.triggerDao())
    }

    private val zenAdapter: SystemZenAdapter by lazy {
        SystemZenAdapter(
            context = appContext,
            catalog = modeCatalog,
            onRuleIdResolved = { modeId, ruleId -> modeCatalog.setZenRuleId(modeId, ruleId) },
        )
    }

    private val engine: ModeEngine by lazy {
        ModeEngine(
            clock = Clock.systemDefaultZone(),
            catalog = modeCatalog,
            schedules = scheduleSource,
            state = activeStateStore,
            zen = zenAdapter,
        )
    }

    private val alarmScheduler: AlarmScheduler by lazy {
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

    /**
     * A toggle the *user* asked for, with the three steps every surface owes it.
     *
     * Five surfaces turn a mode on or off by hand — the app's switches, the tile's
     * long-press grid, the widget's picker, the widget's own buttons and the ongoing
     * notification's "Turn off" — and all five had this trio open-coded, which is five
     * chances for one of them to forget a step. They did: the notification's receiver had
     * no `goAsync`, so its engine work raced process death.
     *
     * The steps, and why each is here rather than left to `notification/SurfaceSync`:
     *
     *  - **Flip the tile cache.** `SurfaceSync` will overwrite this with engine truth
     *    within milliseconds, but the user's next glance is often at the shade, and a
     *    second tap arriving before the first lands must see what is on screen.
     *  - **Submit to the engine.** The only thing that actually changes state, and the
     *    only path to it — no surface touches an `AutomaticZenRule`.
     *  - **Nudge the tile.** An `ACTIVE_TILE` repaints only when asked, and the tile has
     *    a far tighter repaint window than any other surface.
     *
     * [onComplete] is for broadcast receivers, which must hold their `goAsync` result open
     * until the engine is done. `FocusTileService` deliberately does **not** call this: it
     * paints itself from the snapshot it already holds, and a tile must never nudge itself.
     */
    fun submitUserToggleAsync(event: TriggerEvent, onComplete: (() -> Unit)? = null) {
        TileStateCache.flipTo(event.modeId.takeIf { event.direction == Direction.ACTIVATE })
        submitAsync(event, onComplete)
        TileNudge.refresh(appContext)
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

    // ------------------------------------------------------------- write protocols

    /**
     * Persist an edited mode, and do the three things that follow from it.
     *
     * [RoomModeCatalog] holds a snapshot the engine reads synchronously and must be
     * invalidated by hand; `reconcile` pushes the new name and people policy into the
     * mode's `AutomaticZenRule` so the system's own Modes screen agrees with ours; and the
     * tile has to repaint because its glyph and subtitle may just have changed. The Room
     * flow takes care of the mode list on its own.
     *
     * The write runs on the engine thread, which is what makes the invalidate safe: the
     * catalog's contract is that only that thread touches it.
     */
    fun saveModeAsync(mode: ModeEntity, onComplete: (() -> Unit)? = null) {
        onEngineThread(onComplete) {
            runBlocking { database.modeDao().upsert(mode) }
            modeCatalog.invalidate()
            val transition = engine.reconcile()
            Log.d(TAG, "saveMode(${mode.id}) -> $transition")
            afterTransition(transition)
            TileNudge.refresh(appContext)
        }
    }

    /**
     * Persist one schedule row — new or edited, enabled or not — and let the engine work
     * out what it means.
     *
     * Insert and update are one operation because the editor cannot usefully tell them
     * apart: it hands back a complete row and Room upserts it.
     *
     * Unlike [saveModeAsync] this invalidates the *schedule* snapshot, and it does have to:
     * `TriggerScheduleSource` caches the decoded windows for the same reason the catalog
     * caches modes, so a schedule the user just switched on would otherwise not exist as
     * far as this reconcile is concerned. Arming the alarm needs no separate step —
     * [afterTransition] re-arms after every transition, unconditionally.
     *
     * The tile repaint is last rather than alongside: a schedule the user just switched on
     * may turn a mode on *now*, and nudging the tile before the engine has decided repaints
     * it with the old answer.
     */
    fun saveScheduleAsync(trigger: TriggerEntity, onComplete: (() -> Unit)? = null) {
        onEngineThread(onComplete) {
            runBlocking { database.triggerDao().upsert(trigger) }
            afterScheduleWrite("saveSchedule(${trigger.id})")
        }
    }

    /** Remove one schedule row, with the same follow-ups as [saveScheduleAsync]. */
    fun deleteScheduleAsync(trigger: TriggerEntity, onComplete: (() -> Unit)? = null) {
        onEngineThread(onComplete) {
            runBlocking { database.triggerDao().delete(trigger) }
            afterScheduleWrite("deleteSchedule(${trigger.id})")
        }
    }

    private fun afterScheduleWrite(operation: String) {
        scheduleSource.invalidate()
        val transition = engine.reconcile()
        Log.d(TAG, "$operation -> $transition")
        afterTransition(transition)
        TileNudge.refresh(appContext)
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
                        modeCatalog.setZenRuleId(modeId, null)
                    }
                    val transition = engine.onEvent(
                        TriggerEvent(ActivationSource.USER, modeId, Direction.DEACTIVATE),
                    )
                    Log.d(TAG, "system turned $modeId off -> $transition")
                    afterTransition(transition)
                }

                ZenRuleStatus.ON -> adoptSystemActivation(ruleId, modeId)

                ZenRuleStatus.RECONCILE -> reconcileNow()
            }
        }
    }

    /**
     * The `ON` arm: work out whether this is news, and adopt it if it is.
     *
     * Three outcomes, narrowing:
     *  - it is the ~50 ms echo of a write this process just made, so there is nothing to
     *    learn from it and nothing to do;
     *  - the app does not believe [modeId] is on, so a human turned it on from a system
     *    surface (`setManualInvocationAllowed(true)` is what makes that possible) — adopt
     *    it as a `USER` activation rather than leaving the app and the phone disagreeing;
     *  - the app already agrees, so reconcile, which is always safe.
     */
    private fun adoptSystemActivation(ruleId: String, modeId: String) {
        if (zenAdapter.wasSelfInitiated(ruleId)) {
            Log.d(TAG, "ignoring the echo of our own activation of $modeId")
            return
        }
        if (activeStateStore.read().activeModeId == modeId) {
            reconcileNow()
            return
        }
        val transition = engine.onEvent(
            TriggerEvent(ActivationSource.USER, modeId, Direction.ACTIVATE),
        )
        Log.i(TAG, "adopting a system-side activation of $modeId -> $transition")
        afterTransition(transition)
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
     * The one side effect that follows a state change and is not part of the decision:
     * the next boundary alarm.
     *
     * Deliberately outside `ModeEngine` so the reducer stays pure and testable.
     *
     * Everything *else* a state change implies — the ongoing notification, the tile's
     * label, the widget, the last-used pointer — is deliberately **not** here.
     * `notification/SurfaceSync` observes the same Room and DataStore state this
     * transition just wrote and fans it out to every surface from one place. Reaching
     * those surfaces from here as well would give each of them two writers, which is the
     * drift bug that observer exists to prevent; the one thing it cannot own is the
     * alarm, because arming the next boundary is not something any surface renders.
     */
    private fun afterTransition(transition: Transition) {
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
