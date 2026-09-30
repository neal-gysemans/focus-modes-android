package be.nealgysemans.tilespike

import android.app.Dialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Dialog picker, implementation (a): classic Views.
 *
 * Roughly 30 lines, no lifecycle plumbing, no owners, nothing that can go wrong when
 * the host is a Service rather than an Activity. Compare with [ComposeModePickerDialog].
 */
object ClassicModePicker {

    fun create(
        context: Context,
        current: FakeMode?,
        onPick: (FakeMode?) -> Unit,
    ): Dialog {
        val dialog = Dialog(context, R.style.Theme_TileSpike_TileDialog)
        val inflater = LayoutInflater.from(dialog.context)
        val root = inflater.inflate(R.layout.dialog_picker_classic, null) as LinearLayout
        val rows = root.findViewById<LinearLayout>(R.id.picker_rows)

        fun addRow(label: String, iconRes: Int, selected: Boolean, pick: FakeMode?) {
            val row = inflater.inflate(R.layout.item_mode_row, rows, false)
            row.findViewById<ImageView>(R.id.row_icon).setImageResource(iconRes)
            row.findViewById<TextView>(R.id.row_label).text = label
            row.findViewById<TextView>(R.id.row_check).visibility =
                if (selected) View.VISIBLE else View.INVISIBLE
            row.setOnClickListener {
                // Instant selection: no confirm button, the tap IS the commit.
                onPick(pick)
                dialog.dismiss()
            }
            rows.addView(row)
        }

        FakeMode.entries.forEach { mode ->
            addRow(mode.label, mode.iconRes, current == mode, mode)
        }
        addRow("Off", R.drawable.ic_focus_off, current == null, null)

        dialog.setContentView(
            root,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        dialog.setCanceledOnTouchOutside(true)
        return dialog
    }
}
