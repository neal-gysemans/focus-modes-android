package be.nealgysemans.focusmodes.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import be.nealgysemans.focusmodes.engine.ActivationSource
import be.nealgysemans.focusmodes.engine.ActiveState
import be.nealgysemans.focusmodes.engine.ActiveStateStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.activeStateDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "active_state",
)

/**
 * Preferences DataStore holding the single "which mode is on" pointer.
 *
 * Kept out of Room deliberately: this value changes on every tile tap and every
 * schedule boundary, while mode definitions change rarely. Mixing them would make
 * every Room query observer fire on a toggle.
 */
class ActiveStatePreferences(private val context: Context) : ActiveStateStore {

    /** Live pointer for the UI and the tile's label. */
    val flow: Flow<ActiveState> = context.activeStateDataStore.data.map { it.toActiveState() }

    private suspend fun save(state: ActiveState) {
        context.activeStateDataStore.edit { prefs ->
            if (state.activeModeId == null) {
                prefs.remove(KEY_ACTIVE_MODE_ID)
                prefs.remove(KEY_SOURCE)
                prefs.remove(KEY_SINCE)
            } else {
                prefs[KEY_ACTIVE_MODE_ID] = state.activeModeId
                prefs[KEY_SOURCE] = (state.source ?: ActivationSource.USER).name
                prefs[KEY_SINCE] = state.since
            }
        }
    }

    // --- ActiveStateStore (synchronous, for the engine) ---------------------
    //
    // `ModeEngine` must decide without suspending so a tile tap or a boot
    // broadcast fits inside its lifetime budget, but DataStore's API is
    // suspending. Bridging with runBlocking is safe *because* the engine is
    // confined to one single-threaded background dispatcher (see di/AppGraph) and
    // never runs on the main thread.
    //
    // TODO(skeleton): replace with an in-memory snapshot the graph keeps warm
    //  (collect `flow` once at process start) so no call blocks at all.

    override fun read(): ActiveState = runBlocking { readSuspending() }

    override fun write(state: ActiveState) = runBlocking { save(state) }

    private suspend fun readSuspending(): ActiveState = flow.first()

    /**
     * Decode the stored pointer.
     *
     * The retired `pinned_by_user` key is deliberately not read. `ActiveState.pinnedByUser`
     * derives the pin from [KEY_SOURCE] now, and every value ever written under the old key
     * was already equal to `source == USER` — so there is nothing to migrate, only a key to
     * stop reading. A store written by an older build decodes to exactly the same state it
     * used to.
     */
    private fun Preferences.toActiveState(): ActiveState {
        val modeId = this[KEY_ACTIVE_MODE_ID] ?: return ActiveState.IDLE
        return ActiveState(
            activeModeId = modeId,
            source = this[KEY_SOURCE]?.let { name ->
                ActivationSource.entries.firstOrNull { it.name == name }
            },
            since = this[KEY_SINCE] ?: 0L,
        )
    }

    private companion object {
        val KEY_ACTIVE_MODE_ID = stringPreferencesKey("active_mode_id")
        val KEY_SOURCE = stringPreferencesKey("source")
        val KEY_SINCE = longPreferencesKey("since")
    }
}
