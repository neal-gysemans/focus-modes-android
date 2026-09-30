package be.nealgysemans.focusmodes.data

import androidx.room.TypeConverter
import be.nealgysemans.focusmodes.engine.PeopleFilter

/**
 * Enum columns are stored as their **names**, not ordinals.
 *
 * Ordinals would silently remap every stored row the moment someone reorders an
 * enum — a bug that shows up as a user's "starred contacts only" mode quietly
 * becoming "anyone". Unknown names fall back to the safest value rather than
 * throwing, so a downgrade cannot brick the database.
 */
class Converters {

    @TypeConverter
    fun peopleFilterToString(value: PeopleFilter): String = value.name

    @TypeConverter
    fun stringToPeopleFilter(value: String): PeopleFilter =
        PeopleFilter.entries.firstOrNull { it.name == value } ?: PeopleFilter.STARRED

    @TypeConverter
    fun triggerTypeToString(value: TriggerType): String = value.name

    @TypeConverter
    fun stringToTriggerType(value: String): TriggerType =
        TriggerType.entries.firstOrNull { it.name == value } ?: TriggerType.SCHEDULE
}
