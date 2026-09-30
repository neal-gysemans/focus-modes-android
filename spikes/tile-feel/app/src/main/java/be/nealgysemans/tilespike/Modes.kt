package be.nealgysemans.tilespike

import androidx.annotation.DrawableRes

/**
 * Three fake modes. Deliberately fake: this spike measures the *tile surface*,
 * not the mode engine. Only one zen rule exists behind all three.
 */
enum class FakeMode(
    val label: String,
    @param:DrawableRes val iconRes: Int,
) {
    WORK("Work", R.drawable.ic_mode_work),
    SLEEP("Sleep", R.drawable.ic_mode_sleep),
    PERSONAL("Personal", R.drawable.ic_mode_personal),
    ;

    companion object {
        fun fromNameOrNull(value: String?): FakeMode? =
            entries.firstOrNull { it.name == value }
    }
}

/** What a single tap on the Focus tile should do. Switchable from the app. */
enum class TileBehavior(val title: String, val blurb: String) {
    TOGGLE(
        "Tap = toggle",
        "Optimistic tile flip, then the zen rule. This is the path we time.",
    ),
    DIALOG_CLASSIC(
        "Tap = dialog (classic View)",
        "showDialog() with an inflated LinearLayout. No Compose plumbing.",
    ),
    DIALOG_COMPOSE(
        "Tap = dialog (ComposeView)",
        "showDialog() with a ComposeView + hand-wired ViewTree owners.",
    ),
    ACTIVITY(
        "Tap = activity",
        "startActivityAndCollapse(PendingIntent). The Intent overload throws on targetSdk 34+.",
    ),
    ;

    companion object {
        val DEFAULT = TOGGLE

        fun fromNameOrDefault(value: String?): TileBehavior =
            entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}
