package be.nealgysemans.focusmodes.ui

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import be.nealgysemans.focusmodes.health.HealthCheck
import be.nealgysemans.focusmodes.health.PermissionHealth

/**
 * The permission grants, as Compose state that actually changes when a grant does.
 *
 * `PermissionHealth.checkAll()` is a live probe of the system — it is correct every
 * time it is called. The bug it caused was one level up: calling it straight from a
 * composable makes its result a plain value, and Compose has no reason to recompose
 * when a value it is not observing changes. Granting DND access means leaving for
 * Settings and coming back, and on return the screen was still rendering the answer
 * from before the user left. The card only disappeared on a cold start.
 *
 * So the probe is re-run on the two events that can mean a grant changed:
 *
 *  - **`ON_RESUME`** — covers every grant, because all three are changed somewhere
 *    other than this screen (a Settings screen, or the system permission dialog), and
 *    coming back is the one thing they all have in common. This is the load-bearing one.
 *  - **`ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED`** — covers the case where
 *    DND access changes while this screen is already in the foreground, which happens
 *    when the grant is toggled from a notification-shade shortcut or by an OEM
 *    "cleanup" tool. Registered `RECEIVER_EXPORTED` because it is a system broadcast;
 *    `NOT_EXPORTED` would silently never fire.
 *
 * Deliberately no polling: a grant cannot change without one of those two happening.
 */
@Composable
fun rememberHealthChecks(health: PermissionHealth): List<HealthCheck> {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var checks by remember(health) { mutableStateOf(health.checkAll()) }

    DisposableEffect(health, lifecycleOwner, context) {
        val refresh = { checks = health.checkAll() }

        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)

        val policyReceiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) = refresh()
        }
        val registered = runCatching {
            context.registerReceiver(
                policyReceiver,
                IntentFilter(NotificationManager.ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED),
                Context.RECEIVER_EXPORTED,
            )
        }.isSuccess

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            if (registered) runCatching { context.unregisterReceiver(policyReceiver) }
        }
    }

    return checks
}
