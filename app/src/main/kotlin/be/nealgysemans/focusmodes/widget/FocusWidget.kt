package be.nealgysemans.focusmodes.widget

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import be.nealgysemans.focusmodes.R
import be.nealgysemans.focusmodes.notification.SurfaceSync
import be.nealgysemans.focusmodes.tile.TileMode
import be.nealgysemans.focusmodes.tile.TileSnapshot
import be.nealgysemans.focusmodes.tile.TileSnapshotSource
import be.nealgysemans.focusmodes.tile.TileTap
import be.nealgysemans.focusmodes.tile.toggleLastUsed
import be.nealgysemans.focusmodes.ui.MainActivity
import be.nealgysemans.focusmodes.ui.ModeGlyphs
import kotlinx.coroutines.flow.first

/**
 * The home-screen widget, modelled on the iPhone Focus widget.
 *
 * Three jobs, one per size (see [sizeMode]): at 2x1 it is *the* Focus button — the
 * active mode's glyph in a filled circular chip, its name beside it, a hollow ring and
 * the word "Focus" when nothing is on. Wider, it becomes a button per mode, the active
 * one filled in its own colour. That progression is the iOS one, and it is the reason
 * the widget is resizable at all: a two-mode user wants one button, a five-mode user
 * wants five.
 *
 * ## State source
 *
 * [stateDefinition] is deliberately `null`: **this widget has no Glance state.** It
 * renders from [TileSnapshotSource], the same Room + DataStore derivation the tile, the
 * picker and the ongoing notification render from — so there is no parallel copy that
 * could drift, and no widget-specific store to keep in step. Concretely:
 *
 *  - Room (`modes`) is the truth for which modes exist, their order, glyph and colour;
 *  - `data/ActiveStatePreferences` is the truth for which one is on, and since when;
 *  - `tile/TilePreferences` is the truth for the last-used mode.
 *
 * [provideGlance] reads one snapshot before the first frame (so the launcher never sees
 * a placeholder) and then *collects* the flow, which keeps a widget with a live session
 * correct without anyone telling it anything. A widget whose session has been torn down
 * — process death, reboot, a launcher restart — keeps showing the host's copy of the
 * last RemoteViews and re-runs [provideGlance] on the next update, re-deriving from the
 * stores rather than from anything of its own.
 *
 * ## Sync
 *
 * `notification/SurfaceSync` calls [refreshAll] on every snapshot change, which covers
 * every path where the widget is not already collecting: a tile toggle, an in-app
 * toggle, the notification's "Turn off", a schedule boundary, a mode turned off from
 * system Settings. Reboot and app update are covered by the platform instead — the host
 * sends `APPWIDGET_UPDATE` to [FocusWidgetReceiver], which runs [provideGlance] again.
 *
 * ## Taps
 *
 * Every tap goes to `ModeEngine` through `AppGraph`, via [ToggleModeAction]. The widget
 * never touches an `AutomaticZenRule`, so the manual-pin and single-active invariants
 * stay in the one place that enforces them.
 */
object FocusWidget : GlanceAppWidget() {

    /**
     * No Glance state definition.
     *
     * The default would give every widget instance its own Preferences DataStore. This
     * widget has nothing to put in one — every value it draws is already owned by a
     * store the rest of the app reads — and an empty-but-present store is exactly the
     * parallel copy that later grows a drift bug.
     */
    override val stateDefinition: GlanceStateDefinition<*>? = null

    /**
     * One layout per size bracket.
     *
     * `Responsive` rather than `Exact` because the host asks for each declared size once
     * and then picks between the finished RemoteViews itself: resizing the widget or
     * rotating the phone switches layout with no round trip to our process, which is
     * what keeps a resize from showing a stale or blank frame.
     *
     * The declared sizes are *lower bounds* — the host picks the largest that fits — so
     * they sit deliberately under the launcher's real cell sizes, and every layout is
     * built to fill whatever it actually gets.
     */
    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(SMALL_SIZE, ROW_SIZE, GRID_SIZE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // This is a surface entry point like the tile service and the activities, and a
        // widget update can be the thing that starts the process (boot, launcher
        // restart). Idempotent; without it a widget-only process would never fan a
        // change back out to the tile cache or the ongoing notification.
        SurfaceSync.start(context)

        val snapshots = TileSnapshotSource.flow(context)
        // Read once *before* composing. `provideGlance` is suspending precisely so this
        // is allowed, and it matters: composing from an empty snapshot first would push
        // a "finish setting up" frame to the launcher and correct it a moment later.
        val initial = runCatching { snapshots.first() }
            .onFailure { Log.w(TAG, "initial snapshot read failed", it) }
            .getOrDefault(TileSnapshot())

        provideContent {
            val snapshot by snapshots.collectAsState(initial = initial)
            GlanceTheme {
                FocusWidgetBody(snapshot, LocalSize.current)
            }
        }
    }

    /**
     * Repaint every placed instance from the stores.
     *
     * Called by `SurfaceSync`. A no-op when no widget is placed, and cheap when one is:
     * Glance diffs the composition and only pushes RemoteViews that actually changed.
     */
    suspend fun refreshAll(context: Context) {
        runCatching { updateAll(context) }
            .onFailure { Log.w(TAG, "widget update failed", it) }
    }

    private const val TAG = "FocusWidget"
}

// --------------------------------------------------------------------------- body

/**
 * The widget's one rounded card, and what goes inside it.
 *
 * `appWidgetBackground` plus the platform's own `system_app_widget_background_radius`
 * are what make it sit next to the system's widgets rather than merely beside them: the
 * host uses that marker view to round and to animate the widget, and reusing the
 * platform dimension means the corners track whatever the OEM's launcher decided they
 * are. The fill is [GlanceTheme]'s `widgetBackground`, which resolves to the system's
 * day/night widget surface — so light and dark are the host's decision, not ours.
 */
@Composable
private fun FocusWidgetBody(snapshot: TileSnapshot, size: DpSize) {
    val card = GlanceModifier
        .fillMaxSize()
        .appWidgetBackground()
        .background(GlanceTheme.colors.widgetBackground)
        .cornerRadius(android.R.dimen.system_app_widget_background_radius)

    // Nothing to draw and nothing a tap could usefully do: no DND grant, or no modes
    // defined. Both are only fixable in the app, so the whole card opens it. Decided
    // here rather than inside the action so a blocked widget never dispatches at all.
    if (!snapshot.dndGranted || snapshot.modes.isEmpty()) {
        Box(
            modifier = card.clickable(actionStartActivity<MainActivity>()).padding(CARD_PADDING),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = LocalContext.current.getString(R.string.widget_unavailable),
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                ),
                maxLines = 3,
            )
        }
        return
    }

    Box(modifier = card.padding(CARD_PADDING), contentAlignment = Alignment.Center) {
        if (size.width < WIDE_FROM) {
            CurrentFocusButton(snapshot)
        } else {
            ModeButtons(snapshot, columns = columnsFor(size, snapshot.modes.size))
        }
    }
}

/**
 * How many buttons go on a line.
 *
 * One line while there is only one line's worth of height; otherwise a grid at most
 * [MAX_COLUMNS] wide, because a fifth glyph-plus-name on one line stops being readable
 * well before it stops fitting.
 */
private fun columnsFor(size: DpSize, modeCount: Int): Int =
    if (size.height < TALL_FROM) modeCount else minOf(modeCount, MAX_COLUMNS)

// ------------------------------------------------------- the single Focus button

/**
 * The 2x1 layout: one chip, one name, one tap.
 *
 * Which mode a tap turns on is [TileSnapshot.toggleLastUsed]'s decision, resolved here
 * at render time rather than inside the action — so the click is always a concrete
 * "toggle this mode", and the cases that are not a toggle fall out as "open the app"
 * instead of as a callback that has to guess.
 *
 * Deliberately *not* the tile's `tap()`: the tile's cycle/ask preference belongs to a
 * control in a shade the user is halfway through closing, not to a labelled button on
 * the home screen.
 */
@Composable
private fun CurrentFocusButton(snapshot: TileSnapshot) {
    val active = snapshot.activeMode
    val context = LocalContext.current
    Row(
        modifier = GlanceModifier.fillMaxSize().clickable(snapshot.focusButtonAction()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphChip(
            glyphRes = active?.glyphRes ?: ModeGlyphs.OFF_RES,
            accent = active?.let { Color(it.color) },
            description = active?.let { context.getString(R.string.widget_state_on, it.name) }
                ?: context.getString(R.string.widget_state_off),
            size = CHIP_SIZE,
        )
        Spacer(GlanceModifier.width(10.dp))
        Text(
            text = active?.name ?: context.getString(R.string.widget_off_label),
            style = TextStyle(
                // The mode's own colour — the same one the tile glyph and the
                // notification accent use, so "violet means Sleep" holds here too.
                color = active?.let { ColorProvider(Color(it.color)) }
                    ?: GlanceTheme.colors.onSurfaceVariant,
                fontSize = 15.sp,
                fontWeight = if (active != null) FontWeight.Medium else FontWeight.Normal,
            ),
            // One line, ellipsised: a long mode name must shorten, never push the chip
            // off the card and never grow the text into a second line that gets clipped.
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
        )
    }
}

/** Toggle the mode this button names, or open the app when there is nothing to toggle. */
private fun TileSnapshot.focusButtonAction(): Action = when (val tap = toggleLastUsed()) {
    is TileTap.Activate -> ToggleModeAction.of(tap.modeId)
    is TileTap.Deactivate -> ToggleModeAction.of(tap.modeId)
    // Ask: no last-used mode survives and there is more than one to choose from. The
    // widget cannot show a dialog, and the app is where the choice is already visible.
    TileTap.Ask, TileTap.Blocked -> actionStartActivity<MainActivity>()
}

// ------------------------------------------------------------- the per-mode grid

/**
 * The wide layouts: one button per mode, [columns] per line.
 *
 * Every cell toggles its own mode, so this is a set of switches rather than a one-way
 * selector — tapping the mode that is already on turns it off, which is what the app's
 * long-press grid does and what a home-screen button is expected to do.
 *
 * Laid out eagerly with `Row`s rather than with a lazy grid: there are a handful of
 * modes, and `LazyVerticalGrid` in a widget costs a `RemoteViewsService` per instance
 * for no benefit at this scale.
 */
@Composable
private fun ModeButtons(snapshot: TileSnapshot, columns: Int) {
    val perLine = columns.coerceAtLeast(1)
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        snapshot.modes.chunked(perLine).forEachIndexed { index, line ->
            if (index > 0) Spacer(GlanceModifier.height(CELL_GAP))
            Row(
                modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                line.forEachIndexed { cell, mode ->
                    if (cell > 0) Spacer(GlanceModifier.width(CELL_GAP))
                    ModeCell(
                        mode = mode,
                        active = mode.id == snapshot.activeModeId,
                        modifier = GlanceModifier.defaultWeight(),
                    )
                }
                // Keep the last line's cells the same width as every other line's, so a
                // five-mode grid does not render two giant buttons underneath three.
                repeat(perLine - line.size) {
                    Spacer(GlanceModifier.width(CELL_GAP))
                    Spacer(GlanceModifier.defaultWeight())
                }
            }
        }
    }
}

/**
 * One mode as a button: glyph chip above, name below.
 *
 * Active is "filled in its colour" — the whole cell takes the mode's colour and the
 * glyph and label go white. A stronger signal than a tint or an outline, which matters
 * on a home screen where the widget competes with the wallpaper, and it is the one
 * presentation that survives both themes without a second palette: a saturated accent
 * with white on it reads the same on a light and on a dark widget surface.
 */
@Composable
private fun ModeCell(mode: TileMode, active: Boolean, modifier: GlanceModifier) {
    val accent = Color(mode.color)
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(if (active) ColorProvider(accent) else GlanceTheme.colors.surfaceVariant)
            .cornerRadius(android.R.dimen.system_app_widget_inner_radius)
            .clickable(ToggleModeAction.of(mode.id))
            .padding(CELL_PADDING),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GlyphChip(
            glyphRes = mode.glyphRes,
            // Inside a filled cell a coloured disc would sit on the same colour, so the
            // chip drops its own fill and the cell becomes the chip.
            accent = accent,
            description = mode.name,
            size = CELL_CHIP_SIZE,
            onAccent = active,
        )
        Spacer(GlanceModifier.height(4.dp))
        Text(
            text = mode.name,
            style = TextStyle(
                color = if (active) ON_ACCENT else GlanceTheme.colors.onSurface,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
            ),
            maxLines = 1,
        )
    }
}

// ------------------------------------------------------------------------- chip

/**
 * A glyph in a circle — the widget's half of `ui/GlyphBadge`.
 *
 * Three presentations:
 *
 *  - **[accent] given, [onAccent] false** → a solid disc in the mode's colour with a
 *    white glyph. iOS's filled Focus circle, and the "this is a mode" signal.
 *  - **[onAccent] true** → no disc: the cell underneath is already that colour, so the
 *    bare glyph goes white on it.
 *  - **[accent] null** → no disc either, glyph muted. This is what draws the hollow ring
 *    for "off", because `ModeGlyphs.OFF_RES` *is* a ring — the absence of a fill is the
 *    presentation, which is the same hollow-versus-filled distinction the tile leans on.
 *
 * A circle is `cornerRadius` at half the size: Glance has no shape or border modifier,
 * and an arbitrary `cornerRadius` needs API 31 — comfortably under this app's minSdk 35.
 */
@Composable
private fun GlyphChip(
    glyphRes: Int,
    accent: Color?,
    description: String,
    size: Dp,
    onAccent: Boolean = false,
) {
    val disc = accent?.takeUnless { onAccent }
    Box(
        modifier = GlanceModifier
            .size(size)
            .then(
                if (disc != null) {
                    GlanceModifier.background(ColorProvider(disc)).cornerRadius(size / 2)
                } else {
                    GlanceModifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(glyphRes),
            contentDescription = description,
            modifier = GlanceModifier.size(size * GLYPH_RATIO),
            colorFilter = ColorFilter.tint(
                if (disc != null || onAccent) ON_ACCENT else GlanceTheme.colors.onSurfaceVariant,
            ),
        )
    }
}

// -------------------------------------------------------------------- constants
//
// Declared at file scope rather than inside `FocusWidget` on purpose: the sizes are read
// by the object's own `sizeMode` initialiser, and an object's properties initialise in
// declaration order — a `private val SMALL_SIZE` declared after `sizeMode` would be read
// as zero.

/** 2x1: the single "current Focus" button. */
private val SMALL_SIZE = DpSize(110.dp, 48.dp)

/** 4x1: one line of per-mode buttons. */
private val ROW_SIZE = DpSize(220.dp, 48.dp)

/** 4x2 and up: a grid of per-mode buttons. */
private val GRID_SIZE = DpSize(220.dp, 110.dp)

/** Past this width there is room for a button per mode instead of one button. */
private val WIDE_FROM = 200.dp

/** Past this height a second line of buttons fits. */
private val TALL_FROM = 100.dp

/** Four glyph-and-name buttons on a line is the readable limit. */
private const val MAX_COLUMNS = 4

/**
 * White, for a glyph or label sitting on a mode's own colour.
 *
 * Not a theme colour: the accent underneath is the same saturated indigo/violet/teal in
 * light and dark, so a colour that flipped with the theme would be the low-contrast one
 * half the time. Every entry in `ui/ModePalette` is dark enough for white to clear the
 * contrast bar.
 */
private val ON_ACCENT = ColorProvider(Color.White)

/** Inset from the card's edge, matching the system's widget content padding. */
private val CARD_PADDING = 12.dp

private val CHIP_SIZE = 38.dp
private val CELL_CHIP_SIZE = 26.dp
private val CELL_PADDING = 4.dp
private val CELL_GAP = 6.dp

/** Glyph-to-chip ratio, shared with `ui/GlyphBadge` so the chips look alike. */
private const val GLYPH_RATIO = 0.55f
