package be.nealgysemans.focusmodes.health

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.net.toUri

/**
 * What the app can offer the user about a grant it is missing.
 *
 * A closed set rather than a nullable `Intent`, because "null" was carrying two different
 * meanings that the screens then had to take apart again: *this is a runtime permission, ask
 * for it in-app*, and *there is a Settings screen but this build does not ship it*. Those
 * lead to two different buttons — or to none — and a screen deciding between them from a
 * null had to re-probe the system to tell them apart.
 */
sealed interface Remedy {

    /** A normal runtime permission: the Activity can request it directly. */
    data object AskInApp : Remedy

    /**
     * A Settings screen, **already probed** as resolvable.
     *
     * Holding a resolved intent is the point of this type. OEM skins remove and rename
     * Settings screens and firing an unresolvable intent throws, so the probe has to happen
     * — but it is a package-manager call, and it used to happen inside a composable, which
     * means once per recomposition per card. Now it happens once per [PermissionHealth.checkAll],
     * and a card that holds one of these can simply draw its button.
     */
    data class OpenSettings(val intent: Intent) : Remedy

    /** Nothing to offer: the grant is missing and this build has no screen that grants it. */
    data object None : Remedy
}

/**
 * One grant the app needs, and whether the user has given it.
 *
 * @property blocking true when the app cannot do its core job without it. Only DND
 *   access is blocking; the rest degrade specific features, and the UI must say
 *   which feature, not just "grant this".
 * @property remedy how the user can fix it, resolved here rather than in a composable.
 */
data class HealthCheck(
    val id: Grant,
    val granted: Boolean,
    val blocking: Boolean,
    val remedy: Remedy,
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
 * Checks the three grants the app depends on, and hands back how to fix each one.
 *
 * This exists as its own layer because on OEM skins these grants get revoked
 * behind the user's back (aggressive battery managers, "clean up" tools), so the
 * app has to re-check on every foreground rather than asking once at onboarding and
 * assuming the answer holds.
 */
class PermissionHealth(private val context: Context) {

    // `by lazy` rather than a lookup per call: [dndGranted] is on the snapshot derivation's
    // path, which runs on every mode edit and every activation from every surface.
    private val notificationManager: NotificationManager by lazy {
        context.getSystemService(NotificationManager::class.java)
    }

    private val alarmManager: AlarmManager by lazy {
        context.getSystemService(AlarmManager::class.java)
    }

    /** Every check, in the order the UI should present them. */
    fun checkAll(): List<HealthCheck> = listOf(
        dndAccess(),
        exactAlarm(),
        postNotifications(),
    )

    /**
     * Notification policy access — the gate on the whole `AutomaticZenRule` API.
     *
     * Not a runtime permission: it is a Settings toggle, so it can only be
     * requested by sending the user to [Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS].
     */
    fun dndAccess(): HealthCheck = HealthCheck(
        id = Grant.DND_ACCESS,
        granted = dndGranted(),
        blocking = true,
        remedy = settingsRemedy(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)),
    )

    /**
     * Just the DND answer, with no intent built and no screen probed.
     *
     * `tile/TileSnapshotSource` re-probes this on every emission — there is no broadcast for
     * it and the user can revoke it from the shade — so it must not drag [dndAccess]'s
     * intent allocation and package-manager call along with it. Every *other* caller wants
     * the full [HealthCheck], which is why this is the narrow one rather than the default.
     */
    fun dndGranted(): Boolean = notificationManager.isNotificationPolicyAccessGranted

    /**
     * Exact alarm permission.
     *
     * `SCHEDULE_EXACT_ALARM` is declared in the manifest, but from API 33 the user
     * can revoke it, and `setExactAndAllowWhileIdle` throws when they have. Checked
     * before every arm, not just at startup.
     *
     * A `package:` data URI opens this app's own row instead of the whole "Alarms &
     * reminders" list, but it is served by a *separate* Settings activity that some builds
     * do not ship — so it is offered first and the plain list is the fallback. Both forms
     * are declared in `<queries>`; without that, package-visibility filtering makes each
     * look absent and the "Fix this" button would never show.
     */
    fun exactAlarm(): HealthCheck = HealthCheck(
        id = Grant.EXACT_ALARM,
        granted = alarmManager.canScheduleExactAlarms(),
        blocking = false,
        remedy = settingsRemedy(
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData("package:${context.packageName}".toUri()),
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM),
        ),
    )

    /** `POST_NOTIFICATIONS`, a normal runtime permission requested from the Activity. */
    fun postNotifications(): HealthCheck = HealthCheck(
        id = Grant.POST_NOTIFICATIONS,
        granted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED,
        blocking = false,
        remedy = Remedy.AskInApp,
    )

    /**
     * The first of [candidates] that would actually land somewhere, or [Remedy.None].
     *
     * `resolveActivity` only works because the manifest declares the matching `<queries>`
     * intents — package-visibility filtering otherwise makes every Settings action look
     * absent, and the app would hide the only way the user can grant DND access.
     */
    private fun settingsRemedy(vararg candidates: Intent): Remedy =
        candidates.firstOrNull { it.resolveActivity(context.packageManager) != null }
            ?.let(Remedy::OpenSettings)
            ?: Remedy.None
}
