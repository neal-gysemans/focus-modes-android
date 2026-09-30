package be.nealgysemans.focusmodes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import be.nealgysemans.focusmodes.R
import be.nealgysemans.focusmodes.data.EffectsColumns
import be.nealgysemans.focusmodes.data.ModeEntity
import be.nealgysemans.focusmodes.data.TriggerEntity
import be.nealgysemans.focusmodes.data.newScheduleTriggerId
import be.nealgysemans.focusmodes.data.scheduleTrigger
import be.nealgysemans.focusmodes.engine.PeopleFilter
import be.nealgysemans.focusmodes.engine.ScheduleWindow
import be.nealgysemans.focusmodes.health.Grant
import be.nealgysemans.focusmodes.health.HealthCheck
import be.nealgysemans.focusmodes.health.Remedy
import be.nealgysemans.focusmodes.schedule.wrapsMidnight
import java.time.ZonedDateTime

/**
 * Everything one mode is: what it looks like, who gets through, what it does to the
 * screen, and when it turns itself on.
 *
 * A full screen, not the dialog this replaced. The dialog was scoped to name, glyph and
 * colour and that was the right size for those three; people filters, three device
 * effects and a list of schedules do not fit in an `AlertDialog` on a phone, and the
 * half of the app that makes a mode *do* something cannot live behind a scrollbar in a
 * 300dp box.
 *
 * **Two different save semantics on one screen, deliberately.** The mode's own fields
 * are edited locally and committed when the user leaves, the way a Settings screen
 * behaves — there is no Cancel, because back is how Android users leave a screen and
 * silently discarding their edit for using it is the worse failure. Schedules are
 * separate database rows with their own dialog and their own Save, so they persist the
 * moment that dialog is confirmed. The header says so in one line, because the
 * alternative — a Cancel button that discards the name but not the schedule the user
 * added while they were here — is the trap this arrangement avoids.
 *
 * A blank name is not rejected with an error dialog; the previous name is kept and the
 * field says so. A mode with no name is unusable on an icon-only tile, but stopping the
 * user from leaving a screen is a worse outcome than quietly keeping what worked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeEditorScreen(
    mode: ModeEntity,
    /** Every schedule row in the database, this mode's and everyone else's. */
    schedules: List<TriggerEntity>,
    checks: List<HealthCheck>,
    actions: ModeListActions,
    onClose: () -> Unit,
) {
    var name by remember(mode.id) { mutableStateOf(mode.name) }
    var iconKey by remember(mode.id) { mutableStateOf(mode.iconKey) }
    // An ARGB Int, so the specialised state avoids boxing it on every recomposition.
    var color by remember(mode.id) { mutableIntStateOf(mode.color) }
    var callsFrom by remember(mode.id) { mutableStateOf(mode.callsFrom) }
    var messagesFrom by remember(mode.id) { mutableStateOf(mode.messagesFrom) }
    var repeatCallers by remember(mode.id) { mutableStateOf(mode.repeatCallers) }
    var effects by remember(mode.id) { mutableStateOf(mode.effects) }

    val accent = Color(color)
    val trimmedName = name.trim()

    /**
     * Commit and leave. Idempotent, and called from exactly two places — the nav-up
     * button and the system back gesture — rather than from a `DisposableEffect`, so
     * "when does this save?" has one answer that does not depend on Compose's disposal
     * order.
     */
    val commitAndClose = {
        actions.onSaveMode(
            mode.copy(
                name = trimmedName.ifEmpty { mode.name },
                iconKey = iconKey,
                color = color,
                callsFrom = callsFrom,
                messagesFrom = messagesFrom,
                repeatCallers = repeatCallers,
                effects = effects,
            ),
        )
        onClose()
    }

    BackHandler(onBack = commitAndClose)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.mode_editor_title)) },
                navigationIcon = {
                    IconButton(onClick = commitAndClose) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.ui_editor_done),
                        )
                    }
                },
            )
        },
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.ui_editor_hint_applies_on_leave),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                AppearanceCard(
                    name = name,
                    onNameChange = { name = it },
                    nameIsBlank = trimmedName.isEmpty(),
                    iconKey = iconKey,
                    onIconKeyChange = { iconKey = it },
                    color = color,
                    onColorChange = { color = it },
                    accent = accent,
                )
            }

            item {
                PeopleCard(
                    callsFrom = callsFrom,
                    onCallsFromChange = { callsFrom = it },
                    messagesFrom = messagesFrom,
                    onMessagesFromChange = { messagesFrom = it },
                    repeatCallers = repeatCallers,
                    onRepeatCallersChange = { repeatCallers = it },
                    actions = actions,
                )
            }

            item {
                EffectsCard(effects = effects, onChange = { effects = it })
            }

            item {
                SchedulesCard(
                    modeId = mode.id,
                    schedules = schedules,
                    exactAlarm = checks.firstOrNull { it.id == Grant.EXACT_ALARM },
                    actions = actions,
                )
            }
        }
    }
}

// --------------------------------------------------------------------- appearance

/** Name, glyph and colour: the three things that change how a mode looks everywhere. */
@Composable
private fun AppearanceCard(
    name: String,
    onNameChange: (String) -> Unit,
    nameIsBlank: Boolean,
    iconKey: String,
    onIconKeyChange: (String) -> Unit,
    color: Int,
    onColorChange: (Int) -> Unit,
    accent: Color,
) {
    EditorCard {
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text(stringResource(R.string.mode_editor_name)) },
            singleLine = true,
            isError = nameIsBlank,
            supportingText = if (nameIsBlank) {
                { Text(stringResource(R.string.ui_editor_name_blank)) }
            } else {
                null
            },
            modifier = Modifier.fillMaxWidth(),
        )

        LabelledChoices(label = stringResource(R.string.mode_editor_glyph)) {
            // Chunked into fixed rows rather than scrolled or lazily laid out: a glyph
            // the user has to discover by swiping may as well not be there.
            ModeGlyphs.ALL.chunked(CHOICES_PER_ROW).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { glyph ->
                        GlyphChoice(
                            glyph = glyph,
                            accent = accent,
                            selected = glyph.key == iconKey,
                            onClick = { onIconKeyChange(glyph.key) },
                        )
                    }
                }
            }
        }

        LabelledChoices(label = stringResource(R.string.mode_editor_color)) {
            ModePalette.COLORS.chunked(CHOICES_PER_ROW).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { candidate ->
                        ColorChoice(
                            color = Color(candidate),
                            selected = candidate == color,
                            onClick = { onColorChange(candidate) },
                        )
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------- people

/**
 * Who gets through, per channel, plus the repeat-caller escape hatch.
 *
 * The four options are `ZenPolicy.PEOPLE_TYPE_*` under the skin, but the screen never
 * says STARRED: the enum name is a storage detail, and "Starred contacts" is what the
 * user set in the app that owns those stars.
 *
 * The explainer and its Contacts link are the load-bearing part. "Starred contacts"
 * means nothing unless the user knows where stars come from, and this app deliberately
 * cannot tell them — it holds no `READ_CONTACTS` permission and the filtering happens
 * system-side, so pointing at Contacts is both the honest answer and the only one.
 */
@Composable
private fun PeopleCard(
    callsFrom: PeopleFilter,
    onCallsFromChange: (PeopleFilter) -> Unit,
    messagesFrom: PeopleFilter,
    onMessagesFromChange: (PeopleFilter) -> Unit,
    repeatCallers: Boolean,
    onRepeatCallersChange: (Boolean) -> Unit,
    actions: ModeListActions,
) {
    val context = LocalContext.current
    // Resolved once: package visibility does not change while the screen is open, and
    // resolveActivity on every recomposition is a binder call per frame.
    val contacts = remember(context) { contactsIntent(context) }

    EditorCard {
        Text(
            text = stringResource(R.string.ui_editor_people_title),
            style = MaterialTheme.typography.titleMedium,
        )

        PeopleSelector(
            label = stringResource(R.string.ui_editor_calls_from, stringResource(callsFrom.plainLabelRes)),
            selected = callsFrom,
            onSelect = onCallsFromChange,
        )
        PeopleSelector(
            label = stringResource(
                R.string.ui_editor_messages_from,
                stringResource(messagesFrom.plainLabelRes),
            ),
            selected = messagesFrom,
            onSelect = onMessagesFromChange,
        )

        SwitchRow(
            title = stringResource(R.string.ui_editor_repeat_callers),
            summary = stringResource(R.string.ui_editor_repeat_callers_summary),
            checked = repeatCallers,
            onCheckedChange = onRepeatCallersChange,
        )

        HorizontalDivider()

        Text(
            text = stringResource(R.string.ui_editor_starred_explainer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Offered only when it would land somewhere — the same rule the health cards
        // follow, because firing an unresolvable intent throws.
        if (contacts != null) {
            OutlinedButton(onClick = { actions.onOpenSettings(contacts) }) {
                Text(stringResource(R.string.ui_editor_open_contacts))
            }
        }
    }
}

/**
 * One channel: a sentence saying what is set, and four chips to change it.
 *
 * Chips rather than a dropdown so all four options and the current one are visible at
 * once — this is a setting people second-guess ("wait, does my partner get through?"),
 * and a menu that has to be opened to answer that question makes them open it.
 *
 * Two chips per row rather than a flow layout: "Starred" and "Contacts" are long enough
 * that four across is a coin-flip on a narrow screen, and a chip that wraps to one
 * character wide is unreadable.
 */
@Composable
private fun PeopleSelector(
    label: String,
    selected: PeopleFilter,
    onSelect: (PeopleFilter) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        PeopleFilter.entries.chunked(FILTERS_PER_ROW).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { filter ->
                    FilterChip(
                        selected = filter == selected,
                        onClick = { onSelect(filter) },
                        label = { Text(stringResource(filter.shortLabelRes)) },
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------------ effects

/**
 * The three `ZenDeviceEffects` toggles.
 *
 * The summary is not hedging for its own sake. These are *requests*: a third-party rule
 * may ask for greyscale and the platform decides, and spike #1 exists precisely because
 * HyperOS's answer could not be read off the documentation. A switch that silently does
 * nothing on this phone is worse than a switch that warns it might.
 */
@Composable
private fun EffectsCard(effects: EffectsColumns, onChange: (EffectsColumns) -> Unit) {
    EditorCard {
        Text(
            text = stringResource(R.string.ui_editor_effects_title),
            style = MaterialTheme.typography.titleMedium,
        )
        SwitchRow(
            title = stringResource(R.string.ui_editor_effect_grayscale),
            summary = null,
            checked = effects.grayscale,
            onCheckedChange = { onChange(effects.copy(grayscale = it)) },
        )
        SwitchRow(
            title = stringResource(R.string.ui_editor_effect_dim_wallpaper),
            summary = null,
            checked = effects.dimWallpaper,
            onCheckedChange = { onChange(effects.copy(dimWallpaper = it)) },
        )
        SwitchRow(
            title = stringResource(R.string.ui_editor_effect_night_mode),
            summary = null,
            checked = effects.nightMode,
            onCheckedChange = { onChange(effects.copy(nightMode = it)) },
        )
        Text(
            text = stringResource(R.string.ui_editor_effects_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------- schedules

/**
 * This mode's schedules: what they are, when each next fires, and whether any of them
 * fight each other.
 *
 * Reads every schedule row, not just this mode's, because two of the three things shown
 * here are cross-mode facts. Overlap is computed against the whole enabled set — a Work
 * window colliding with Sleep's is exactly the collision worth knowing about — while
 * the list itself shows only this mode's rows, including the disabled ones. Those
 * disabled rows are the seeded Work and Sleep schedules: they ship inert so a new
 * install does not silence someone's first night uninvited, and this list is where they
 * become discoverable enough to switch on.
 *
 * Every change here persists immediately and reconciles; arming the next alarm is not
 * this screen's job, because `AppGraph.afterTransition` re-arms after every reconcile
 * unconditionally.
 */
@Composable
private fun SchedulesCard(
    modeId: String,
    schedules: List<TriggerEntity>,
    exactAlarm: HealthCheck?,
    actions: ModeListActions,
) {
    val clock = rememberClockStyle()
    val now = rememberNow()

    val rows = remember(schedules, modeId) {
        schedules.filter { it.modeId == modeId }.toScheduleRows().sortedForDisplay()
    }
    // Overlap is a property of what is *armed*, so disabled rows are excluded: a
    // switched-off schedule cannot take a boundary from anyone.
    val overlapping = remember(schedules) {
        overlappingTriggerIds(
            schedules.filter(TriggerEntity::enabled).toScheduleRows().mapNotNull(ScheduleRow::window),
        )
    }

    var editing by remember { mutableStateOf<ScheduleEdit?>(null) }

    EditorCard {
        Text(
            text = stringResource(R.string.ui_schedules_title),
            style = MaterialTheme.typography.titleMedium,
        )

        // The health card at the top of the list already covers the grant. This is the
        // same fix, offered where the consequence actually lands: someone creating their
        // first schedule has no reason to connect a card they scrolled past to the
        // schedule they just set for 07:00.
        if (exactAlarm != null && !exactAlarm.granted && rows.isNotEmpty()) {
            ExactAlarmHint(remedy = exactAlarm.remedy, actions = actions)
        }

        if (rows.isEmpty()) {
            Text(
                text = stringResource(R.string.ui_schedules_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            rows.forEach { row ->
                ScheduleRowItem(
                    row = row,
                    overlaps = row.trigger.id in overlapping,
                    clock = clock,
                    now = now,
                    onClick = { editing = ScheduleEdit(row) },
                    onEnabledChange = { enabled ->
                        actions.onSaveSchedule(row.trigger.copy(enabled = enabled))
                    },
                )
            }
        }

        OutlinedButton(
            onClick = { editing = ScheduleEdit(row = null) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.ui_schedules_add))
        }
    }

    editing?.let { edit ->
        ScheduleEditorDialog(
            initial = edit.draft,
            isNew = edit.row == null,
            onDismiss = { editing = null },
            onSave = { draft ->
                actions.onSaveSchedule(
                    scheduleTrigger(
                        id = edit.row?.trigger?.id ?: newScheduleTriggerId(),
                        modeId = modeId,
                        startMinuteOfDay = draft.startMinuteOfDay,
                        endMinuteOfDay = draft.endMinuteOfDay,
                        daysOfWeek = draft.daysOfWeek,
                        enabled = draft.enabled,
                    ),
                )
                editing = null
            },
            onDelete = edit.row?.let { row ->
                {
                    actions.onDeleteSchedule(row.trigger)
                    editing = null
                }
            },
        )
    }
}

/**
 * Which schedule the dialog is open for, and the draft it opened with.
 *
 * A null [row] means "new". The draft is derived once here rather than inside the
 * dialog so an unreadable row still opens on sane defaults instead of crashing, and so
 * the dialog itself never has to know about `params_json`.
 */
private class ScheduleEdit(val row: ScheduleRow?) {
    val draft: ScheduleDraft = row?.window?.let { window ->
        ScheduleDraft(
            daysOfWeek = window.daysOfWeek,
            startMinuteOfDay = window.startMinuteOfDay,
            endMinuteOfDay = window.endMinuteOfDay,
            enabled = row.trigger.enabled,
        )
    } ?: newScheduleDraft()
}

/**
 * One schedule: when it runs, when it next fires, and any honesty owed about it.
 *
 * "Next:" comes from the same `nextBoundaryAfter` the alarm is armed from, so it is the
 * armed instant and not a second guess at it. It is only shown for an enabled schedule,
 * because a disabled one has no next.
 */
@Composable
private fun ScheduleRowItem(
    row: ScheduleRow,
    overlaps: Boolean,
    clock: ClockStyle,
    now: ZonedDateTime,
    onClick: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            val window = row.window
            if (window == null) {
                Text(
                    text = stringResource(R.string.ui_schedule_unreadable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Text(
                    text = stringResource(
                        R.string.ui_schedule_summary,
                        daySetLabel(window.daysOfWeek, clock),
                        rangeLabel(window, clock),
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = scheduleStatusLine(window, row.trigger.enabled, clock, now),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (window.daysOfWeek.isEmpty()) {
                    Text(
                        text = stringResource(R.string.ui_schedule_no_days),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (overlaps) {
                    Text(
                        text = stringResource(R.string.ui_schedule_overlaps),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = row.trigger.enabled,
            onCheckedChange = onEnabledChange,
            // A row with no decodable window cannot be armed into anything sane.
            enabled = row.window != null,
        )
    }
}

/** "Next: Mon 09:00" when armed, "Off" when not, and nothing to say when inert. */
@Composable
private fun scheduleStatusLine(
    window: ScheduleWindow,
    enabled: Boolean,
    clock: ClockStyle,
    now: ZonedDateTime,
): String {
    if (!enabled) return stringResource(R.string.ui_schedule_disabled)
    val next = window.nextBoundary(now) ?: return stringResource(R.string.ui_schedule_disabled)
    return stringResource(R.string.ui_schedule_next, boundaryLabel(next, clock))
}

/** "Weekdays", "Every day", or the days spelled out. */
@Composable
private fun daySetLabel(days: Set<Int>, clock: ClockStyle): String = when (daySetOf(days)) {
    DaySet.NONE -> stringResource(R.string.ui_days_none)
    DaySet.EVERY_DAY -> stringResource(R.string.ui_days_every_day)
    DaySet.WEEKDAYS -> stringResource(R.string.ui_days_weekdays)
    DaySet.WEEKENDS -> stringResource(R.string.ui_days_weekends)
    DaySet.CUSTOM -> daysLabel(days, clock)
}

/**
 * "09:00 – 17:00", or "23:00 – 07:00 next day" when the window crosses midnight.
 *
 * Asks `ScheduleWindows.wrapsMidnight` rather than comparing the two minutes here, which
 * it used to do *inverted*: `start <= end` labelled a 09:00–09:00 window as an ordinary
 * same-day range, while the engine treats equal times as a full 24 hours. The row said
 * one thing and the phone did another, and the phone was right.
 */
@Composable
private fun rangeLabel(window: ScheduleWindow, clock: ClockStyle): String {
    val start = timeLabel(window.startMinuteOfDay, clock)
    val end = timeLabel(window.endMinuteOfDay, clock)
    val template = if (window.wrapsMidnight) {
        R.string.ui_schedule_range_next_day
    } else {
        R.string.ui_schedule_range
    }
    return stringResource(template, start, end)
}

/**
 * The missing exact-alarm grant, restated where a schedule is being created.
 *
 * Only [Remedy.OpenSettings] draws a button here. The exact-alarm grant is never
 * [Remedy.AskInApp] — it is a Settings toggle, not a runtime permission — so unlike the
 * health card at the top of the list there is no second kind of button to offer, and the
 * cases this cannot act on are simply the explanation on its own.
 */
@Composable
private fun ExactAlarmHint(remedy: Remedy, actions: ModeListActions) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.ui_schedule_exact_alarm_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        if (remedy is Remedy.OpenSettings) {
            TextButton(
                onClick = { actions.onOpenSettings(remedy.intent) },
                contentPadding = PaddingValues(0.dp),
            ) {
                Text(stringResource(R.string.action_fix))
            }
        }
    }
}

// -------------------------------------------------------------------- primitives

/** The editor's one card shape, so four sections cannot drift apart. */
@Composable
private fun EditorCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

@Composable
private fun LabelledChoices(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelLarge)
        content()
    }
}

/** Title, optional second line, switch. The pattern three cards here all need. */
@Composable
private fun SwitchRow(
    title: String,
    summary: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            summary?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun GlyphChoice(
    glyph: ModeGlyph,
    accent: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    SelectableSwatch(
        shape = RoundedCornerShape(percent = 50),
        selected = selected,
        accent = accent,
        onClick = onClick,
        modifier = Modifier.size(36.dp),
    ) {
        Icon(
            painter = painterResource(glyph.res),
            contentDescription = stringResource(glyph.labelRes),
            tint = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * A colour, and whether it is the one chosen.
 *
 * Deliberately not a [SelectableSwatch]: that component tints an unselected tile neutral and a
 * selected one with its accent, and here the fill *is* the value being chosen, so it has to be
 * the colour either way. What marks the selection is therefore a heavier ring in a neutral the
 * palette cannot collide with, plus a tick — a swatch that only changed its own colour's alpha
 * would be unreadable as "selected" against the seven other colours beside it.
 */
@Composable
private fun ColorChoice(color: Color, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(percent = 50)
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(shape)
            .background(color)
            .border(
                width = if (selected) 3.dp else 0.dp,
                color = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                shape = shape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = stringResource(R.string.picker_selected),
                tint = MaterialTheme.colorScheme.surface,
                modifier = Modifier.size(16.dp).padding(1.dp),
            )
        }
    }
}

/** Keeps both choice grids the same shape and inside the card's width. */
private const val CHOICES_PER_ROW = 4

/** Two per row: "Starred" and "Contacts" are too long for four across on a phone. */
private const val FILTERS_PER_ROW = 2
