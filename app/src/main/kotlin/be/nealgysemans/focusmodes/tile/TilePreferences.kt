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
 * @property alwaysAsk when true a tap opens the picker instead of re-activating
 *   [lastUsedModeId], for users who never want an implicit choice made for them.
 */
data class TilePrefs(
    val lastUsedModeId: String? = null,
    val alwaysAsk: Boolean = false,
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
            alwaysAsk = prefs[KEY_ALWAYS_ASK] ?: false,
        )
    }

    suspend fun setLastUsedModeId(modeId: String) {
        context.tilePreferencesDataStore.edit { it[KEY_LAST_USED_MODE_ID] = modeId }
    }

    suspend fun setAlwaysAsk(alwaysAsk: Boolean) {
        context.tilePreferencesDataStore.edit { it[KEY_ALWAYS_ASK] = alwaysAsk }
    }

    private companion object {
        val KEY_LAST_USED_MODE_ID = stringPreferencesKey("last_used_mode_id")
        val KEY_ALWAYS_ASK = booleanPreferencesKey("always_ask")
    }
}
