package com.alexkoleda.crosspick.ui

import com.alexkoleda.crosspick.git.GitOps
import com.alexkoleda.crosspick.model.CommitRow
import com.alexkoleda.crosspick.model.TransferStatus
import com.alexkoleda.crosspick.service.CrossPickService
import com.alexkoleda.crosspick.state.CrossPickState
import com.intellij.dvcs.repo.Repository
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.JBPopupMenu
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vcs.AbstractVcsHelper
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.TableView
import com.intellij.util.ui.JBUI
import git4idea.GitVcs
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.BoxLayout
import javax.swing.JButton
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
 *
 * Only one CrossPick git operation runs at a time ([busy]). When a cherry-pick stops
 * on conflicts, the IDE's merge dialog is opened on the conflicted files and the row
 * shows "Resolving…" ([TransferStatus.CONFLICT]); the panel then listens for git
 * repository changes and, once the target repository leaves the cherry-picking state,
 * marks the row [TransferStatus.TRANSFERRED] (HEAD moved: the user committed) or back
 * to [TransferStatus.PENDING] (HEAD unchanged: the pick was aborted).
 */
class CrossPickPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    /** A cherry-pick of [row] that stopped on conflicts and hasn't been committed or aborted yet. */
    private class ActiveConflict(
        val row: CommitRow,
        val repoRoot: VirtualFile,
        val headBefore: String?,
        val sourceUrl: String
    )

    private val sourceUrlField = JBTextField()
    private val sourceBranchField = JBTextField()
    private val initialShaField = JBTextField()
    private val statusLabel = JBLabel(" ")
    private val hintLabel = JBLabel("Nothing is pushed — push manually when ready.")

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

    private val scanButton: JButton

    private val state: CrossPickState
        get() = project.getService(CrossPickState::class.java)

    private val service: CrossPickService
        get() = project.getService(CrossPickService::class.java)

    /** True while a CrossPick git operation (scan, cherry-pick, abort) is running. */
    private var busy: Boolean = false

    /**
     * Source URL the rows currently in the table were scanned from. Keys every persisted
     * status change, so editing the URL field (or a failed scan of another URL) can't
     * file records under the wrong source.
     */
    private var scannedSourceUrl: String? = null

    private var activeConflict: ActiveConflict? = null

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

        scanButton = JButton("Scan").apply { addActionListener { onScanClicked() } }

        val buttonsPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = JBUI.Borders.empty(4, 0)
            add(scanButton)
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

        project.messageBus.connect(this).subscribe(
            GitRepository.GIT_REPO_CHANGE,
            GitRepositoryChangeListener { repo ->
                ApplicationManager.getApplication().invokeLater({ onRepositoryChanged(repo) }, project.disposed)
            }
        )

        prefillFromState()
    }

    override fun dispose() = Unit

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

    private fun setBusy(value: Boolean) {
        busy = value
        scanButton.isEnabled = !value
    }

    /** Returns true (and says so in the status line) if another operation is still running. */
    private fun rejectIfBusy(): Boolean {
        if (busy) setStatus("Another CrossPick operation is still running.")
        return busy
    }

    private fun onScanClicked() {
        if (rejectIfBusy()) return

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

        setBusy(true)
        setStatus("Scanning $url ($branch)…")

        service.scan(url, branch, sha) { result ->
            setBusy(false)
            result.fold(
                onSuccess = { scan ->
                    scannedSourceUrl = url
                    tableModel.items = scan.rows
                    val conflictRow = scan.rows.firstOrNull { it.status == TransferStatus.CONFLICT }
                    activeConflict = conflictRow?.let { ActiveConflict(it, scan.repoRoot, scan.headBeforeInProgressPick, url) }
                    setStatus(
                        "Found ${scan.rows.size} commit(s)." +
                            (conflictRow?.let { " ${it.shortSha} is mid cherry-pick: ${RESOLVE_HINT}" } ?: "")
                    )
                },
                onFailure = { error ->
                    setStatus("Scan failed: ${error.message}")
                }
            )
        }
    }

    private fun onCherryPickClicked(row: CommitRow) {
        if (rejectIfBusy()) return
        if (row.status != TransferStatus.PENDING) return
        activeConflict?.let {
            setStatus("Commit or abort the cherry-pick of ${it.row.shortSha} first.")
            return
        }
        val sourceUrl = scannedSourceUrl ?: return

        row.status = TransferStatus.IN_PROGRESS
        row.message = null
        setBusy(true)
        refreshTable()
        setStatus("Cherry-picking ${row.shortSha}…")

        service.cherryPick(row, sourceUrl) { result ->
            setBusy(false)
            result.fold(
                onSuccess = { outcome ->
                    row.status = outcome.status
                    when (outcome.status) {
                        TransferStatus.TRANSFERRED -> setStatus("Cherry-picked ${row.shortSha}.")
                        TransferStatus.ALREADY_APPLIED ->
                            setStatus("${row.shortSha} is already applied; nothing to transfer.")
                        TransferStatus.CONFLICT -> {
                            activeConflict = ActiveConflict(row, outcome.repoRoot, outcome.headBefore, sourceUrl)
                            setStatus("Cherry-pick of ${row.shortSha} stopped on conflicts: $RESOLVE_HINT")
                            refreshTable()
                            showMergeDialog(outcome.conflictedFiles)
                        }
                        else -> setStatus(" ")
                    }
                },
                onFailure = { error ->
                    row.status = TransferStatus.PENDING
                    row.message = error.message
                    setStatus("Cherry-pick of ${row.shortSha} failed: ${error.message}")
                }
            )
            refreshTable()
        }
    }

    /** Opens the IDE's merge dialog (the same one "Update Project" shows) on [files]. */
    private fun showMergeDialog(files: List<VirtualFile>) {
        if (files.isEmpty()) return
        AbstractVcsHelper.getInstance(project).showMergeDialog(files, GitVcs.getInstance(project).mergeProvider)
    }

    /**
     * Settles [activeConflict] once its repository is no longer mid cherry-pick: if HEAD
     * moved the user committed the resolved pick, otherwise it was aborted.
     */
    private fun onRepositoryChanged(repo: GitRepository) {
        val conflict = activeConflict ?: return
        if (repo.root != conflict.repoRoot) return
        if (repo.state == Repository.State.GRAFTING) return

        activeConflict = null
        val head = repo.currentRevision
        val committed = conflict.headBefore != null && head != null && head != conflict.headBefore
        val row = conflict.row
        row.message = null
        if (committed) {
            service.setRowStatus(row, TransferStatus.TRANSFERRED, conflict.sourceUrl)
            setStatus("Cherry-pick of ${row.shortSha} completed.")
        } else {
            service.setRowStatus(row, TransferStatus.PENDING, conflict.sourceUrl)
            setStatus("Cherry-pick of ${row.shortSha} was aborted.")
        }
        refreshTable()
    }

    private fun onAbortOrSkip(row: CommitRow) {
        if (rejectIfBusy()) return
        val sourceUrl = scannedSourceUrl ?: return

        setBusy(true)
        setStatus("Checking cherry-pick state…")
        // Run the check in a background task to avoid EDT violations
        object : Task.Backgroundable(project, "CrossPick: Checking cherry-pick state", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val repo = service.resolveRepository()
                    val isInProgress = repo != null && GitOps.isCherryPickInProgress(repo)

                    ApplicationManager.getApplication().invokeLater {
                        if (isInProgress) {
                            // A cherry-pick is in progress, abort it. The repository listener
                            // moves the conflicted row back to pending once git is done.
                            setStatus("Aborting cherry-pick…")
                            service.abortInProgressPick { result ->
                                setBusy(false)
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
                            setBusy(false)
                            service.setRowStatus(row, TransferStatus.SKIPPED, sourceUrl)
                            setStatus("Marked ${row.shortSha} as skipped.")
                            refreshTable()
                        }
                    }
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        setBusy(false)
                        setStatus("Error: ${e.message}")
                    }
                }
            }
        }.queue()
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

    /**
     * Applies [status] to every currently-selected row via [CrossPickService.setRowStatus].
     * Rows with a cherry-pick in flight (running or halted on conflicts) are left alone:
     * their status is settled by git, not by hand.
     */
    private fun applyStatusToSelectedRows(status: TransferStatus) {
        val sourceUrl = scannedSourceUrl ?: return
        val rows = selectedRows().filter {
            it.status != TransferStatus.IN_PROGRESS && it.status != TransferStatus.CONFLICT
        }
        if (rows.isEmpty()) return
        for (row in rows) {
            service.setRowStatus(row, status, sourceUrl)
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

    private companion object {
        const val RESOLVE_HINT = "resolve the conflicts, then commit to finish, or click × to abort."
    }
}
