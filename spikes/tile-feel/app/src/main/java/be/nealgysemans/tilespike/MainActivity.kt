package be.nealgysemans.tilespike

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Control panel + results view for the spike. Everything a tester needs is on one
 * scrolling screen, because the protocol in the README wants them switching tile
 * behaviour, tapping the tile, and reading numbers without hunting through menus.
 */
class MainActivity : ComponentActivity() {

    private lateinit var store: SpikeStore
    private lateinit var zen: ZenController
    private lateinit var watcher: ZenBroadcastWatcher

    /** Bumped to force the non-observable system reads (DND access, rule state) to re-read. */
    private val refreshTick = MutableStateFlow(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SpikeStore(applicationContext)
        zen = ZenController(applicationContext)
        watcher = ZenBroadcastWatcher(applicationContext) { line ->
            log(line)
            refreshTick.value = refreshTick.value + 1
        }
        watcher.register()

        setContent {
            SpikeTheme {
                Surface(Modifier.fillMaxSize()) {
                    val state by store.state.collectAsStateWithLifecycle(SpikeState())
                    val measurements by store.measurements.collectAsStateWithLifecycle(emptyList())
                    val events by store.events.collectAsStateWithLifecycle(emptyList())
                    val tick by refreshTick.collectAsStateWithLifecycle(0)
                    Screen(state, measurements, events, tick)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTick.value = refreshTick.value + 1
    }

    override fun onDestroy() {
        watcher.unregister()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ actions

    /**
     * `requestAddTileService` only works while the calling app is in the foreground, and
     * the platform auto-denies after repeated user refusals — so a NOT_ADDED result can
     * mean "user said no" OR "user already said no twice". Whether HyperOS implements
     * this at all is one of the things the spike is here to find out.
     */
    private fun requestAddTile() {
        val sbm = getSystemService(StatusBarManager::class.java)
        if (sbm == null) {
            log("requestAddTileService: StatusBarManager unavailable")
            return
        }
        log("requestAddTileService: calling…")
        runCatching {
            sbm.requestAddTileService(
                ComponentName(this, FocusTileService::class.java),
                getString(R.string.tile_label_focus),
                Icon.createWithResource(this, R.drawable.ic_focus_on),
                mainExecutor,
            ) { result ->
                log("requestAddTileService result=$result (${addTileResultName(result)})")
                refreshTick.value = refreshTick.value + 1
            }
        }.onFailure {
            log("requestAddTileService THREW ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private fun openDndAccessSettings() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        runCatching { startActivity(intent) }
            .onFailure { log("ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS failed: ${it.message}") }
    }

    /**
     * `Settings.ACTION_ZEN_MODE_SETTINGS` is @hide, so the action string is used
     * directly and we fall back to sound settings when the OEM does not handle it —
     * which is exactly the kind of thing that differs on HyperOS.
     */
    private fun openZenSettings() {
        // No resolveActivity() pre-check: package-visibility filtering on targetSdk 30+
        // makes that unreliable, so just try and fall back on ActivityNotFoundException.
        runCatching { startActivity(Intent(ACTION_ZEN_MODE_SETTINGS)) }
            .onFailure { first ->
                log("ZEN_MODE_SETTINGS unhandled (${first.javaClass.simpleName}) -> sound settings")
                runCatching { startActivity(Intent(Settings.ACTION_SOUND_SETTINGS)) }
                    .onFailure { log("sound settings also failed: ${it.message}") }
            }
    }

    private fun createRule() {
        val result = zen.createRule()
        result.onSuccess { id ->
            lifecycleScope.launch(Dispatchers.IO) {
                store.setRuleId(id)
                store.log("zen rule created/found id=$id")
            }
        }.onFailure {
            log("createRule FAILED ${it.javaClass.simpleName}: ${it.message}")
        }
        refreshTick.value = refreshTick.value + 1
    }

    private fun deleteRule(id: String?) {
        if (id == null) {
            log("deleteRule: no rule id known")
            return
        }
        val removed = zen.deleteRule(id)
        lifecycleScope.launch(Dispatchers.IO) {
            store.setRuleId(null)
            store.setActiveMode(null)
            store.log("zen rule delete id=$id removed=${removed.getOrNull()} err=${removed.exceptionOrNull()?.javaClass?.simpleName}")
        }
        refreshTick.value = refreshTick.value + 1
    }

    private fun setSecondTile(enabled: Boolean) {
        runCatching { SecondTileService.setEnabled(this, enabled) }
            .onSuccess {
                log("second tile component -> ${if (enabled) "ENABLED" else "DISABLED"}")
                TileRefresher.refreshSecondTile(this)
            }
            .onFailure { log("setComponentEnabledSetting failed: ${it.message}") }
        refreshTick.value = refreshTick.value + 1
    }

    private fun log(line: String) {
        lifecycleScope.launch(Dispatchers.IO) { runCatching { store.log(line) } }
    }

    // --------------------------------------------------------------------- UI

    @Composable
    private fun Screen(
        state: SpikeState,
        measurements: List<TapMeasurement>,
        events: List<String>,
        tick: Int,
    ) {
        // `tick` is read so that recomposition re-runs these non-reactive system reads.
        @Suppress("UNUSED_EXPRESSION") tick
        val dnd = zen.hasDndAccess
        val knownRuleId = state.zenRuleId ?: zen.findExistingRuleId()
        val ruleState = knownRuleId?.let { zen.ruleStateName(it) } ?: "—"
        val secondEnabled = remember(tick) { SecondTileService.isEnabled(this) }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text("Tile Feel Spike", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Can a Quick Settings tile feel like iOS Control Center?",
                style = MaterialTheme.typography.bodySmall,
            )

            SectionCard("1 · Tile behaviour") {
                Text(
                    "What a single tap on the Focus tile does. Change it here, then pull " +
                        "the shade down and tap.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                TileBehavior.entries.forEach { b ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = state.behavior == b,
                                onClick = {
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        store.setBehavior(b)
                                        store.log("behavior -> ${b.name}")
                                    }
                                    TileRefresher.refreshFocusTile(this@MainActivity)
                                },
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = state.behavior == b, onClick = null)
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(b.title, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                b.blurb,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            SectionCard("2 · Add the tile") {
                Text(
                    "requestAddTileService needs this app in the foreground; repeated " +
                        "denials are auto-denied by the platform. If nothing happens, use " +
                        "the manual path in the README (HyperOS: edit Control Center, " +
                        "scroll to the very end of the available-tiles list).",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = ::requestAddTile) { Text("requestAddTileService(\"Focus\")") }
            }

            SectionCard("3 · Do Not Disturb access + zen rule") {
                StatusLine("DND access granted", if (dnd) "yes" else "NO")
                StatusLine("rule id", knownRuleId?.take(16) ?: "—")
                StatusLine("rule state", ruleState)
                StatusLine("interruption filter", zen.currentInterruptionFilter)
                StatusLine("rules user-managed", zen.rulesUserManaged.toString())
                Spacer(Modifier.height(8.dp))
                Text(
                    "Without a real rule the tile still demos, but tap-to-confirmed is " +
                        "recorded as n/a (confirmSource = no-dnd-access / no-rule).",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = ::openDndAccessSettings) { Text("Grant DND access") }
                    Button(onClick = ::createRule, enabled = dnd) { Text("Create rule") }
                    OutlinedButton(onClick = { deleteRule(knownRuleId) }, enabled = knownRuleId != null) {
                        Text("Delete rule")
                    }
                    OutlinedButton(onClick = ::openZenSettings) { Text("DND settings") }
                }
            }

            SectionCard("4 · Latency") {
                val flipStats = LatencyStats.of(measurements.map { it.flipMicros })
                // Taps where the rule was already in the target state tell us nothing
                // about how long the system takes, so they are excluded.
                val confirmStats = LatencyStats.of(
                    measurements.filterNot { it.confirmSource.contains("pre-match") }
                        .map { it.confirmMicros },
                )
                val bcastStats = LatencyStats.of(measurements.map { it.broadcastMicros })

                StatsLine("tap -> tile flip", flipStats)
                StatsLine("tap -> rule confirmed", confirmStats)
                StatsLine("tap -> status broadcast", bcastStats)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Records appear up to ${ToggleEngine.BROADCAST_WAIT_MS} ms after the " +
                        "tap: the engine waits that long for the zen-status broadcast " +
                        "before writing the row.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    MonoCell("#", 0.10f)
                    MonoCell("target", 0.22f)
                    MonoCell("flip", 0.18f)
                    MonoCell("conf", 0.20f)
                    MonoCell("source", 0.30f)
                }
                HorizontalDivider()
                if (measurements.isEmpty()) {
                    Text("no taps recorded yet", style = MaterialTheme.typography.bodySmall)
                } else {
                    measurements.asReversed().take(40).forEach { m ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            MonoCell("${m.seq}", 0.10f)
                            MonoCell(m.target, 0.22f)
                            MonoCell(ToggleEngine.fmt(m.flipMicros), 0.18f)
                            MonoCell(ToggleEngine.fmt(m.confirmMicros), 0.20f)
                            MonoCell(m.confirmSource, 0.30f)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    lifecycleScope.launch(Dispatchers.IO) { store.clearMeasurements() }
                }) { Text("Clear measurements") }
            }

            SectionCard("5 · Second tile (per-mode tiles)") {
                StatusLine("SecondTileService component", if (secondEnabled) "ENABLED" else "disabled")
                Text(
                    "Declared android:enabled=\"false\". Flip it, then open the QS edit " +
                        "list and check whether \"Focus: Sleep\" appears without a reboot. " +
                        "Then place it, disable it, re-enable it, and see whether it comes " +
                        "back in the same position.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { setSecondTile(true) }, enabled = !secondEnabled) {
                        Text("Enable 2nd tile")
                    }
                    OutlinedButton(onClick = { setSecondTile(false) }, enabled = secondEnabled) {
                        Text("Disable 2nd tile")
                    }
                }
            }

            SectionCard("6 · Long-press target & keyguard") {
                Text(
                    "Long-press the Focus tile in QS: it should open the fast mode picker " +
                        "(QS_TILE_PREFERENCES), not a settings screen. Button below opens " +
                        "the same activity directly for comparison.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    startActivity(Intent(this@MainActivity, TilePrefsActivity::class.java))
                }) { Text("Open picker activity") }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Keyguard: showDialog() renders UNDERNEATH a secure keyguard, so the " +
                        "tile checks isSecure && isLocked and defers the dialog through " +
                        "unlockAndRun. To test: lock the device with a PIN set, pull the " +
                        "shade down from the lock screen, tap the tile — you should be " +
                        "asked to unlock first, and the picker should appear only after. " +
                        "The event log records which branch was taken.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            SectionCard("7 · Event log") {
                if (events.isEmpty()) {
                    Text("empty", style = MaterialTheme.typography.bodySmall)
                } else {
                    events.take(120).forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    lifecycleScope.launch(Dispatchers.IO) { store.clearEvents() }
                }) { Text("Clear log") }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    @Composable
    private fun SectionCard(title: String, content: @Composable () -> Unit) {
        Card(Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                content()
            }
        }
    }

    @Composable
    private fun StatsLine(label: String, stats: LatencyStats) {
        val value = if (stats.count == 0) {
            "—"
        } else {
            "med %.1f / max %.1f / min %.1f ms (n=%d)".format(
                stats.medianMs ?: -1.0, stats.maxMs ?: -1.0, stats.minMs ?: -1.0, stats.count,
            )
        }
        StatusLine(label, value)
    }

    @Composable
    private fun MonoCell(text: String, weight: Float) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(weight),
        )
    }

    companion object {
        private const val ACTION_ZEN_MODE_SETTINGS = "android.settings.ZEN_MODE_SETTINGS"

        fun addTileResultName(code: Int): String = when (code) {
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "TILE_ADDED"
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> "TILE_NOT_ADDED"
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "TILE_ALREADY_ADDED"
            StatusBarManager.TILE_ADD_REQUEST_ERROR_MISMATCHED_PACKAGE -> "ERROR_MISMATCHED_PACKAGE"
            StatusBarManager.TILE_ADD_REQUEST_ERROR_REQUEST_IN_PROGRESS -> "ERROR_REQUEST_IN_PROGRESS"
            StatusBarManager.TILE_ADD_REQUEST_ERROR_BAD_COMPONENT -> "ERROR_BAD_COMPONENT"
            StatusBarManager.TILE_ADD_REQUEST_ERROR_NOT_CURRENT_USER -> "ERROR_NOT_CURRENT_USER"
            StatusBarManager.TILE_ADD_REQUEST_ERROR_APP_NOT_IN_FOREGROUND -> "ERROR_APP_NOT_IN_FOREGROUND"
            StatusBarManager.TILE_ADD_REQUEST_ERROR_NO_STATUS_BAR_SERVICE -> "ERROR_NO_STATUS_BAR_SERVICE"
            else -> "UNDOCUMENTED_CODE"
        }
    }
}
