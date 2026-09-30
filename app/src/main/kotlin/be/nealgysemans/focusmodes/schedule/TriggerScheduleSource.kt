package be.nealgysemans.focusmodes.schedule

import be.nealgysemans.focusmodes.data.TriggerDao
import be.nealgysemans.focusmodes.data.TriggerEntity
import be.nealgysemans.focusmodes.data.TriggerType
import be.nealgysemans.focusmodes.engine.ScheduleSource
import be.nealgysemans.focusmodes.engine.ScheduleWindow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicReference

/**
 * Turns stored schedule triggers into the clock-only answer `ModeEngine.reconcile`
 * needs: "which mode should be on right now?".
 *
 * All JSON decoding happens here so the engine never parses strings, and all clock
 * arithmetic lives in `ScheduleWindows.kt` so it can be tested without Room. Windows
 * that fail to decode are dropped rather than throwing — one corrupt row must not
 * stop the whole reconcile.
 *
 * ## Snapshotting
 *
 * Holds the decoded windows, for the same reason and on the same terms as
 * `data/RoomModeCatalog`: both of its methods are called once per reconcile pass —
 * [modeIdActiveAt] by the engine and [nextBoundaryAfter] by `AlarmScheduler` immediately
 * afterwards — so without this every pass was two SQLite queries and two rounds of JSON
 * parsing for one unchanged answer.
 *
 * Anything that writes the trigger table must call [invalidate], and there is exactly one
 * place that does: `di/AppGraph`'s schedule write protocols, which invalidate before they
 * reconcile. A deliberate, visible coupling rather than a cache with an invisible TTL — and
 * the failure it is trading against is visible too, because a stale snapshot means the
 * schedule the user just switched on does not fire.
 *
 * Same threading contract as the catalog: only ever touched from the engine's single
 * background dispatcher, with the [AtomicReference] there for the publication guarantee
 * rather than for contention.
 */
class TriggerScheduleSource(private val triggerDao: TriggerDao) : ScheduleSource {

    private val snapshot = AtomicReference<List<ScheduleWindow>?>(null)

    override fun modeIdActiveAt(now: ZonedDateTime): String? = windows().modeIdActiveAt(now)

    override fun nextBoundaryAfter(now: ZonedDateTime): ZonedDateTime? =
        windows().nextBoundaryAfter(now)

    /** Drop the snapshot after a trigger write so the next reconcile re-reads and re-parses. */
    fun invalidate() = snapshot.set(null)

    private fun windows(): List<ScheduleWindow> =
        snapshot.get() ?: runBlocking { triggerDao.getEnabledTriggers() }
            .filter { it.type == TriggerType.SCHEDULE }
            .mapNotNull(TriggerEntity::toWindowOrNull)
            .also(snapshot::set)
}

/**
 * Decode `params_json` into a [ScheduleWindow], or null if the row is unusable.
 *
 * Shape: `{"start":540,"end":1020,"days":[1,2,3,4,5]}` with ISO day-of-week
 * values (1 = Monday) and minutes-of-day.
 */
internal fun TriggerEntity.toWindowOrNull(): ScheduleWindow? = runCatching {
    val json = JSONObject(paramsJson)
    val days = json.getJSONArray("days")
    ScheduleWindow(
        triggerId = id,
        modeId = modeId,
        startMinuteOfDay = json.getInt("start"),
        endMinuteOfDay = json.getInt("end"),
        daysOfWeek = (0 until days.length()).map(days::getInt).toSet(),
    )
}.getOrNull()
