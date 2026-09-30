package be.nealgysemans.zenspike

import android.app.NotificationManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The rule's owner. `addAutomaticZenRule` refuses a rule with no owner, so this
 * activity exists purely to be named by `setConfigurationActivity` — it must be
 * exported and carry the ACTION_AUTOMATIC_ZEN_RULE intent filter (see the manifest).
 *
 * Reaching this screen from the system Modes/DND UI is itself a spike result: it
 * proves the OS routes rule configuration back to the owning app.
 */
class RuleConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val ruleId = intent?.getStringExtra(NotificationManager.EXTRA_AUTOMATIC_ZEN_RULE_ID)
        EventLog.broadcast(
            TAG,
            "configuration activity launched by the system; action=${intent?.action} ruleId=$ruleId",
        )

        setContent {
            SpikeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text("Zen Spike rule", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "The system launched this screen to configure the rule. " +
                                "Nothing to configure here — reaching this screen at all is the result " +
                                "worth recording (it means the OS honours setConfigurationActivity).",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "rule id: ${ruleId ?: "(not supplied in the intent)"}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(onClick = { finish() }) { Text("Done") }
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "config"
    }
}
