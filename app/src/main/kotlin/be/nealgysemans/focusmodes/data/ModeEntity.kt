package be.nealgysemans.focusmodes.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import be.nealgysemans.focusmodes.engine.FocusMode
import be.nealgysemans.focusmodes.engine.ModeEffects
import be.nealgysemans.focusmodes.engine.PeopleFilter

/**
 * A focus mode as stored in Room — **the source of truth**.
 *
 * The matching `AutomaticZenRule` in the system is treated as a cache: the user
 * can edit a rule in Settings, after which the platform may refuse further app
 * updates to it, so the app must never read its own configuration back out of
 * the system.
 *
 * @property zenRuleId the system-assigned rule id, cached so reconcile updates
 *   the existing rule instead of creating a duplicate. Null until first sync.
 * @property sortOrder display order in the list and on the tile's picker.
 */
@Entity(tableName = "modes")
data class ModeEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** Stable key into the app's bundled icon set, not a resource id (those move). */
    val iconKey: String,
    /** ARGB accent colour. */
    val color: Int,
    @ColumnInfo(name = "calls_from") val callsFrom: PeopleFilter,
    @ColumnInfo(name = "messages_from") val messagesFrom: PeopleFilter,
    @ColumnInfo(name = "repeat_callers") val repeatCallers: Boolean,
    @Embedded(prefix = "effect_") val effects: EffectsColumns,
    @ColumnInfo(name = "zen_rule_id") val zenRuleId: String? = null,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
)

/**
 * The `ZenDeviceEffects` toggles, flattened into the modes table.
 *
 * Embedded rather than a separate table because they are always read and written
 * with their mode and have no independent identity.
 */
data class EffectsColumns(
    val grayscale: Boolean = false,
    @ColumnInfo(name = "dim_wallpaper") val dimWallpaper: Boolean = false,
    @ColumnInfo(name = "night_mode") val nightMode: Boolean = false,
)

/** Entity to domain. Mapping lives here so `engine/` stays free of Room. */
fun ModeEntity.toDomain(): FocusMode = FocusMode(
    id = id,
    name = name,
    iconKey = iconKey,
    color = color,
    callsFrom = callsFrom,
    messagesFrom = messagesFrom,
    repeatCallers = repeatCallers,
    effects = ModeEffects(
        grayscale = effects.grayscale,
        dimWallpaper = effects.dimWallpaper,
        nightMode = effects.nightMode,
    ),
    zenRuleId = zenRuleId,
)

// No `FocusMode.toEntity`. The mapping only ever needs to go one way: Room is the source of
// truth, the editor edits a [ModeEntity] directly and hands one back, and `FocusMode` exists
// so `engine/` can stay free of Room rather than as a form the UI fills in. A reverse mapping
// would be the first step towards writing a mode back from the domain copy, which is how the
// `sort_order` and `zen_rule_id` the entity carries and the domain does not get lost.
