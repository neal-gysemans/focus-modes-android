package be.nealgysemans.focusmodes.ui

import android.content.Context
import android.content.Intent
import android.provider.ContactsContract

/**
 * Where "Open Contacts" sends the user, or null when there is nowhere to send them.
 *
 * The mode editor explains that starred contacts are the allowlist, which is only
 * actionable if the user can get to the place stars are set. That place is the
 * Contacts app, and the app has no business knowing which one it is — so the intent is
 * resolved, not hardcoded, and the button is simply not offered when nothing answers.
 * Same rule the health cards follow: an unresolvable intent throws, and a button that
 * throws is worse than a button that is not there.
 *
 * Two candidates, tried in order:
 *  1. `ACTION_MAIN` + `CATEGORY_APP_CONTACTS` — the documented "open the contacts app"
 *     intent, and what a launcher-registered Contacts app matches.
 *  2. `ACTION_VIEW` on `ContactsContract.Contacts.CONTENT_URI` — the fallback for OEM
 *     builds that ship a Contacts app without the launcher category. HyperOS moves
 *     enough of Settings around that assuming either one exists is not safe.
 *
 * Both forms are declared in the manifest's `<queries>`; package-visibility filtering
 * otherwise makes `resolveActivity` return null for an installed app.
 *
 * Nothing here reads a contact. The app holds no `READ_CONTACTS` permission and never
 * will — the allowlist is enforced system-side by `ZenPolicy` from stars the user sets
 * in Contacts itself, which is the entire reason this is a link and not a picker.
 */
internal fun contactsIntent(context: Context): Intent? {
    val candidates = listOf(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CONTACTS),
        Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI),
    )
    // No FLAG_ACTIVITY_NEW_TASK: this is started from a foreground Activity on a tap,
    // which is the one case that does not need it, and setting it changes where back
    // takes the user out of Contacts.
    return candidates.firstOrNull { it.resolveActivity(context.packageManager) != null }
}
