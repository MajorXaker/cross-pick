package com.alexkoleda.crosspick.ui

import com.alexkoleda.crosspick.git.GitOps
import com.alexkoleda.crosspick.model.CommitRow
import com.alexkoleda.crosspick.model.TransferStatus
import com.alexkoleda.crosspick.service.CrossPickService
import com.alexkoleda.crosspick.state.CrossPickState
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.JBPopupMenu
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.TableView
import com.intellij.util.ui.JBUI
import com.intellij.vcsUtil.VcsUtil
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import javax.swing.Box
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.BoxLayout
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities

/**
 * Root UI panel for the CrossPick tool window. Presents a small form for the source
 * repository coordinates (URL, branch, initial commit SHA), a table of discovered
 * commits with per-row cherry-pick actions, and a status line. All git work is
 * delegated to [CrossPickService] and runs off the EDT; this panel only mutates Swing
 * state on the EDT.
 */
class CrossPickPanel(private val project: Project) : SimpleToolWindowPanel(true, true) {

    private val sourceUrlField = JBTextField()
    private val sourceBranchField = JBTextField()
    private val initialShaField = JBTextField()
    private val statusLabel = JBLabel(" ")
    private val hintLabel = JBLabel("Nothing is pushed \u2014 push manually when ready.")

    private val tableModel = CommitTableModel(
        { row -> onCherryPickClicked(row) },
        { row -> onAbortOrSkip(row) }
    )
    private val table = TableView<CommitRow>(tableModel)

    /** The cherry-pick [ButtonColumn] instance, used by the mouse listener below. */
    private val cherryPickColumn: ButtonColumn = tableModel.columnInfos.filterIsInstance<ButtonColumn>().first()

    /** Model column index of the cherry-pick button column, resolved once against [tableModel]. */
    private val cherryPickColumnModelIndex: Int = tableModel.columnInfos.indexOfFirst { it === cherryPickColumn }

    /** The abort/skip column instance, used by the mouse listener. */
    private val abortSkipColumn: AbortSkipColumn = tableModel.columnInfos.filterIsInstance<AbortSkipColumn>().first()

    /** Model column index of the abort/skip button column. */
    private val abortSkipColumnModelIndex: Int = tableModel.columnInfos.indexOfFirst { it === abortSkipColumn }

    private val scanButton: javax.swing.JButton
    private val exportButton: javax.swing.JButton

    private val state: CrossPickState
        get() = project.getService(CrossPickState::class.java)

    private val service: CrossPickService
        get() = project.getService(CrossPickService::class.java)

    private var taskRunning: Boolean = false
    private var currentRows: List<CommitRow> = emptyList()

    init {
        val formPanel = panel {
            row("Source repo URL:") {
                cell(sourceUrlField).align(AlignX.FILL)
            }
            row("Source branch:") {
                cell(sourceBranchField).align(AlignX.FILL)
            }
            row("Initial commit SHA:") {
                cell(initialShaField).align(AlignX.FILL)
            }
        }

        scanButton = javax.swing.JButton("Scan").apply { addActionListener { onScanClicked() } }
        exportButton = javax.swing.JButton("Export to CSV").apply { addActionListener { onExportClicked() } }
        exportButton.isEnabled = false

        val scanRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = JBUI.Borders.empty(4, 0)
            add(scanButton)
            add(javax.swing.Box.createHorizontalStrut(8))
            add(exportButton)
        }

        val buttonsPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(scanRow)
        }

        val topPanel = JPanel(BorderLayout()).apply {
            add(formPanel, BorderLayout.CENTER)
            add(buttonsPanel, BorderLayout.SOUTH)
        }

        val bottomPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(4, 8)
            add(statusLabel)
            add(hintLabel)
        }

        table.selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        table.rowHeight = JBUI.scale(28)
        configureColumnWidths()
        installTablePopupMenu()
        installButtonColumnClickHandler()

        setToolbar(null)
        setContent(
            JPanel(BorderLayout()).apply {
                add(topPanel, BorderLayout.NORTH)
                add(JBScrollPane(table), BorderLayout.CENTER)
                add(bottomPanel, BorderLayout.SOUTH)
            }
        )

        prefillFromState()
    }

    /** Populates the form fields from the last persisted scan inputs, if any. */
    private fun prefillFromState() {
        val data = state.state
        sourceUrlField.text = data.lastSourceUrl
        sourceBranchField.text = data.lastSourceBranch
        initialShaField.text = data.lastInitialSha
    }

    private fun setStatus(text: String) {
        statusLabel.text = text
    }

    private fun setTaskRunning(running: Boolean) {
        taskRunning = running
        scanButton.isEnabled = !running
    }

    /**
     * Refreshes the enabled state of [continueButton], [abortButton], and [skipButton]
     * to reflect whether a cherry-pick is currently halted in the target repository.
     * These three buttons only ever act on that single in-flight commit.
     */

    private fun onScanClicked() {
        val url = sourceUrlField.text.trim()
        val branch = sourceBranchField.text.trim()
        val sha = initialShaField.text.trim()

        if (url.isEmpty() || branch.isEmpty() || sha.isEmpty()) {
            setStatus("Please fill in source URL, branch, and initial commit SHA.")
            return
        }

        state.state.lastSourceUrl = url
        state.state.lastSourceBranch = branch
        state.state.lastInitialSha = sha

        setTaskRunning(true)
        setStatus("Scanning $url ($branch)\u2026")

        service.scan(url, branch, sha) { result ->
            setTaskRunning(false)
            result.fold(
                onSuccess = { rows ->
                    currentRows = rows
                    tableModel.items = rows
                    exportButton.isEnabled = rows.isNotEmpty()
                    setStatus("Found ${rows.size} commit(s).")
                },
                onFailure = { error ->
                    setStatus("Scan failed: ${error.message}")
                }
            )
        }
    }

    private fun onCherryPickClicked(row: CommitRow) {
        if (row.status != TransferStatus.PENDING && row.status != TransferStatus.FAILED) return

        row.status = TransferStatus.IN_PROGRESS
        refreshTable()
        setStatus("Cherry-picking ${row.shortSha}\u2026")

        service.cherryPick(row) { result ->
            result.fold(
                onSuccess = { status ->
                    row.status = status
                    setStatus(
                        when (status) {
                            TransferStatus.TRANSFERRED -> "Cherry-picked ${row.shortSha}."
                            TransferStatus.ALREADY_APPLIED -> "${row.shortSha} was already applied."
                            TransferStatus.FAILED -> row.message ?: "Cherry-pick of ${row.shortSha} failed."
                            else -> " "
                        }
                    )
                },
                onFailure = { error ->
                    row.status = TransferStatus.FAILED
                    row.message = error.message
                    setStatus("Cherry-pick failed: ${error.message}")
                }
            )
            refreshTable()
        }
    }

    private fun onExportClicked() {
        if (currentRows.isEmpty()) {
            setStatus("No commits to export.")
            return
        }

        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("csv")
        val dialog = FileChooserFactory.getInstance().createFileChooser(descriptor, project, null)
        val files = dialog.choose(project, null)

        if (files.isEmpty()) return

        val selectedFile = files.first()
        val filePath = selectedFile.path

        setStatus("Exporting to $filePath\u2026")
        service.exportToCSV(currentRows, filePath) { result ->
            result.fold(
                onSuccess = { path ->
                    setStatus("Exported ${currentRows.size} commit(s) to $path")
                },
                onFailure = { error ->
                    setStatus("Export failed: ${error.message}")
                }
            )
        }
    }

    private fun onAbortOrSkip(row: CommitRow) {
        setStatus("Checking cherry-pick state\u2026")
        // Run the check in a background task to avoid EDT violations
        object : Task.Backgroundable(project, "CrossPick: Checking cherry-pick state", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val repo = getRepository()
                    val isInProgress = repo != null && GitOps.isCherryPickInProgress(repo)

                    ApplicationManager.getApplication().invokeLater {
                        if (isInProgress) {
                            // A cherry-pick is in progress, abort it
                            setStatus("Aborting cherry-pick\u2026")
                            service.abortInProgressPick { result ->
                                setStatus(
                                    result.fold(
                                        onSuccess = { "Cherry-pick aborted." },
                                        onFailure = { error -> "Abort failed: ${error.message}" }
                                    )
                                )
                                refreshTable()
                            }
                        } else {
                            // No cherry-pick in progress, mark as skipped
                            service.setRowStatus(row, TransferStatus.SKIPPED)
                            setStatus("Marked ${row.shortSha} as skipped.")
                            refreshTable()
                        }
                    }
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        setStatus("Error: ${e.message}")
                    }
                }
            }
        }.queue()
    }

    /** Gets the git repository for the current project. */
    private fun getRepository(): GitRepository? {
        val manager = GitRepositoryManager.getInstance(project)
        val repositories = manager.repositories
        if (repositories.isEmpty()) return null
        if (repositories.size == 1) return repositories.first()
        val basePath = project.basePath ?: return repositories.first()
        val projectDir: VirtualFile = LocalFileSystem.getInstance().findFileByPath(basePath) ?: return repositories.first()
        val rootFile: VirtualFile? = VcsUtil.getVcsRootFor(project, projectDir)
        return repositories.firstOrNull { it.root == rootFile } ?: repositories.first()
    }

    private fun refreshTable() {
        tableModel.fireTableDataChanged()
    }

    /**
     * Gives each column a sensible preferred width: SHA narrow (monospace, fixed-ish
     * length), Date/Author medium, Subject wide (it should absorb most extra space),
     * cherry-pick button column fixed, and X button column narrow.
     */
    private fun configureColumnWidths() {
        val columnModel = table.columnModel
        if (columnModel.columnCount < 6) return
        columnModel.getColumn(0).preferredWidth = JBUI.scale(90)   // SHA
        columnModel.getColumn(1).preferredWidth = JBUI.scale(140)  // Date
        columnModel.getColumn(2).preferredWidth = JBUI.scale(120)  // Author
        columnModel.getColumn(3).preferredWidth = JBUI.scale(320)  // Subject
        columnModel.getColumn(4).preferredWidth = JBUI.scale(120)  // Cherry-pick button
        columnModel.getColumn(5).preferredWidth = JBUI.scale(40)   // X button
    }

    /**
     * Installs a left-click handler on [table] that treats clicks landing in the
     * cherry-pick button column as a button press, invoking [onCherryPickClicked] for
     * the resolved row. This deliberately bypasses Swing's cell-editor mechanism
     * (see [ButtonColumn]'s docs for why) and instead resolves the clicked cell
     * directly from the mouse event, converting view indices to model indices since
     * the table may be sorted. Also swaps in a hand cursor while hovering over a
     * clickable button cell. Coexists with the right-click popup menu installed by
     * [installTablePopupMenu] because it only acts on left-clicks.
     */
    private fun installButtonColumnClickHandler() {
        fun resolveRowAndActionAt(point: java.awt.Point): Pair<CommitRow, String>? {
            val viewColumn = table.columnAtPoint(point)
            val viewRow = table.rowAtPoint(point)
            if (viewColumn < 0 || viewRow < 0) return null

            val modelColumn = table.convertColumnIndexToModel(viewColumn)
            val modelRow = table.convertRowIndexToModel(viewRow)
            if (modelRow < 0 || modelRow >= tableModel.rowCount) return null

            val row = tableModel.getItem(modelRow)
            return when (modelColumn) {
                cherryPickColumnModelIndex -> {
                    if (cherryPickColumn.isClickable(row)) Pair(row, "cherry-pick") else null
                }
                abortSkipColumnModelIndex -> {
                    if (abortSkipColumn.isClickable(row)) Pair(row, "abort-skip") else null
                }
                else -> null
            }
        }

        fun repaintRow(viewRow: Int) {
            if (viewRow < 0) return
            val rowRect = table.getCellRect(viewRow, 0, true)
            rowRect.width = table.width
            table.repaint(rowRect)
        }

        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) return
                val (row, action) = resolveRowAndActionAt(e.point) ?: return
                val viewRow = table.rowAtPoint(e.point)
                when (action) {
                    "cherry-pick" -> onCherryPickClicked(row)
                    "abort-skip" -> abortSkipColumn.fireClicked(row)
                }
                repaintRow(viewRow)
            }

            override fun mousePressed(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) return
                val viewRow = table.rowAtPoint(e.point)
                if (resolveRowAndActionAt(e.point) != null) {
                    repaintRow(viewRow)
                }
            }
        })

        table.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val clickable = resolveRowAndActionAt(e.point) != null
                table.cursor = if (clickable) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
            }
        })
    }

    /** Installs a right-click context menu on [table] for marking selected rows' status. */
    private fun installTablePopupMenu() {
        val popupMenu = JBPopupMenu()

        val markSkippedItem = JMenuItem("Mark as skipped").apply {
            addActionListener { applyStatusToSelectedRows(TransferStatus.SKIPPED) }
        }
        val markTransferredItem = JMenuItem("Mark as transferred").apply {
            addActionListener { applyStatusToSelectedRows(TransferStatus.TRANSFERRED) }
        }
        val resetPendingItem = JMenuItem("Reset to pending").apply {
            addActionListener { applyStatusToSelectedRows(TransferStatus.PENDING) }
        }
        val copyShaItem = JMenuItem("Copy full SHA").apply {
            addActionListener { copySelectedRowsSha() }
        }

        popupMenu.add(markSkippedItem)
        popupMenu.add(markTransferredItem)
        popupMenu.add(resetPendingItem)
        popupMenu.addSeparator()
        popupMenu.add(copyShaItem)

        table.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = maybeShowPopup(e)
            override fun mouseReleased(e: MouseEvent) = maybeShowPopup(e)

            private fun maybeShowPopup(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val viewRow = table.rowAtPoint(e.point)
                if (viewRow >= 0 && !table.selectionModel.isSelectedIndex(viewRow)) {
                    table.setRowSelectionInterval(viewRow, viewRow)
                }
                if (table.selectedRowCount > 0) {
                    popupMenu.show(e.component, e.x, e.y)
                }
            }
        })
    }

    /** Returns the [CommitRow]s currently selected in [table], mapped from view to model rows. */
    private fun selectedRows(): List<CommitRow> =
        table.selectedRows
            .map { table.convertRowIndexToModel(it) }
            .map { tableModel.getItem(it) }

    /** Applies [status] to every currently-selected row via [CrossPickService.setRowStatus]. */
    private fun applyStatusToSelectedRows(status: TransferStatus) {
        val rows = selectedRows()
        if (rows.isEmpty()) return
        for (row in rows) {
            service.setRowStatus(row, status)
        }
        refreshTable()
        setStatus("Marked ${rows.size} commit(s) as ${status.name.lowercase()}.")
    }

    /** Copies the full SHAs of all currently-selected rows to the clipboard, one per line. */
    private fun copySelectedRowsSha() {
        val rows = selectedRows()
        if (rows.isEmpty()) return
        val text = rows.joinToString("\n") { it.fullSha }
        val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(text), null)
        setStatus("Copied ${rows.size} SHA(s) to clipboard.")
    }
}
