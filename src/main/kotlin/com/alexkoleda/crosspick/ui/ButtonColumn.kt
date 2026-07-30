package com.alexkoleda.crosspick.ui

import com.alexkoleda.crosspick.model.CommitRow
import com.alexkoleda.crosspick.model.TransferStatus
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Component
import javax.swing.JButton
import javax.swing.JTable
import javax.swing.table.TableCellRenderer

/**
 * A [ColumnInfo] that renders a single action button per row, used to trigger a
 * cherry-pick for a given [CommitRow]. The button's label and enabled state reflect
 * the row's current [TransferStatus].
 *
 * This column is deliberately **not** editable: Swing's cell-editor mechanism is
 * unreliable for delivering the click that starts editing to a button embedded in a
 * table cell (the initiating mouse event is not forwarded to the editor component, so
 * clicks are often swallowed or need to be doubled). Instead, [CrossPickPanel] installs
 * a [java.awt.event.MouseAdapter] directly on the table that detects clicks on this
 * column and invokes [onCherryPick] itself. See [getRenderer] / [ButtonRenderer] for the
 * purely-visual button used to paint each cell.
 */
class ButtonColumn(
    name: String,
    private val onCherryPick: (CommitRow) -> Unit
) : ColumnInfo<CommitRow, String>(name) {

    override fun valueOf(item: CommitRow): String = labelFor(item)

    override fun isCellEditable(item: CommitRow): Boolean = false

    override fun getRenderer(item: CommitRow): TableCellRenderer =
        ButtonRenderer()

    private fun labelFor(row: CommitRow): String = when (row.status) {
        TransferStatus.PENDING -> "Cherry-pick"
        TransferStatus.IN_PROGRESS -> "…"
        TransferStatus.TRANSFERRED -> "Transferred"
        TransferStatus.ALREADY_APPLIED -> "Already applied"
        TransferStatus.FAILED -> "Retry"
        TransferStatus.SKIPPED -> "Skipped"
    }

    private fun enabledFor(row: CommitRow): Boolean =
        row.status == TransferStatus.PENDING || row.status == TransferStatus.FAILED

    /**
     * Returns whether the given [row] is currently clickable, i.e. would trigger
     * [onCherryPick] if its cell were clicked. Used by [CrossPickPanel]'s mouse
     * listener to decide whether to act on a click.
     */
    fun isClickable(row: CommitRow): Boolean = enabledFor(row)

    /** Invokes [onCherryPick] for [row]. Called by [CrossPickPanel]'s mouse listener. */
    fun fireClicked(row: CommitRow) = onCherryPick(row)

    private inner class ButtonRenderer : JButton(), TableCellRenderer {
        init {
            isOpaque = true
            margin = JBUI.insets(2, 8)
        }

        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): Component {
            val model = table.model
            val commitRow = (model as? CommitTableModel)?.getItem(row)
            text = commitRow?.let { labelFor(it) } ?: (value as? String ?: "")
            isEnabled = commitRow?.let { enabledFor(it) } ?: true

            val background: Color
            val foreground: Color
            if (isSelected) {
                background = table.selectionBackground
                foreground = table.selectionForeground
            } else {
                background = table.background
                foreground = table.foreground
            }
            this.background = background
            this.foreground = foreground

            return this
        }
    }
}
