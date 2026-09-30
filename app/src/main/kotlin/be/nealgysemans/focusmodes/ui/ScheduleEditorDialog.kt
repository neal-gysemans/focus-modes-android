package be.nealgysemans.focusmodes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import be.nealgysemans.focusmodes.R
import be.nealgysemans.focusmodes.data.ISO_WEEK
import be.nealgysemans.focusmodes.schedule.wrapsMidnight

/**
 * What one schedule is, as the user sets it: days, a start, an end, and whether it is
 * armed at all.
 *
 * A value type rather than a bag of loose state so the dialog can hold one `remember`
 * and the caller gets one object back to persist. Minutes-of-day, not `LocalTime`,
 * because that is what `params_json` stores and converting in two places is one place
 * too many.
 */
internal data class ScheduleDraft(
    val daysOfWeek: Set<Int>,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    val enabled: Boolean,
) {
    /** Nothing to arm: a window with no days never starts, so Save would be a lie. */
    val isValid: Boolean get() = daysOfWeek.isNotEmpty()

    /** `ScheduleWindows.wrapsMidnight` itself, not a copy of it, so it cannot disagree. */
    val wrapsMidnight: Boolean get() = wrapsMidnight(startMinuteOfDay, endMinuteOfDay)
}

/** Weekdays 09:00-17:00 — a first schedule someone is likely to keep. */
internal fun newScheduleDraft(): ScheduleDraft = ScheduleDraft(
    daysOfWeek = WEEKDAYS,
    startMinuteOfDay = 9 * 60,
    endMinuteOfDay = 17 * 60,
    enabled = true,
)

/**
 * Create or edit one schedule.
 *
 * A dialog rather than another full screen: a schedule is four fields, and pushing a
 * third level of navigation onto a tile-first app for four fields is worse than a
 * dialog that covers the list it came from.
 *
 * Edits are local until Save, and [onDelete] is only offered for a schedule that
 * already exists — a brand-new one is cancelled, not deleted. Validation is limited to
 * "at least one day", which is the only setting that makes a schedule inert; everything
 * else the user might mean is explained instead of refused (a window that wraps
 * midnight says so, and so does one that covers the whole day).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduleEditorDialog(
    initial: ScheduleDraft,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (ScheduleDraft) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val clock = rememberClockStyle()
    var draft by remember(initial) { mutableStateOf(initial) }
    // WHICH picker is open, or none. One slot rather than two booleans: they are
    // mutually exclusive and two flags can drift into both-true.
    var picking by remember { mutableStateOf<TimeField?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (isNew) R.string.ui_schedule_editor_new else R.string.ui_schedule_editor_edit,
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.ui_schedule_editor_days),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    DayToggles(
                        selected = draft.daysOfWeek,
                        clock = clock,
                        onToggle = { day ->
                            draft = draft.copy(
                                daysOfWeek = draft.daysOfWeek.toMutableSet().apply {
                                    if (!add(day)) remove(day)
                                },
                            )
                        },
                    )
                    if (!draft.isValid) {
                        Text(
                            text = stringResource(R.string.ui_schedule_no_days),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                TimeRow(
                    label = stringResource(R.string.ui_schedule_editor_start),
                    minuteOfDay = draft.startMinuteOfDay,
                    clock = clock,
                    onClick = { picking = TimeField.START },
                )
                TimeRow(
                    label = stringResource(R.string.ui_schedule_editor_end),
                    minuteOfDay = draft.endMinuteOfDay,
                    clock = clock,
                    onClick = { picking = TimeField.END },
                )

                // Why the end time can look "before" the start. Both cases are legal
                // and both are surprising, so both are said out loud.
                val note = when {
                    draft.startMinuteOfDay == draft.endMinuteOfDay ->
                        R.string.ui_schedule_editor_same_time
                    draft.wrapsMidnight -> R.string.ui_schedule_editor_wraps
                    else -> null
                }
                note?.let {
                    Text(
                        text = stringResource(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.ui_schedule_editor_enabled),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = draft.enabled,
                        onCheckedChange = { draft = draft.copy(enabled = it) },
                    )
                }

                onDelete?.let { delete ->
                    TextButton(onClick = delete) {
                        Text(
                            text = stringResource(R.string.ui_action_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft) }, enabled = draft.isValid) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )

    picking?.let { field ->
        val current = when (field) {
            TimeField.START -> draft.startMinuteOfDay
            TimeField.END -> draft.endMinuteOfDay
        }
        TimePickerDialog(
            initialMinuteOfDay = current,
            onDismiss = { picking = null },
            onPick = { minuteOfDay ->
                draft = when (field) {
                    TimeField.START -> draft.copy(startMinuteOfDay = minuteOfDay)
                    TimeField.END -> draft.copy(endMinuteOfDay = minuteOfDay)
                }
                picking = null
            },
        )
    }
}

private enum class TimeField { START, END }

/**
 * The Material 3 clock picker, in the dialog `TimePicker` does not bring with it.
 *
 * `is24Hour` is read from the device setting rather than left to the default, so the
 * picker agrees with the times the list shows — both go through the locale's own
 * convention, and a 24-hour phone showing an AM/PM dial is the kind of small wrongness
 * that makes a screen feel untrustworthy.
 *
 * Both entry modes are offered, because they are good at different things. The dial is
 * faster for "about nine-ish", which is what a bedtime usually is; typing is the only
 * bearable way to hit an exact minute, which is what you want when the schedule has to
 * line up with something else. One [TimePickerState] backs both, so switching keeps
 * whatever has been set so far.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    initialMinuteOfDay: Int,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    // The same `rememberClockStyle` every label in this file goes through, rather than a
    // second `DateFormat.is24HourFormat` read beside it. They cannot disagree now, which is
    // the whole point: a 24-hour phone showing an AM/PM dial for a value it then prints in
    // 24-hour is exactly the small wrongness this reads the device setting to avoid.
    val state = rememberTimePickerState(
        initialHour = initialMinuteOfDay / 60,
        initialMinute = initialMinuteOfDay % 60,
        is24Hour = rememberClockStyle().is24Hour,
    )
    var typing by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (typing) TimeInput(state = state) else TimePicker(state = state)
                TextButton(onClick = { typing = !typing }) {
                    Text(
                        stringResource(
                            if (typing) R.string.ui_time_pick_on_dial else R.string.ui_time_type_it,
                        ),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(state.hour * 60 + state.minute) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** A label and the time it holds; the whole row is the target, not just the time. */
@Composable
private fun TimeRow(
    label: String,
    minuteOfDay: Int,
    clock: ClockStyle,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            text = timeLabel(minuteOfDay, clock),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * Seven day toggles on one line.
 *
 * Circles with the locale's narrow day name rather than `FilterChip`s: seven chips do
 * not fit a dialog's width, and this is the shape every alarm clock and bedtime screen
 * on the platform already uses, so it needs no learning. The narrow name is one or two
 * characters and repeats across days in several languages ("M T W T F S S"), so the
 * full day name is attached as the content description — the visual label alone is not
 * enough for a screen reader, and in some locales not even for a sighted user.
 */
@Composable
private fun DayToggles(
    selected: Set<Int>,
    clock: ClockStyle,
    onToggle: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ISO_WEEK.forEach { day ->
            val isOn = day in selected
            val shape = RoundedCornerShape(percent = 50)
            Box(
                modifier = Modifier
                    .weight(1f)
                    // Square cells sized to whatever width the dialog gives us; a fixed
                    // 36.dp each overflows a narrow dialog once spacing is counted.
                    .aspectRatio(1f)
                    .clip(shape)
                    .background(
                        if (isOn) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                    .border(
                        width = if (isOn) 0.dp else 1.dp,
                        color = if (isOn) Color.Transparent else MaterialTheme.colorScheme.outlineVariant,
                        shape = shape,
                    )
                    .clickable(onClick = { onToggle(day) })
                    .semantics { contentDescription = dayName(day, clock) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = dayInitial(day, clock),
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    color = if (isOn) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}
