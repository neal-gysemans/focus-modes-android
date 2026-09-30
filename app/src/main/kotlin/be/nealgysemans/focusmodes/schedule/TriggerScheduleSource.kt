package be.nealgysemans.focusmodes.schedule

import be.nealgysemans.focusmodes.data.TriggerDao
import be.nealgysemans.focusmodes.data.TriggerEntity
import be.nealgysemans.focusmodes.data.TriggerType
import be.nealgysemans.focusmodes.engine.ScheduleSource
import be.nealgysemans.focusmodes.engine.ScheduleWindow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.time.ZonedDateTime

/**
 * Turns stored schedule triggers into the clock-only answer `ModeEngine.reconcile`
 * needs: "which mode should be on right now?".
 *
 * All JSON decoding happens here so the engine never parses strings, and all clock
 * arithmetic lives in `ScheduleWindows.kt` so it can be tested without Room. Windows
 * that fail to decode are dropped rather than throwing — one corrupt row must not
 * stop the whole reconcile.
 */
class TriggerScheduleSource(private val triggerDao: TriggerDao) : ScheduleSource {

    override fun modeIdActiveAt(now: ZonedDateTime): String? = windows().modeIdActiveAt(now)

    override fun nextBoundaryAfter(now: ZonedDateTime): ZonedDateTime? =
        windows().nextBoundaryAfter(now)

    private fun windows(): List<ScheduleWindow> =
        runBlocking { triggerDao.getEnabledTriggers() }
            .filter { it.type == TriggerType.SCHEDULE }
            .mapNotNull(TriggerEntity::toWindowOrNull)
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
