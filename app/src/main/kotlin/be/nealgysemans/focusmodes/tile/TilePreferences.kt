package be.nealgysemans.focusmodes.tile

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.tilePreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "tile_prefs",
)

/**
 * How the tile behaves, and which mode a plain tap turns on.
 *
 * @property lastUsedModeId the mode the tile re-activates from off. Written whenever
 *   any surface activates a mode, so "last used" means last used anywhere — tile,
 *   app, picker or schedule — not last used *from the tile*.
 * @property tapBehavior what a plain tap on the tile does.
 */
data class TilePrefs(
    val lastUsedModeId: String? = null,
    val tapBehavior: TapBehavior = TapBehavior.LAST_USED,
)

/**
 * Preferences DataStore for the tile surface.
 *
 * Separate from `data/ActiveStatePreferences` on purpose: that store is the engine's
 * belief about *what is on*, which the engine owns and this module must never write.
 * This one is the tile's own presentation state, which the engine has no opinion
 * about. Keeping them apart means a tile preference change cannot wake the engine,
 * and the engine's writes cannot be confused for a user preference.
 *
 * Instances are cheap and interchangeable — the `by preferencesDataStore` delegate
 * holds one DataStore per process regardless of how many wrappers are constructed —
 * so callers create one where they need it rather than routing it through `AppGraph`
 * (which this module does not own).
 */
class TilePreferences(private val context: Context) {

    val flow: Flow<TilePrefs> = context.tilePreferencesDataStore.data.map { prefs ->
        TilePrefs(
            lastUsedModeId = prefs[KEY_LAST_USED_MODE_ID],
            tapBehavior = prefs.readTapBehavior(),
        )
    }

    suspend fun setLastUsedModeId(modeId: String) {
        context.tilePreferencesDataStore.edit { it[KEY_LAST_USED_MODE_ID] = modeId }
    }

    /**
     * Store [behaviour] and retire the boolean it replaced.
     *
     * Removing the old key here rather than in a one-shot migration is what keeps
     * [readTapBehavior] honest: once the user has expressed a three-way choice there must
     * be no two-way value left behind that a future reader could prefer by mistake.
     */
    suspend fun setTapBehavior(behaviour: TapBehavior) {
        context.tilePreferencesDataStore.edit { prefs ->
            prefs[KEY_TAP_BEHAVIOR] = behaviour.name
            prefs.remove(KEY_ALWAYS_ASK)
        }
    }

    private fun Preferences.readTapBehavior(): TapBehavior =
        tapBehaviorFrom(this[KEY_TAP_BEHAVIOR], this[KEY_ALWAYS_ASK])

    private companion object {
        val KEY_LAST_USED_MODE_ID = stringPreferencesKey("last_used_mode_id")
        val KEY_TAP_BEHAVIOR = stringPreferencesKey("tap_behavior")

        /** Pre-`tap_behavior` setting. Read for migration, never written again. */
        val KEY_ALWAYS_ASK = booleanPreferencesKey("always_ask")
    }
}

/**
 * Resolve the stored tap behaviour, migrating the `alwaysAsk` boolean it used to be.
 *
 * A read-time migration, not a write-time one: DataStore's `data` flow is collected by a
 * `TileService`, a widget composition and the UI, and a migration that wrote on first
 * read would have all of them racing to write the same value — and would emit a second
 * time, which is a repaint for every surface. The derivation is cheap, total, and stops
 * mattering the moment the user makes a real choice (which clears the old key).
 *
 * Pure and top-level so the rule is testable without a DataStore: this is the only place
 * an upgrading user's setting can be silently changed, which makes it worth pinning down.
 *
 * @param storedName the `tap_behavior` name, or null on a store written before it existed.
 * @param legacyAlwaysAsk the retired `always_ask` boolean, or null if it was never set.
 */
internal fun tapBehaviorFrom(storedName: String?, legacyAlwaysAsk: Boolean?): TapBehavior = when {
    // A stored name always wins, even an unrecognised one — `ofName` maps that to the
    // default rather than letting a stale boolean underneath it resurface.
    storedName != null -> TapBehavior.ofName(storedName)
    legacyAlwaysAsk == true -> TapBehavior.ALWAYS_ASK
    else -> TapBehavior.LAST_USED
}
