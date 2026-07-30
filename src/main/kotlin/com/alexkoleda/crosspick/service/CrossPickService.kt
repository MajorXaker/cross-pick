package com.alexkoleda.crosspick.service

import com.alexkoleda.crosspick.export.CsvExporter
import com.alexkoleda.crosspick.git.GitOps
import com.alexkoleda.crosspick.model.CommitRow
import com.alexkoleda.crosspick.model.TransferStatus
import com.alexkoleda.crosspick.state.CrossPickState
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.vcsUtil.VcsUtil
import git4idea.commands.GitCommandResult
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager

/**
 * Project-level service coordinating CrossPick's git operations. Owns the resolution
 * of the "target" [GitRepository] (the repository backing the currently open project)
 * and runs all git work off the EDT inside [Task.Backgroundable], marshalling results
 * back to callers on the EDT.
 *
 * This service never pushes anything; cherry-picked commits remain local until the
 * user pushes manually.
 */
@Service(Service.Level.PROJECT)
class CrossPickService(private val project: Project) {

    /** Resolves the git repository backing the currently open project, if any. */
    private fun resolveRepository(): GitRepository? {
        val manager = GitRepositoryManager.getInstance(project)
        val repositories = manager.repositories
        if (repositories.isEmpty()) return null
        if (repositories.size == 1) return repositories.first()
        val basePath = project.basePath ?: return repositories.first()
        val projectDir: VirtualFile = LocalFileSystem.getInstance().findFileByPath(basePath) ?: return repositories.first()
        val rootFile: VirtualFile? = VcsUtil.getVcsRootFor(project, projectDir)
        return repositories.firstOrNull { it.root == rootFile } ?: repositories.first()
    }

    /**
     * Fetches [url]/[branch] into a dedicated CrossPick remote, then lists every
     * non-merge commit in `(initialSha, remoteRef]`, tagging rows that were already
     * transferred (per [CrossPickState] or a `cherry picked from commit` trailer found
     * on `HEAD`) as [TransferStatus.ALREADY_APPLIED]. Invokes [onDone] on the EDT.
     */
    fun scan(url: String, branch: String, initialSha: String, onDone: (Result<List<CommitRow>>) -> Unit) {
        val repo = resolveRepository()
        if (repo == null) {
            invokeOnDone(onDone, Result.failure(IllegalStateException("No git repository found for this project.")))
            return
        }

        object : Task.Backgroundable(project, "CrossPick: Scanning source repository", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = "Setting up remote…"
                    val remoteName = GitOps.remoteNameFor(url)
                    GitOps.ensureRemote(repo, remoteName, url)

                    indicator.text = "Fetching $branch…"
                    val fetchResult = GitOps.fetchBranch(repo, remoteName, branch)
                    if (!fetchResult.success()) {
                        invokeOnDone(
                            onDone,
                            Result.failure(IllegalStateException("Fetch failed: ${fetchResult.errorOutputAsJoinedString}"))
                        )
                        return
                    }

                    indicator.text = "Verifying commit…"
                    if (!GitOps.commitExists(repo, initialSha)) {
                        invokeOnDone(
                            onDone,
                            Result.failure(IllegalStateException("Initial commit SHA '$initialSha' was not found after fetch."))
                        )
                        return
                    }

                    val remoteRef = "$remoteName/$branch"
                    if (!GitOps.isAncestor(repo, initialSha, remoteRef)) {
                        invokeOnDone(
                            onDone,
                            Result.failure(
                                IllegalStateException(
                                    "Initial commit SHA '$initialSha' is not an ancestor of $remoteRef."
                                )
                            )
                        )
                        return
                    }

                    indicator.text = "Listing commits…"
                    val rows = GitOps.listCommits(repo, initialSha, remoteRef)

                    indicator.text = "Checking for already-applied commits…"
                    val alreadyPickedTrailers = GitOps.findAlreadyPicked(repo, "HEAD")
                    val stateService = project.getService(CrossPickState::class.java)
                    val key = stateKey(url, repo)
                    for (row in rows) {
                        if (alreadyPickedTrailers.contains(row.fullSha) ||
                            alreadyPickedTrailers.contains(row.shortSha) ||
                            stateService.isTransferred(key, row.fullSha)
                        ) {
                            row.status = TransferStatus.ALREADY_APPLIED
                        } else if (stateService.isSkipped(key, row.fullSha)) {
                            row.status = TransferStatus.SKIPPED
                        }
                    }

                    stateService.state.lastSourceUrl = url
                    stateService.state.lastSourceBranch = branch
                    stateService.state.lastInitialSha = initialSha

                    invokeOnDone(onDone, Result.success(rows))
                } catch (e: Exception) {
                    invokeOnDone(onDone, Result.failure(e))
                }
            }
        }.queue()
    }

    /**
     * Cherry-picks [row]'s commit into the target repository's current branch using
     * `git cherry-pick -x`. Confirms the working tree is clean first (offering to
     * proceed anyway via a yes/no dialog if not), refreshes VCS state after a
     * successful pick, and classifies "nothing to commit" / already-empty outputs as
     * [TransferStatus.ALREADY_APPLIED] and conflicts as [TransferStatus.FAILED].
     * Invokes [onDone] on the EDT.
     */
    fun cherryPick(row: CommitRow, onDone: (Result<TransferStatus>) -> Unit) {
        val repo = resolveRepository()
        if (repo == null) {
            invokeOnDone(onDone, Result.failure(IllegalStateException("No git repository found for this project.")))
            return
        }

        if (!GitOps.isCleanWorkingTree(repo)) {
            val proceed = ApplicationManager.getApplication().let { app ->
                var answer = false
                app.invokeAndWait {
                    answer = Messages.showYesNoDialog(
                        project,
                        "The working tree has uncommitted changes. Cherry-pick anyway?",
                        "CrossPick",
                        "Continue Anyway",
                        "Cancel",
                        Messages.getWarningIcon()
                    ) == Messages.YES
                }
                answer
            }
            if (!proceed) {
                invokeOnDone(onDone, Result.failure(IllegalStateException("Cancelled: working tree is not clean.")))
                return
            }
        }

        object : Task.Backgroundable(project, "CrossPick: Cherry-picking ${row.shortSha}", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = "Cherry-picking ${row.shortSha}…"
                    val result = GitOps.cherryPick(repo, row.fullSha)
                    val combinedOutput = (result.output + result.errorOutput).joinToString("\n").lowercase()

                    val status: TransferStatus = when {
                        result.success() -> TransferStatus.TRANSFERRED

                        combinedOutput.contains("nothing to commit") ||
                            combinedOutput.contains("previous cherry-pick is now empty") ||
                            combinedOutput.contains("the previous cherry-pick is now empty") -> TransferStatus.ALREADY_APPLIED

                        combinedOutput.contains("conflict") ||
                            combinedOutput.contains("after resolving the conflicts") -> TransferStatus.FAILED

                        else -> TransferStatus.FAILED
                    }

                    if (status == TransferStatus.FAILED &&
                        (combinedOutput.contains("conflict") || combinedOutput.contains("after resolving the conflicts"))
                    ) {
                        row.message = "Cherry-pick conflict. Resolve the conflicts in the IDE, then press " +
                            "\"Continue current\"."
                    } else if (status == TransferStatus.ALREADY_APPLIED &&
                        (combinedOutput.contains("previous cherry-pick is now empty") ||
                            combinedOutput.contains("nothing to commit"))
                    ) {
                        row.message = "This commit is already applied. Use 'Skip current' to drop it and " +
                            "clear the cherry-pick state."
                    } else if (status == TransferStatus.FAILED) {
                        row.message = result.errorOutputAsJoinedString.ifBlank { "Cherry-pick failed." }
                    }

                    if (status == TransferStatus.TRANSFERRED || status == TransferStatus.ALREADY_APPLIED) {
                        val stateService = project.getService(CrossPickState::class.java)
                        stateService.markTransferred(stateKey(stateService.state.lastSourceUrl, repo), row.fullSha)

                        // Capture the target SHA if the cherry-pick was successful
                        if (status == TransferStatus.TRANSFERRED) {
                            row.targetSha = GitOps.getCurrentHeadSha(repo)
                        }
                    }

                    if (status == TransferStatus.TRANSFERRED) {
                        VcsDirtyScopeManager.getInstance(project).markEverythingDirty()
                        repo.update()
                        VirtualFileManager.getInstance().asyncRefresh(null)
                    }

                    invokeOnDone(onDone, Result.success(status))
                } catch (e: Exception) {
                    invokeOnDone(onDone, Result.failure(e))
                }
            }
        }.queue()
    }

    /**
     * Runs `git cherry-pick --continue` against the target repository's in-flight
     * cherry-pick, refreshing VCS state on success. Only ever acts on the single
     * currently-halted commit — there is no multi-commit sequence to advance through.
     * Reports a friendly error via [onDone] if no cherry-pick is in progress.
     */
    fun continueInProgressPick(onDone: (Result<Unit>) -> Unit) {
        runGuardedCherryPickAction("Continuing cherry-pick", GitOps::cherryPickContinue, refreshOnSuccess = true, onDone)
    }

    /**
     * Runs `git cherry-pick --abort` against the target repository's in-flight
     * cherry-pick. Reports a friendly error via [onDone] if no cherry-pick is in
     * progress.
     */
    fun abortInProgressPick(onDone: (Result<Unit>) -> Unit) {
        runGuardedCherryPickAction("Aborting cherry-pick", GitOps::cherryPickAbort, refreshOnSuccess = false, onDone)
    }

    /**
     * Runs `git cherry-pick --skip` against the target repository's in-flight
     * cherry-pick (used when the pick turned out empty and there's nothing to
     * continue), refreshing VCS state on success. Reports a friendly error via
     * [onDone] if no cherry-pick is in progress.
     */
    fun skipInProgressPick(onDone: (Result<Unit>) -> Unit) {
        runGuardedCherryPickAction("Skipping cherry-pick", GitOps::cherryPickSkip, refreshOnSuccess = true, onDone)
    }

    /**
     * Shared implementation backing [continueInProgressPick], [abortInProgressPick],
     * and [skipInProgressPick]: resolves the target repository, guards on
     * [GitOps.isCherryPickInProgress], runs [action] in a background task, and
     * optionally refreshes VCS state on success — mirroring the refresh performed
     * after a successful [cherryPick].
     */
    private fun runGuardedCherryPickAction(
        taskTitle: String,
        action: (GitRepository) -> GitCommandResult,
        refreshOnSuccess: Boolean,
        onDone: (Result<Unit>) -> Unit
    ) {
        val repo = resolveRepository()
        if (repo == null) {
            invokeOnDone(onDone, Result.failure(IllegalStateException("No git repository found for this project.")))
            return
        }

        object : Task.Backgroundable(project, "CrossPick: $taskTitle", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    if (!GitOps.isCherryPickInProgress(repo)) {
                        invokeOnDone(
                            onDone,
                            Result.failure(IllegalStateException("No cherry-pick is currently in progress."))
                        )
                        return
                    }

                    val result = action(repo)
                    if (!result.success()) {
                        invokeOnDone(
                            onDone,
                            Result.failure(IllegalStateException(result.errorOutputAsJoinedString.ifBlank { "Command failed." }))
                        )
                        return
                    }

                    if (refreshOnSuccess) {
                        VcsDirtyScopeManager.getInstance(project).markEverythingDirty()
                        repo.update()
                        VirtualFileManager.getInstance().asyncRefresh(null)
                    }

                    invokeOnDone(onDone, Result.success(Unit))
                } catch (e: Exception) {
                    invokeOnDone(onDone, Result.failure(e))
                }
            }
        }.queue()
    }

    /**
     * Updates [row]'s in-memory [CommitRow.status] and writes the corresponding change
     * through to [CrossPickState]: [TransferStatus.SKIPPED] marks the SHA skipped,
     * [TransferStatus.TRANSFERRED] marks it transferred, and any other status (e.g.
     * reverting to [TransferStatus.PENDING]) clears it from both persisted sets.
     */
    fun setRowStatus(row: CommitRow, status: TransferStatus) {
        row.status = status

        val repo = resolveRepository() ?: return
        val stateService = project.getService(CrossPickState::class.java)
        val key = stateKey(stateService.state.lastSourceUrl, repo)
        when (status) {
            TransferStatus.SKIPPED -> stateService.markSkipped(key, row.fullSha)
            TransferStatus.TRANSFERRED -> stateService.markTransferred(key, row.fullSha)
            else -> stateService.clearRowState(key, row.fullSha)
        }
    }

    /**
     * Exports the given [rows] to a CSV file at [outputPath]. Runs in a background task.
     */
    fun exportToCSV(rows: List<CommitRow>, outputPath: String, onDone: (Result<String>) -> Unit) {
        object : Task.Backgroundable(project, "CrossPick: Exporting to CSV", false) {
            override fun run(indicator: ProgressIndicator) {
                val result = CsvExporter.export(rows, outputPath).map { outputPath }
                invokeOnDone(onDone, result)
            }
        }.queue()
    }

    /** Builds the persisted-state lookup key "sourceUrl|targetRepoRoot" for [repo]. */
    private fun stateKey(url: String, repo: GitRepository): String = "$url|${repo.root.path}"

    private fun <T> invokeOnDone(onDone: (Result<T>) -> Unit, result: Result<T>) {
        ApplicationManager.getApplication().invokeLater {
            onDone(result)
        }
    }
}
