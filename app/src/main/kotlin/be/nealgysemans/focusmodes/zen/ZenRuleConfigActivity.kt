package be.nealgysemans.focusmodes.zen

import android.app.Activity
import android.app.NotificationManager
import android.os.Bundle
import android.util.Log

/**
 * The owner of every `AutomaticZenRule` this app creates.
 *
 * `addAutomaticZenRule` rejects a rule that names no owner, and the modern owner is
 * a configuration activity handling `ACTION_AUTOMATIC_ZEN_RULE` — which is why this
 * exists at all, and why the manifest entry (exported, with the action filter and
 * the `ruleType` meta-data) is load-bearing rather than boilerplate. Spike #1
 * confirmed the system routes per-rule configuration back to it on HyperOS.
 *
 * It holds no UI of its own: it hands the user to the app's own mode list, which is
 * where a mode is actually edited, and finishes immediately. Kept out of `ui/` and
 * off Compose so the process does not pay for a Compose start-up on a Settings tap.
 */
class ZenRuleConfigActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Two spellings exist: ACTION_AUTOMATIC_ZEN_RULE carries
        // EXTRA_AUTOMATIC_RULE_ID, while the Settings deep link and the status
        // broadcast carry EXTRA_AUTOMATIC_ZEN_RULE_ID. Read both rather than
        // guessing which surface sent us here.
        val ruleId = intent?.getStringExtra(NotificationManager.EXTRA_AUTOMATIC_RULE_ID)
            ?: intent?.getStringExtra(NotificationManager.EXTRA_AUTOMATIC_ZEN_RULE_ID)
        Log.i(TAG, "launched by the system to configure rule $ruleId")

        // Resolved through the package manager instead of naming the Activity class,
        // so this file carries no dependency on the ui/ package.
        // The rule id is logged, not forwarded. It used to travel on the launch intent under
        // an extra nothing read, against a future "land on this mode's editor" that does not
        // exist — and an extra with no reader is a promise the UI looks like it keeps.
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        if (launch == null) {
            Log.w(TAG, "no launcher intent for $packageName; nothing to show")
        } else {
            startActivity(launch)
        }
        finish()
    }

    private companion object {
        const val TAG = "ZenRuleConfig"
    }
}
