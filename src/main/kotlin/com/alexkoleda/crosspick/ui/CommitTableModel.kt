package com.alexkoleda.crosspick.ui

import com.alexkoleda.crosspick.model.CommitRow
import com.alexkoleda.crosspick.model.TransferStatus
import com.intellij.ui.JBColor
import com.intellij.ui.table.JBTable
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.font.TextAttribute
import javax.swing.JButton
import javax.swing.JTable
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer

/**
 * Table model backing the CrossPick commit list. Renders SHA, date, author, and subject
 * columns for each [CommitRow], plus a trailing action column built by [ButtonColumn].
 * Rows whose [CommitRow.status] is [TransferStatus.TRANSFERRED] or
 * [TransferStatus.ALREADY_APPLIED] are rendered with strikethrough text and a dimmed
 * foreground color to visually mark them as already handled. Rows whose status is
 * [TransferStatus.SKIPPED] are rendered dimmed and italic, but deliberately *without*
 * strikethrough — that styling stays reserved for commits that were actually
 * transferred.
 */
class CommitTableModel(
    onCherryPick: (CommitRow) -> Unit,
    private val onAbortOrSkip: (CommitRow) -> Unit = {}
) :
    ListTableModel<CommitRow>(
        ShaColumn(),
        DateColumn(),
        AuthorColumn(),
        SubjectColumn(),
        ButtonColumn("Cherry-pick", onCherryPick),
        AbortSkipColumn(onAbortOrSkip)
    ) {

    companion object {
        /** Dimmed foreground used for both transferred and skipped rows, theme-aware. */
        private val DIM_FOREGROUND = JBColor.GRAY

        /** Applies the shared "transferred"/"skipped" row styling to [component]. */
        fun styleForRow(component: Component, row: CommitRow, isSelected: Boolean) {
            val isTransferredLike = row.status == TransferStatus.TRANSFERRED || row.status == TransferStatus.ALREADY_APPLIED
            val isSkipped = row.status == TransferStatus.SKIPPED
            val baseFont = component.font
            component.font = when {
                isTransferredLike -> {
                    val attributes = baseFont.attributes.toMutableMap()
                    attributes[TextAttribute.STRIKETHROUGH] = TextAttribute.STRIKETHROUGH_ON
                    Font(attributes)
                }
                isSkipped -> {
                    val attributes = baseFont.attributes.toMutableMap()
                    attributes[TextAttribute.POSTURE] = TextAttribute.POSTURE_OBLIQUE
                    Font(attributes)
                }
                else -> Font(baseFont.attributes)
            }
            if (!isSelected) {
                component.foreground = if (isTransferredLike || isSkipped) DIM_FOREGROUND else UIManagerColor.textForeground()
            }
        }
    }
}

/** Small helper to avoid importing UIManager at multiple call sites. */
private object UIManagerColor {
    fun textForeground(): Color = javax.swing.UIManager.getColor("Table.foreground") ?: Color.BLACK
}

/** Base renderer applying the shared transferred-row styling on top of default rendering. */
private open class BaseRowRenderer : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int
    ): Component {
        val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
        val model = (table as? JBTable)?.model as? ListTableModel<*>
        @Suppress("UNCHECKED_CAST")
        val commitRow = (model?.getItem(row) as? CommitRow)
        if (commitRow != null) {
            CommitTableModel.styleForRow(component, commitRow, isSelected)
        }
        return component
    }
}

private class ShaColumn : ColumnInfo<CommitRow, String>("Commit") {
    override fun valueOf(item: CommitRow): String = item.shortSha

    override fun getRenderer(item: CommitRow): TableCellRenderer = object : BaseRowRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): Component {
            val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            component.font = Font(Font.MONOSPACED, component.font.style, component.font.size)
            return component
        }
    }
}

private class DateColumn : ColumnInfo<CommitRow, String>("Date") {
    override fun valueOf(item: CommitRow): String = DateFormatUtil.formatDateTime(item.commitTimeMillis)
    override fun getRenderer(item: CommitRow): TableCellRenderer = BaseRowRenderer()
}

private class AuthorColumn : ColumnInfo<CommitRow, String>("Author") {
    override fun valueOf(item: CommitRow): String = item.authorName
    override fun getRenderer(item: CommitRow): TableCellRenderer = BaseRowRenderer()
}

private class SubjectColumn : ColumnInfo<CommitRow, String>("Subject") {
    override fun valueOf(item: CommitRow): String = item.subject
    override fun getRenderer(item: CommitRow): TableCellRenderer = BaseRowRenderer()
}

class AbortSkipColumn(private val onAbortOrSkip: (CommitRow) -> Unit) :
    ColumnInfo<CommitRow, String>("") {

    override fun valueOf(item: CommitRow): String = "×"
    override fun isCellEditable(item: CommitRow): Boolean = false
    override fun getRenderer(item: CommitRow): TableCellRenderer = AbortSkipRenderer()

    fun isClickable(row: CommitRow): Boolean =
        row.status != TransferStatus.IN_PROGRESS

    fun fireClicked(row: CommitRow) = onAbortOrSkip(row)

    private inner class AbortSkipRenderer : JButton(), TableCellRenderer {
        init {
            isOpaque = true
            font = font.deriveFont(font.size * 1.5f)
            margin = JBUI.insets(0, 4)
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
            text = "×"
            isEnabled = commitRow?.let { isClickable(it) } ?: true

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
