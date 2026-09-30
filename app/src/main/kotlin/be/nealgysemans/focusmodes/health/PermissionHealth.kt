package be.nealgysemans.focusmodes.health

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * One grant the app needs, and whether the user has given it.
 *
 * @property blocking true when the app cannot do its core job without it. Only DND
 *   access is blocking; the rest degrade specific features, and the UI must say
 *   which feature, not just "grant this".
 * @property settingsIntent where to send the user. Null when the grant is a normal
 *   runtime permission requested in-app.
 */
data class HealthCheck(
    val id: Grant,
    val granted: Boolean,
    val blocking: Boolean,
    val settingsIntent: Intent?,
)

/** The grants this app asks for. Deliberately short — see the no-INTERNET stance. */
enum class Grant {
    /** Notification policy access. Without it no zen rule can be created or driven. */
    DND_ACCESS,

    /** Exact alarms. Without it schedule boundaries drift by minutes to hours. */
    EXACT_ALARM,

    /** Post notifications. Without it the ongoing status notification is invisible. */
    POST_NOTIFICATIONS,
}

/**
 * Checks the three grants the app depends on, and hands back where to fix each one.
 *
 * This exists as its own layer because on OEM skins these grants get revoked
 * behind the user's back (aggressive battery managers, "clean up" tools), so the
 * app has to re-check on every foreground rather than asking once at onboarding and
 * assuming the answer holds.
 */
class PermissionHealth(private val context: Context) {

    /** Every check, in the order the UI should present them. */
    fun checkAll(): List<HealthCheck> = listOf(
        dndAccess(),
        exactAlarm(),
        postNotifications(),
    )

    /** True when nothing blocking is missing; the engine may run. */
    fun isOperational(): Boolean = checkAll().none { it.blocking && !it.granted }

    /**
     * Notification policy access — the gate on the whole `AutomaticZenRule` API.
     *
     * Not a runtime permission: it is a Settings toggle, so it can only be
     * requested by sending the user to [Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS].
     */
    fun dndAccess(): HealthCheck {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        return HealthCheck(
            id = Grant.DND_ACCESS,
            granted = notificationManager.isNotificationPolicyAccessGranted,
            blocking = true,
            settingsIntent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS),
        )
    }

    /**
     * Exact alarm permission.
     *
     * `SCHEDULE_EXACT_ALARM` is declared in the manifest, but from API 33 the user
     * can revoke it, and `setExactAndAllowWhileIdle` throws when they have. Checked
     * before every arm, not just at startup.
     */
    fun exactAlarm(): HealthCheck {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        return HealthCheck(
            id = Grant.EXACT_ALARM,
            granted = alarmManager.canScheduleExactAlarms(),
            blocking = false,
            settingsIntent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM),
        )
    }

    /** `POST_NOTIFICATIONS`, a normal runtime permission requested from the Activity. */
    fun postNotifications(): HealthCheck = HealthCheck(
        id = Grant.POST_NOTIFICATIONS,
        granted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED,
        blocking = false,
        settingsIntent = null,
    )

    /**
     * Whether a "Fix this" button would actually land somewhere.
     *
     * OEM skins do remove or rename Settings screens, and firing an unresolvable
     * intent throws. Note this only works because the manifest declares the
     * matching `<queries>` intents — package-visibility filtering otherwise makes
     * every Settings action look absent.
     */
    fun canOpen(intent: Intent?): Boolean =
        intent != null && intent.resolveActivity(context.packageManager) != null

    /**
     * Deep link into the system's Modes screen for one rule, used by the "see it in
     * Settings" affordance. Also gated on [canOpen].
     */
    fun automaticZenRuleSettings(ruleId: String): Intent =
        Intent(Settings.ACTION_AUTOMATIC_ZEN_RULE_SETTINGS)
            .putExtra(EXTRA_AUTOMATIC_ZEN_RULE_ID, ruleId)

    private companion object {
        /**
         * `NotificationManager.EXTRA_AUTOMATIC_ZEN_RULE_ID`, spelled out rather than
         * referenced so this file needs no `NotificationManager` import just for a
         * string constant.
         */
        const val EXTRA_AUTOMATIC_ZEN_RULE_ID = "android.app.extra.AUTOMATIC_ZEN_RULE_ID"
    }
}
