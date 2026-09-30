package be.nealgysemans.focusmodes.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The kinds of trigger a mode can have.
 *
 * Only [SCHEDULE] is implemented. The others are listed so the enum's stored
 * ordinal-free string values are already reserved — adding a value later must
 * not require a Room migration of existing rows.
 */
enum class TriggerType {
    /** A recurring time window; params decode into `engine/ScheduleWindow`. */
    SCHEDULE,

    /** Reserved: geofence entry/exit. */
    LOCATION,

    /** Reserved: a named app coming to the foreground. */
    APP_LAUNCH,
}

/**
 * One reason a mode might turn itself on.
 *
 * Params are stored as JSON rather than typed columns because each trigger type
 * has a different shape and the set of types is expected to grow; the engine
 * never sees this string — `schedule/TriggerScheduleSource` decodes it into
 * `engine/ScheduleWindow` first.
 *
 * Cascade-deleted with its mode: an orphan trigger would fire
 * `IgnoreReason.UNKNOWN_MODE` forever.
 */
@Entity(
    tableName = "triggers",
    foreignKeys = [
        ForeignKey(
            entity = ModeEntity::class,
            parentColumns = ["id"],
            childColumns = ["mode_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("mode_id")],
)
data class TriggerEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "mode_id") val modeId: String,
    val type: TriggerType,
    /** Type-specific payload, e.g. `{"start":540,"end":1020,"days":[1,2,3,4,5]}`. */
    @ColumnInfo(name = "params_json") val paramsJson: String,
    /** User can mute a trigger without deleting it. */
    val enabled: Boolean = true,
)
