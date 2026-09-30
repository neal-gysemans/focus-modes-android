package be.nealgysemans.focusmodes.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The app's only database. Modes and their triggers; nothing else is persisted
 * here (the active-mode pointer lives in DataStore — see [ActiveStatePreferences]).
 *
 * `exportSchema = false` while the schema is still moving; flip it on and commit
 * the JSON schemas before the first release so migrations can be tested.
 */
@Database(
    entities = [ModeEntity::class, TriggerEntity::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class FocusModesDatabase : RoomDatabase() {

    abstract fun modeDao(): ModeDao

    abstract fun triggerDao(): TriggerDao

    companion object {
        private const val NAME = "focus-modes.db"

        /**
         * Open the database, seeding the three starter modes on first create.
         *
         * Seeding happens in a [RoomDatabase.Callback] with raw SQL rather than
         * through the DAOs on purpose: `onCreate` runs inside Room's own
         * transaction, and re-entering the database through a DAO from there
         * deadlocks.
         */
        fun build(context: Context): FocusModesDatabase =
            Room.databaseBuilder(context, FocusModesDatabase::class.java, NAME)
                .addCallback(SeedCallback)
                .build()
    }
}

/** Inserts Work / Sleep / Personal plus their default schedules, once, at create time. */
private object SeedCallback : RoomDatabase.Callback() {

    override fun onCreate(db: SupportSQLiteDatabase) {
        Seed.MODES.forEach(db::execSQL)
        Seed.TRIGGERS.forEach(db::execSQL)
    }
}

/**
 * First-run content.
 *
 * Ids are fixed strings, not generated: the Quick Settings tile and any future
 * shortcut both address a mode by id, so the starter ids must be stable across
 * installs and reinstalls.
 */
internal object Seed {

    const val MODE_WORK = "mode-work"
    const val MODE_SLEEP = "mode-sleep"
    const val MODE_PERSONAL = "mode-personal"

    val MODES: List<String> = listOf(
        insertMode(
            id = MODE_WORK,
            name = "Work",
            iconKey = "briefcase",
            color = 0xFF3D5AFE.toInt(),
            callsFrom = "STARRED",
            messagesFrom = "STARRED",
            repeatCallers = true,
            grayscale = false,
            dimWallpaper = false,
            nightMode = false,
            sortOrder = 0,
        ),
        insertMode(
            id = MODE_SLEEP,
            name = "Sleep",
            iconKey = "moon",
            color = 0xFF7E57C2.toInt(),
            callsFrom = "STARRED",
            messagesFrom = "NONE",
            repeatCallers = true,
            grayscale = true,
            dimWallpaper = true,
            nightMode = true,
            sortOrder = 1,
        ),
        insertMode(
            id = MODE_PERSONAL,
            name = "Personal",
            iconKey = "person",
            color = 0xFF26A69A.toInt(),
            callsFrom = "CONTACTS",
            messagesFrom = "CONTACTS",
            repeatCallers = true,
            grayscale = false,
            dimWallpaper = false,
            nightMode = false,
            sortOrder = 2,
        ),
    )

    /**
     * Minutes-of-day windows; ISO day-of-week (1 = Monday). Sleep wraps midnight
     * (23:00 to 07:00), which is the case `ScheduleSource` implementations have
     * to get right.
     *
     * Seeded **disabled**: silencing a new user's phone on the first night
     * without them asking is a support ticket, so the schedules are visible in
     * the UI but inert until switched on.
     *
     * The `params_json` comes from [scheduleParamsJson], the same writer the editor uses,
     * rather than from a hand-typed literal. `ScheduleParamsTest` asserts that the two agree
     * character for character, and it had to, because they were two independent spellings of
     * one format: a writer that emitted a different shape than the seed would leave an
     * installed database holding two formats in one column, with only the test standing
     * between them. Now there is one spelling and the test confirms it rather than guarding
     * it.
     */
    val TRIGGERS: List<String> = listOf(
        insertTrigger(
            id = "trigger-work-weekdays",
            modeId = MODE_WORK,
            // Weekdays 09:00–17:00.
            paramsJson = scheduleParamsJson(9 * 60, 17 * 60, setOf(1, 2, 3, 4, 5)),
        ),
        insertTrigger(
            id = "trigger-sleep-nightly",
            modeId = MODE_SLEEP,
            // Every night 23:00–07:00.
            paramsJson = scheduleParamsJson(23 * 60, 7 * 60, setOf(1, 2, 3, 4, 5, 6, 7)),
        ),
    )

    private fun insertMode(
        id: String,
        name: String,
        iconKey: String,
        color: Int,
        callsFrom: String,
        messagesFrom: String,
        repeatCallers: Boolean,
        grayscale: Boolean,
        dimWallpaper: Boolean,
        nightMode: Boolean,
        sortOrder: Int,
    ): String = """
        INSERT INTO modes (
            id, name, iconKey, color, calls_from, messages_from, repeat_callers,
            effect_grayscale, effect_dim_wallpaper, effect_night_mode,
            zen_rule_id, sort_order
        ) VALUES (
            '$id', '$name', '$iconKey', $color, '$callsFrom', '$messagesFrom', ${repeatCallers.sql},
            ${grayscale.sql}, ${dimWallpaper.sql}, ${nightMode.sql},
            NULL, $sortOrder
        )
    """.trimIndent()

    private fun insertTrigger(id: String, modeId: String, paramsJson: String): String = """
        INSERT INTO triggers (id, mode_id, type, params_json, enabled)
        VALUES ('$id', '$modeId', 'SCHEDULE', '$paramsJson', 0)
    """.trimIndent()

    private val Boolean.sql: Int get() = if (this) 1 else 0
}
