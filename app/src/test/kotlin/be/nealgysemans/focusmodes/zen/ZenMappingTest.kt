package be.nealgysemans.focusmodes.zen

import android.app.NotificationManager
import android.service.notification.Condition
import android.service.notification.ZenPolicy
import be.nealgysemans.focusmodes.engine.ActivationSource
import be.nealgysemans.focusmodes.engine.PeopleFilter
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two translations between the app's domain and the platform's ints.
 *
 * These are one-liners that are also the whole contract with the system: a schedule
 * firing reported as `SOURCE_USER_ACTION` would let the app override a user's snooze
 * (the one thing it must never do), and a status value read as the wrong direction
 * would make the app fight the user over whether the phone is quiet. Constants are
 * compile-time ints, so this runs on the JVM with no device and no Robolectric.
 */
class ZenMappingTest {

    @Test
    fun `each activation source reports the matching condition source`() {
        assertEquals(
            Condition.SOURCE_USER_ACTION,
            ActivationSource.USER.toConditionSource(),
        )
        assertEquals(
            Condition.SOURCE_SCHEDULE,
            ActivationSource.SCHEDULE.toConditionSource(),
        )
        assertEquals(
            Condition.SOURCE_CONTEXT,
            ActivationSource.CONTEXT.toConditionSource(),
        )
    }

    @Test
    fun `no activation source maps to an unknown source`() {
        ActivationSource.entries.forEach { source ->
            assert(source.toConditionSource() != Condition.SOURCE_UNKNOWN) {
                "$source maps to SOURCE_UNKNOWN; the system would misreport why the phone is quiet"
            }
        }
    }

    @Test
    fun `only a user action may override a snooze`() {
        // The platform only lets SOURCE_USER_ACTION punch through a rule the user
        // turned off, so exactly one source is allowed to carry it.
        val userActionSources = ActivationSource.entries
            .filter { it.toConditionSource() == Condition.SOURCE_USER_ACTION }

        assertEquals(listOf(ActivationSource.USER), userActionSources)
    }

    @Test
    fun `each people filter maps to its own zen policy audience`() {
        assertEquals(ZenPolicy.PEOPLE_TYPE_STARRED, PeopleFilter.STARRED.toZenPeopleType())
        assertEquals(ZenPolicy.PEOPLE_TYPE_CONTACTS, PeopleFilter.CONTACTS.toZenPeopleType())
        assertEquals(ZenPolicy.PEOPLE_TYPE_ANYONE, PeopleFilter.ANYONE.toZenPeopleType())
        assertEquals(ZenPolicy.PEOPLE_TYPE_NONE, PeopleFilter.NONE.toZenPeopleType())
    }

    @Test
    fun `no people filter falls back to unset`() {
        val distinct = PeopleFilter.entries.map { it.toZenPeopleType() }.toSet()

        assertEquals("every filter must map to a distinct audience", 4, distinct.size)
        assert(ZenPolicy.PEOPLE_TYPE_UNSET !in distinct) {
            "PEOPLE_TYPE_UNSET would let the device default decide who rings through"
        }
    }

    // --- status broadcast ----------------------------------------------------

    @Test
    fun `a snooze, a disable and a delete all mean the mode is off`() {
        listOf(
            NotificationManager.AUTOMATIC_RULE_STATUS_DEACTIVATED,
            NotificationManager.AUTOMATIC_RULE_STATUS_DISABLED,
            NotificationManager.AUTOMATIC_RULE_STATUS_REMOVED,
        ).forEach { status ->
            assertEquals(
                "status ${status.statusName} must clear the pin",
                ZenRuleStatus.OFF,
                zenRuleStatusOf(status),
            )
        }
    }

    @Test
    fun `an activation means the mode is on`() {
        assertEquals(
            ZenRuleStatus.ON,
            zenRuleStatusOf(NotificationManager.AUTOMATIC_RULE_STATUS_ACTIVATED),
        )
    }

    @Test
    fun `a directionless status falls back to reconciling`() {
        listOf(
            NotificationManager.AUTOMATIC_RULE_STATUS_ENABLED,
            NotificationManager.AUTOMATIC_RULE_STATUS_UNKNOWN,
            // Not a value this SDK defines; a future one must not be read as "off".
            99,
        ).forEach { status ->
            assertEquals(
                "status ${status.statusName} carries no direction",
                ZenRuleStatus.RECONCILE,
                zenRuleStatusOf(status),
            )
        }
    }
}
