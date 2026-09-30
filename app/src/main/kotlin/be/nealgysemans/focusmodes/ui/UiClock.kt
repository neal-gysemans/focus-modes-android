package be.nealgysemans.focusmodes.ui

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import java.time.ZonedDateTime

/**
 * The wall clock, as Compose state that actually advances.
 *
 * "Next: Mon 09:00" is read off the current time, and a plain `ZonedDateTime.now()` in a
 * composable is a value Compose has no reason to ever recompute — leave the app open
 * across 09:00 and the list keeps promising a boundary that has already gone by. Same
 * shape of bug `rememberHealthChecks` was written to fix, one layer down.
 *
 * Ticks on the minute rather than on a fixed interval: every label derived from this is
 * minute-resolution, so waking up at :30 would recompose the screen for nothing, and a
 * fixed 60-second timer started at :59 would leave every label a minute stale for its
 * whole life. The delay is recomputed each pass, so the tick cannot drift.
 *
 * The coroutine is scoped to the composition, so it stops when the screen goes away.
 */
@Composable
internal fun rememberNow(): ZonedDateTime {
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(millisUntilNextMinute(now))
            now = ZonedDateTime.now()
        }
    }
    return now
}

private fun millisUntilNextMinute(now: ZonedDateTime): Long {
    val millisIntoMinute = now.toInstant().toEpochMilli() % MILLIS_PER_MINUTE
    return MILLIS_PER_MINUTE - millisIntoMinute
}

/**
 * How this device wants dates and times written: its language, and its hour convention.
 *
 * Two different sources, which is the whole reason this exists as one call. The locale
 * is read from the composition's configuration rather than `Locale.getDefault()` so a
 * per-app language override is honoured and labels recompose when the language changes.
 * The hour convention is the *setting* — `DateFormat.is24HourFormat` — not the locale's
 * default, because they disagree on plenty of phones (an `en_US` device with the
 * 24-hour clock switched on is the ordinary case) and the Material time picker follows
 * the setting. Every time this app prints has to follow it too, or a value reads back
 * in a different convention than the one it was typed in.
 *
 * [LocalConfiguration], not `LocalContext.current.resources.configuration`: the latter is the
 * same object but reading it through the context is not configuration-aware, so a language
 * change would not invalidate this composition and the labels would keep the old locale until
 * something else recomposed them. Exactly the class of staleness bug this whole file exists to
 * fix, and the one the platform's own lint check names.
 */
@Composable
internal fun rememberClockStyle(): ClockStyle {
    val configuration = LocalConfiguration.current
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    return remember(configuration, is24Hour) {
        ClockStyle(locale = configuration.locales[0], is24Hour = is24Hour)
    }
}

private const val MILLIS_PER_MINUTE = 60_000L
