package be.nealgysemans.focusmodes.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * The manifest-registered `AppWidgetProvider` behind [FocusWidget].
 *
 * Empty by design: `GlanceAppWidgetReceiver` already turns every provider broadcast into
 * a `provideGlance` run, and [FocusWidget] reads its state from the app's own stores — so
 * there is no per-broadcast work left for this class to do, and nothing here to get out
 * of step with the composition.
 *
 * It is also the reboot path. Alarms and the system's belief about rule state are lost on
 * reboot (see `schedule/BootReceiver`), and so is the Glance session — but not the host's
 * copy of the last RemoteViews. The platform sends `APPWIDGET_UPDATE` to this receiver
 * once the host is back up, which re-runs `provideGlance` and re-derives from Room and
 * DataStore. Same path for an app update, and for a launcher that restarted on its own.
 */
class FocusWidgetReceiver : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget get() = FocusWidget
}
