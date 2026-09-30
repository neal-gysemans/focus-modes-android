package be.nealgysemans.focusmodes.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import be.nealgysemans.focusmodes.R

/**
 * One entry in the app's bundled glyph set.
 *
 * @property key the value stored in `ModeEntity.iconKey`. Stable forever: resource
 *   ids move between builds, so the database must never hold one.
 * @property res the monochrome silhouette. Tinted by the Quick Settings tile, by
 *   the notification shade, and by Compose — so the drawable itself is colourless.
 * @property labelRes the human name, used as the content description in the picker
 *   (a bare glyph with no label is unreadable to TalkBack).
 */
data class ModeGlyph(
    val key: String,
    @param:DrawableRes val res: Int,
    @param:StringRes val labelRes: Int,
)

/**
 * The glyph set every surface draws from — Quick Settings tile, tile picker,
 * QS_TILE_PREFERENCES grid, the ongoing notification's small icon, and the mode
 * editor's icon picker.
 *
 * It lives in `ui/` because it is presentation metadata, but `tile/` and
 * `notification/` read it too: one registry means the tile and the notification
 * cannot disagree about what "moon" looks like, which is the whole point of
 * storing a key rather than a drawable.
 *
 * Adding a glyph is append-only. Removing one would orphan any mode still holding
 * its key, so [resFor] falls back rather than throwing.
 */
object ModeGlyphs {

    /** Shown when no mode is active: a hollow ring against the solid mode glyphs. */
    @DrawableRes
    val OFF_RES: Int = R.drawable.ic_focus_off

    /** Display order in the editor's icon picker. */
    val ALL: List<ModeGlyph> = listOf(
        ModeGlyph("briefcase", R.drawable.ic_mode_briefcase, R.string.glyph_briefcase),
        ModeGlyph("moon", R.drawable.ic_mode_moon, R.string.glyph_moon),
        ModeGlyph("person", R.drawable.ic_mode_person, R.string.glyph_person),
        ModeGlyph("book", R.drawable.ic_mode_book, R.string.glyph_book),
        ModeGlyph("home", R.drawable.ic_mode_home, R.string.glyph_home),
        ModeGlyph("dumbbell", R.drawable.ic_mode_dumbbell, R.string.glyph_dumbbell),
        ModeGlyph("star", R.drawable.ic_mode_star, R.string.glyph_star),
    )

    private val byKey: Map<String, ModeGlyph> = ALL.associateBy(ModeGlyph::key)

    /**
     * The drawable for [key], or the first glyph in the set when the key is unknown.
     *
     * Unknown keys are a normal outcome, not a bug: a database restored from a newer
     * build can name a glyph this build does not ship. Falling back keeps the mode
     * usable instead of crashing the tile.
     */
    @DrawableRes
    fun resFor(key: String?): Int = byKey[key]?.res ?: ALL.first().res

    /** Every drawable the tile has to be able to paint, for its `Icon` pre-warm. */
    val ALL_RES: List<Int> = ALL.map(ModeGlyph::res) + OFF_RES
}

/**
 * The colours offered by the mode editor.
 *
 * A fixed palette rather than a free colour wheel: these are ARGB ints that end up
 * tinting a tile glyph and a notification accent, and half the wheel produces
 * something invisible against one theme or the other. The first three are the
 * seeded Work / Sleep / Personal colours, so an untouched mode's colour is always
 * in the palette and shows as selected.
 */
object ModePalette {

    val COLORS: List<Int> = listOf(
        0xFF3D5AFE.toInt(), // indigo — seeded Work
        0xFF7E57C2.toInt(), // violet — seeded Sleep
        0xFF26A69A.toInt(), // teal — seeded Personal
        0xFF42A5F5.toInt(), // blue
        0xFF66BB6A.toInt(), // green
        0xFFFFA726.toInt(), // orange
        0xFFEF5350.toInt(), // red
        0xFFEC407A.toInt(), // pink
    )
}
