package be.nealgysemans.tilespike

import android.content.Context
import android.os.SystemClock
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Context.spikeDataStore: DataStore<Preferences> by preferencesDataStore(name = "tile_spike")

/** Everything the tile needs to render, in one snapshot. */
data class SpikeState(
    val behavior: TileBehavior = TileBehavior.DEFAULT,
    val activeMode: FakeMode? = null,
    val zenRuleId: String? = null,
    val seq: Int = 0,
)

/**
 * Process-wide cache of [SpikeState].
 *
 * WHY: `TileService.onClick()` is the thing we are timing. Reading DataStore there
 * would put a disk read inside the measurement and inside the user's perceived
 * latency. The tile therefore only ever reads these volatile fields; DataStore is
 * the durable mirror, written off the hot path.
 */
object StateCache {
    @Volatile
    var value: SpikeState = SpikeState()
        private set

    @Volatile
    var warm: Boolean = false
        private set

    fun publish(state: SpikeState) {
        value = state
        warm = true
    }

    /** Optimistic local mutation, applied before anything touches disk. */
    fun setActiveModeOptimistically(mode: FakeMode?) {
        value = value.copy(activeMode = mode)
    }
}

class SpikeStore(private val context: Context) {

    private val ds get() = context.spikeDataStore

    val state: Flow<SpikeState> = ds.data.map { p ->
        SpikeState(
            behavior = TileBehavior.fromNameOrDefault(p[KEY_BEHAVIOR]),
            activeMode = FakeMode.fromNameOrNull(p[KEY_ACTIVE_MODE]),
            zenRuleId = p[KEY_RULE_ID]?.takeIf { it.isNotBlank() },
            seq = p[KEY_SEQ] ?: 0,
        )
    }

    val measurements: Flow<List<TapMeasurement>> = ds.data.map { p ->
        TapMeasurement.decodeAll(p[KEY_MEASUREMENTS].orEmpty())
    }

    val events: Flow<List<String>> = ds.data.map { p ->
        p[KEY_EVENTS].orEmpty().lineSequence().filter { it.isNotBlank() }.toList()
    }

    suspend fun setBehavior(behavior: TileBehavior) = ds.edit { it[KEY_BEHAVIOR] = behavior.name }

    suspend fun setActiveMode(mode: FakeMode?) = ds.edit {
        if (mode == null) it.remove(KEY_ACTIVE_MODE) else it[KEY_ACTIVE_MODE] = mode.name
    }

    suspend fun setRuleId(id: String?) = ds.edit {
        if (id == null) it.remove(KEY_RULE_ID) else it[KEY_RULE_ID] = id
    }

    /** Returns the sequence number assigned to this tap. */
    suspend fun nextSeq(): Int {
        var assigned = 0
        ds.edit {
            assigned = (it[KEY_SEQ] ?: 0) + 1
            it[KEY_SEQ] = assigned
        }
        return assigned
    }

    suspend fun addMeasurement(m: TapMeasurement) = ds.edit { p ->
        val existing = TapMeasurement.decodeAll(p[KEY_MEASUREMENTS].orEmpty())
        p[KEY_MEASUREMENTS] = TapMeasurement.encodeAll(existing + m)
    }

    suspend fun clearMeasurements() = ds.edit { p ->
        p.remove(KEY_MEASUREMENTS)
        p[KEY_SEQ] = 0
    }

    suspend fun log(line: String) = ds.edit { p ->
        val stamped = "${stamp()}  $line"
        val existing = p[KEY_EVENTS].orEmpty().lineSequence().filter { it.isNotBlank() }.toList()
        p[KEY_EVENTS] = (listOf(stamped) + existing).take(MAX_EVENTS).joinToString("\n")
    }

    suspend fun clearEvents() = ds.edit { it.remove(KEY_EVENTS) }

    private fun stamp(): String {
        val wall = TIME_FMT.format(Date())
        val up = SystemClock.elapsedRealtime() / 1000.0
        return "$wall (+%.1fs)".format(Locale.ROOT, up)
    }

    companion object {
        private const val MAX_EVENTS = 300
        private val TIME_FMT = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

        private val KEY_BEHAVIOR = stringPreferencesKey("tile_behavior")
        private val KEY_ACTIVE_MODE = stringPreferencesKey("active_mode")
        private val KEY_RULE_ID = stringPreferencesKey("zen_rule_id")
        private val KEY_MEASUREMENTS = stringPreferencesKey("measurements")
        private val KEY_EVENTS = stringPreferencesKey("events")
        private val KEY_SEQ = intPreferencesKey("seq")
    }
}
