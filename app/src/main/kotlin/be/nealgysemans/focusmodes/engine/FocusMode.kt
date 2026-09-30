package be.nealgysemans.focusmodes.engine

/**
 * Who is allowed through a mode's filter for one channel (calls or messages).
 *
 * This maps 1:1 onto `ZenPolicy.PEOPLE_TYPE_*`. The mapping lives in
 * `zen/SystemZenAdapter` on purpose: the filter is enforced **system-side** by
 * `ZenPolicy`, never by the app reading notifications and deciding what to relay.
 */
enum class PeopleFilter {
    /** Starred contacts only — the default for Work/Sleep, mirrors iOS "Allowed People". */
    STARRED,

    /** Anyone in the address book. */
    CONTACTS,

    /** No filtering: everything rings through. */
    ANYONE,

    /** Nothing gets through on this channel. */
    NONE,
}

/**
 * The device-level effects a mode asks for while active.
 *
 * These map onto `ZenDeviceEffects` (API 35+). The system decides whether to
 * honour them; a third-party rule can request them but OEM skins may ignore
 * some, which is exactly what spike #1 exists to find out.
 */
data class ModeEffects(
    val grayscale: Boolean = false,
    val dimWallpaper: Boolean = false,
    val nightMode: Boolean = false,
)

/**
 * A user-defined focus mode — the app's core domain object.
 *
 * Room is the source of truth for these (see `data/ModeEntity`); the
 * `AutomaticZenRule` the system holds is only a cache, because the user can
 * edit a rule in Settings and silently freeze further app updates to it.
 *
 * @property zenRuleId the id returned by `NotificationManager.addAutomaticZenRule`,
 *   cached so we can update rather than duplicate a rule. Null until first sync.
 */
data class FocusMode(
    val id: String,
    val name: String,
    val iconKey: String,
    val color: Int,
    val callsFrom: PeopleFilter,
    val messagesFrom: PeopleFilter,
    val repeatCallers: Boolean,
    val effects: ModeEffects,
    val zenRuleId: String? = null,
)
