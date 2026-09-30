package be.nealgysemans.focusmodes.ui

import androidx.annotation.StringRes
import be.nealgysemans.focusmodes.R
import be.nealgysemans.focusmodes.engine.PeopleFilter

/**
 * What a [PeopleFilter] is called on screen.
 *
 * One mapping, used by both the list row and the editor's selectors, so a mode cannot
 * be described one way where it is read and another where it is set. The enum's own name
 * is a storage detail — `Converters` writes it into the database and `SystemZenAdapter`
 * turns it into a `ZenPolicy.PEOPLE_TYPE_*` — and it has no business reaching a screen;
 * "Calls: STARRED" is a row leaking into the UI, and nobody ever chose a constant.
 *
 * Two forms because the context differs. [plainLabelRes] completes a sentence the row
 * already started ("Calls from: Starred contacts"). [shortLabelRes] goes on a chip sat
 * directly under that sentence, where repeating "contacts" four times is noise.
 */
@get:StringRes
internal val PeopleFilter.plainLabelRes: Int
    get() = when (this) {
        PeopleFilter.STARRED -> R.string.ui_people_starred
        PeopleFilter.CONTACTS -> R.string.ui_people_contacts
        PeopleFilter.ANYONE -> R.string.ui_people_anyone
        PeopleFilter.NONE -> R.string.ui_people_none
    }

@get:StringRes
internal val PeopleFilter.shortLabelRes: Int
    get() = when (this) {
        PeopleFilter.STARRED -> R.string.ui_people_starred_short
        PeopleFilter.CONTACTS -> R.string.ui_people_contacts_short
        PeopleFilter.ANYONE -> R.string.ui_people_anyone_short
        PeopleFilter.NONE -> R.string.ui_people_none_short
    }
