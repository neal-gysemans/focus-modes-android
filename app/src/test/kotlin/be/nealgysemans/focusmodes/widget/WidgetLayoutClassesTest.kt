package be.nealgysemans.focusmodes.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every hand-written layout a widget host inflates may only use the view classes
 * RemoteViews allows.
 *
 * The launcher, not this app, inflates these, so a disallowed class fails nowhere in our
 * process: the picker just says "Couldn't add widget". A plain `<View>` hairline in the
 * preview did exactly that on HyperOS, and nothing in the build noticed.
 */
class WidgetLayoutClassesTest {

    @Test
    fun `widget layouts use only RemoteViews-allowed classes`() {
        val layouts = widgetLayouts()
        assertTrue("no widget layouts found — did the info XML move?", layouts.isNotEmpty())

        val offenders = layouts.flatMap { file ->
            tagsIn(file).filterNot { it in ALLOWED }.map { "${file.name}: <$it>" }
        }

        assertEquals(emptyList<String>(), offenders)
    }

    /** The `@layout/` resources the widget info XML points at that live in this app. */
    private fun widgetLayouts(): List<File> {
        val res = File("src/main/res")
        val info = File(res, "xml").listFiles().orEmpty().filter { it.readText().contains("<appwidget-provider") }
        return info.flatMap { Regex("@layout/(\\w+)").findAll(it.readText()).map { m -> m.groupValues[1] }.toList() }
            .map { File(res, "layout/$it.xml") }
            .filter { it.exists() }
    }

    private fun tagsIn(file: File): Set<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("*")
        return (0 until nodes.length).map { nodes.item(it).nodeName }.toSet()
    }

    private companion object {
        /** The classes `RemoteViews` documents as supported in app widgets. */
        val ALLOWED = setOf(
            "FrameLayout", "GridLayout", "LinearLayout", "RelativeLayout",
            "AnalogClock", "Button", "Chronometer", "ImageButton", "ImageView",
            "ProgressBar", "TextClock", "TextView",
            "AdapterViewFlipper", "GridView", "ListView", "StackView", "ViewFlipper",
            "CheckBox", "RadioButton", "RadioGroup", "Switch",
            "ViewStub", "merge", "include",
        )
    }
}
