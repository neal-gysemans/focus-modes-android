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
 * All JSON decoding happens here so the engine never parses strings. Windows that
 * fail to decode are dropped rather than throwing — one corrupt row must not stop
 * the whole reconcile.
 */
class TriggerScheduleSource(private val triggerDao: TriggerDao) : ScheduleSource {

    override fun modeIdActiveAt(now: ZonedDateTime): String? {
        val minute = now.hour * 60 + now.minute
        val today = now.dayOfWeek.value
        val yesterday = now.minusDays(1).dayOfWeek.value

        // Last match wins, which makes the answer deterministic when the user has
        // overlapping windows. Sort order in the DAO query fixes "last".
        return windows().lastOrNull { window ->
            if (window.wrapsMidnight) {
                (today in window.daysOfWeek && minute >= window.startMinuteOfDay) ||
                    (yesterday in window.daysOfWeek && minute < window.endMinuteOfDay)
            } else {
                today in window.daysOfWeek &&
                    minute >= window.startMinuteOfDay &&
                    minute < window.endMinuteOfDay
            }
        }?.modeId
    }

    override fun nextBoundaryAfter(now: ZonedDateTime): ZonedDateTime? {
        val windows = windows()
        if (windows.isEmpty()) return null

        // Brute-force the next week of boundaries. Cheap (a few dozen candidates),
        // and immune to the off-by-one-day bugs that arithmetic on wrapping
        // windows invites.
        val base = now.truncatedTo(java.time.temporal.ChronoUnit.MINUTES)
        return (0..DAYS_TO_SCAN).asSequence()
            .flatMap { dayOffset ->
                val day = base.plusDays(dayOffset.toLong())
                windows.asSequence().flatMap { window ->
                    sequenceOf(window.startMinuteOfDay, window.endMinuteOfDay)
                        .map { day.toLocalDate().atStartOfDay(day.zone).plusMinutes(it.toLong()) }
                }
            }
            .filter { it.isAfter(now) }
            .minOrNull()
    }

    private fun windows(): List<ScheduleWindow> =
        runBlocking { triggerDao.getEnabledTriggers() }
            .filter { it.type == TriggerType.SCHEDULE }
            .mapNotNull(TriggerEntity::toWindowOrNull)

    private companion object {
        const val DAYS_TO_SCAN = 8
    }
}

/** True when the window runs past midnight, e.g. 23:00 to 07:00. */
internal val ScheduleWindow.wrapsMidnight: Boolean
    get() = endMinuteOfDay <= startMinuteOfDay

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
