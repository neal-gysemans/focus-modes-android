package be.nealgysemans.zenspike

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.delay

/**
 * The whole spike: one screen of live status, one row of buttons per API call under
 * test, and a timestamped log of everything that happened.
 */
class MainActivity : ComponentActivity() {

    private lateinit var controller: ZenController
    private lateinit var monitor: ZenBroadcastMonitor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        controller = ZenController(this)
        monitor = ZenBroadcastMonitor(controller)

        if (savedInstanceState == null) {
            EventLog.info("app", "Zen Spike started")
            controller.logEnvironment()
        }
        Notifier.ensureChannels(this)

        setContent {
            SpikeTheme {
                Scaffold { insets ->
                    SpikeScreen(
                        controller = controller,
                        monitor = monitor,
                        modifier = Modifier.padding(insets),
                    )
                }
            }
        }
    }
}

@Composable
private fun SpikeScreen(
    controller: ZenController,
    monitor: ZenBroadcastMonitor,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var status by remember { mutableStateOf(controller.readStatus()) }
    val entries by EventLog.entries.collectAsState()

    // Broadcasts only matter while the screen is up; tie the receiver to the lifecycle.
    LifecycleResumeEffect(monitor) {
        monitor.register(context)
        onPauseOrDispose { monitor.unregister(context) }
    }

    // Poll instead of relying on broadcasts: whether HyperOS even sends them is
    // one of the things we are here to find out.
    LaunchedEffect(Unit) {
        while (true) {
            status = controller.readStatus()
            delay(1_500)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        EventLog.result("perm", "POST_NOTIFICATIONS granted=$granted")
    }

    LaunchedEffect(Unit) {
        if (!Notifier.hasPostPermission(context)) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun launch(intent: Intent, label: String) {
        EventLog.action("intent", "startActivity($label)")
        runCatching { context.startActivity(intent) }
            .onFailure { EventLog.failure("intent", it) }
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        StatusCard(status)
        ActionButtons(
            status = status,
            onRequestDndAccess = { launch(controller.dndAccessSettingsIntent(), "DND access settings") },
            onOpenModesUi = { launch(controller.modesSettingsIntent(), "Modes settings") },
            onCreateOrUpdate = { controller.createOrUpdateRule(); status = controller.readStatus() },
            onActivateUser = { controller.activateAsUserAction(); status = controller.readStatus() },
            onActivateSchedule = { controller.activateAsSchedule(); status = controller.readStatus() },
            onDeactivate = { controller.deactivate(); status = controller.readStatus() },
            onReadBack = { controller.readBackRule(); status = controller.readStatus() },
            onDelete = { controller.deleteRule(); status = controller.readStatus() },
            onPostNormal = { Notifier.postDelayed(context, bypassDnd = false) },
            onPostBypass = { Notifier.postDelayed(context, bypassDnd = true) },
        )
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Event log (${entries.size}, newest first)",
                style = MaterialTheme.typography.titleSmall,
            )
            TextButton(onClick = { EventLog.clear() }) { Text("Clear") }
        }
        EventLogList(entries, Modifier.fillMaxSize())
    }
}

@Composable
private fun StatusCard(status: ZenStatus) {
    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("Zen Spike", style = MaterialTheme.typography.titleMedium)
            StatusLine("DND access granted", status.dndAccessGranted)
            StatusLine("areAutomaticZenRulesUserManaged()", status.rulesUserManaged)
            StatusLine("Modes UI (ACTION_AUTOMATIC_ZEN_RULE_SETTINGS) resolves", status.modesSettingsResolvable)
            StatusLine("DND-access screen resolves", status.dndAccessSettingsResolvable)
            StatusLine("rule present", status.ruleFound)
            KeyValue("rule id", status.ruleId ?: "(none yet)")
            KeyValue("rule", "${status.ruleName ?: "-"} (enabled=${status.ruleEnabled ?: "-"})")
            KeyValue("rule state", status.ruleStateText)
            KeyValue("stored device effects", status.storedEffects)
            KeyValue("interruption filter", status.currentInterruptionFilter)
            KeyValue("other rules on device", status.otherRuleCount.toString())
            KeyValue("consolidated policy", status.consolidatedPolicy)
            status.readError?.let {
                Text(
                    "read error: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, value: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (value) "YES" else "NO",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (value) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
            modifier = Modifier.padding(end = 6.dp),
        )
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun KeyValue(label: String, value: String) {
    Text(
        "$label: $value",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
    )
}

@Composable
private fun ActionButtons(
    status: ZenStatus,
    onRequestDndAccess: () -> Unit,
    onOpenModesUi: () -> Unit,
    onCreateOrUpdate: () -> Unit,
    onActivateUser: () -> Unit,
    onActivateSchedule: () -> Unit,
    onDeactivate: () -> Unit,
    onReadBack: () -> Unit,
    onDelete: () -> Unit,
    onPostNormal: () -> Unit,
    onPostBypass: () -> Unit,
) {
    val hasRule = status.ruleId != null
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Button(onClick = onRequestDndAccess) {
            Text(if (status.dndAccessGranted) "DND access (granted)" else "Grant DND access")
        }
        OutlinedButton(onClick = onOpenModesUi, enabled = status.modesSettingsResolvable) {
            Text("Open Modes UI")
        }
        Button(onClick = onCreateOrUpdate, enabled = status.dndAccessGranted) {
            Text(if (hasRule) "Update rule" else "Create rule")
        }
        Button(onClick = onActivateUser, enabled = hasRule) { Text("Activate: USER_ACTION") }
        Button(onClick = onActivateSchedule, enabled = hasRule) { Text("Activate: SCHEDULE") }
        Button(onClick = onDeactivate, enabled = hasRule) { Text("Deactivate (STATE_FALSE)") }
        OutlinedButton(onClick = onReadBack, enabled = hasRule) { Text("Read back") }
        OutlinedButton(onClick = onDelete, enabled = hasRule) { Text("Delete rule") }
        Button(onClick = onPostNormal) { Text("Notify in 5s (normal)") }
        Button(onClick = onPostBypass) { Text("Notify in 5s (bypass DND)") }
    }
}

@Composable
private fun EventLogList(entries: List<LogEntry>, modifier: Modifier = Modifier) {
    LazyColumn(modifier = modifier) {
        items(entries, key = { it.seq }) { entry ->
            Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Row {
                    Text(
                        entry.clock,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        " ${entry.kind.name} ",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = kindColor(entry.kind),
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .background(kindColor(entry.kind).copy(alpha = 0.12f), RoundedCornerShape(4.dp)),
                    )
                    Text(
                        entry.tag,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    entry.message,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun kindColor(kind: LogEntry.Kind): Color = when (kind) {
    LogEntry.Kind.ACTION -> MaterialTheme.colorScheme.primary
    LogEntry.Kind.RESULT -> MaterialTheme.colorScheme.tertiary
    LogEntry.Kind.ERROR -> MaterialTheme.colorScheme.error
    LogEntry.Kind.BROADCAST -> MaterialTheme.colorScheme.secondary
    LogEntry.Kind.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
}
