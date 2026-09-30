package be.nealgysemans.tilespike

import android.app.NotificationManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The rule's `configurationActivity`.
 *
 * The platform requires an AutomaticZenRule to point somewhere the user can land from
 * Settings > Sound > Do Not Disturb. Without it the rule shows up with no way in and
 * some OEM Settings apps refuse to render it at all. It is also where the system sends
 * the user when they tap the rule name, so it gets EXTRA_AUTOMATIC_RULE_ID.
 */
class ZenRuleConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ruleId = intent?.getStringExtra(NotificationManager.EXTRA_AUTOMATIC_RULE_ID)
        val store = SpikeStore(applicationContext)
        val zen = ZenController(applicationContext)

        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                store.log("zen rule configurationActivity opened (ruleId=${ruleId ?: "none"})")
            }
        }

        setContent {
            SpikeTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(24.dp)) {
                        Text("Zen rule config", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "This screen exists because AutomaticZenRule requires a " +
                                "configurationActivity. The spike has nothing to configure.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        StatusLine("rule id from intent", ruleId ?: "—")
                        StatusLine(
                            "state",
                            ruleId?.let { zen.ruleStateName(it) }
                                ?: zen.findExistingRuleId()?.let { zen.ruleStateName(it) }
                                ?: "—",
                        )
                        StatusLine("interruption filter", zen.currentInterruptionFilter)
                        StatusLine("rules user-managed", zen.rulesUserManaged.toString())
                        Button(onClick = { finish() }, modifier = Modifier.padding(top = 24.dp)) {
                            Text("Done")
                        }
                    }
                }
            }
        }
    }
}
