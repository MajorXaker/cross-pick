package com.alexkoleda.crosspick.service

import com.alexkoleda.crosspick.git.GitOps
import com.alexkoleda.crosspick.model.CommitRow
import com.alexkoleda.crosspick.model.TransferStatus
import com.alexkoleda.crosspick.state.CrossPickState
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.vcsUtil.VcsUtil
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager

/** Result of [CrossPickService.scan]. */
class ScanResult(
    val rows: List<CommitRow>,
    /** Root of the target repository the scan ran against. */
    val repoRoot: VirtualFile,
    /**
     * If one of [rows] is halted mid cherry-pick ([TransferStatus.CONFLICT]), the
     * target's HEAD at scan time, i.e. HEAD before that pick. Null otherwise.
     */
    val headBeforeInProgressPick: String?
)

/** Result of a single [CrossPickService.cherryPick] attempt. */
class PickOutcome(
    /** [TransferStatus.TRANSFERRED], [TransferStatus.ALREADY_APPLIED] or [TransferStatus.CONFLICT]. */
    val status: TransferStatus,
    /** Root of the target repository the pick ran in. */
    val repoRoot: VirtualFile,
    /** Target HEAD before the pick ran; used to tell a completed pick from an aborted one. */
    val headBefore: String?,
    /** Files left with unresolved conflicts, refreshed in the VFS. Empty unless [status] is CONFLICT. */
    val conflictedFiles: List<VirtualFile> = emptyList()
)

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
    fun resolveRepository(): GitRepository? {
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
     * on `HEAD`) as [TransferStatus.ALREADY_APPLIED], and the row whose cherry-pick is
     * currently halted in the target (if any) as [TransferStatus.CONFLICT].
     * Invokes [onDone] on the EDT.
     */
    fun scan(url: String, branch: String, initialSha: String, onDone: (Result<ScanResult>) -> Unit) {
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
                            stateService.isTransferred(key, row.fullSha)
                        ) {
                            row.status = TransferStatus.ALREADY_APPLIED
                        } else if (stateService.isSkipped(key, row.fullSha)) {
                            row.status = TransferStatus.SKIPPED
                        }
                    }

                    val inProgressSha = GitOps.cherryPickHeadSha(repo)
                    val inProgressRow = inProgressSha?.let { sha -> rows.firstOrNull { it.fullSha == sha } }
                    inProgressRow?.status = TransferStatus.CONFLICT
                    val headBefore = if (inProgressRow != null) GitOps.getCurrentHeadSha(repo) else null

                    invokeOnDone(onDone, Result.success(ScanResult(rows, repo.root, headBefore)))
                } catch (e: Exception) {
                    invokeOnDone(onDone, Result.failure(e))
                }
            }
        }.queue()
    }

    /**
     * Cherry-picks [row]'s commit into the target repository's current branch using
     * `git cherry-pick -x`, recording the result under [sourceUrl] in [CrossPickState].
     *
     * Everything runs in a background task: the in-progress and clean-tree checks
     * (asking the user via a yes/no dialog whether to proceed when tracked files are
     * modified), the pick itself, and the VCS refresh afterwards. The outcome is
     * classified from the repository state rather than git's output text:
     * - success → [TransferStatus.TRANSFERRED];
     * - halted with unresolved files → [TransferStatus.CONFLICT], with those files
     *   refreshed so the caller can open the merge dialog on them;
     * - halted with nothing to commit (the commit's changes are already present) →
     *   the halted pick is dropped with `git cherry-pick --skip` and the row is
     *   [TransferStatus.ALREADY_APPLIED];
     * - anything else → failure with git's error output.
     *
     * Invokes [onDone] on the EDT.
     */
    fun cherryPick(row: CommitRow, sourceUrl: String, onDone: (Result<PickOutcome>) -> Unit) {
        val repo = resolveRepository()
        if (repo == null) {
            invokeOnDone(onDone, Result.failure(IllegalStateException("No git repository found for this project.")))
            return
        }

        object : Task.Backgroundable(project, "CrossPick: Cherry-picking ${row.shortSha}", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    if (GitOps.isCherryPickInProgress(repo)) {
                        invokeOnDone(
                            onDone,
                            Result.failure(
                                IllegalStateException("Another cherry-pick is in progress. Commit or abort it first.")
                            )
                        )
                        return
                    }

                    if (!GitOps.isCleanWorkingTree(repo) && !confirmDirtyWorkingTree()) {
                        invokeOnDone(onDone, Result.failure(IllegalStateException("Cancelled: working tree is not clean.")))
                        return
                    }

                    val headBefore = GitOps.getCurrentHeadSha(repo)

                    indicator.text = "Cherry-picking ${row.shortSha}…"
                    val result = GitOps.cherryPick(repo, row.fullSha)

                    val outcome: PickOutcome = when {
                        result.success() -> PickOutcome(TransferStatus.TRANSFERRED, repo.root, headBefore)

                        GitOps.isCherryPickInProgress(repo) -> {
                            val conflictedPaths = GitOps.conflictedFiles(repo)
                            if (conflictedPaths.isEmpty() && !GitOps.hasStagedChanges(repo)) {
                                // Empty pick: the changes are already in the target. Drop the
                                // halted pick so the repository doesn't stay mid cherry-pick.
                                val skip = GitOps.cherryPickSkip(repo)
                                if (!skip.success()) {
                                    LOG.warn("git cherry-pick --skip failed: ${skip.errorOutputAsJoinedString}")
                                }
                                PickOutcome(TransferStatus.ALREADY_APPLIED, repo.root, headBefore)
                            } else {
                                PickOutcome(
                                    TransferStatus.CONFLICT,
                                    repo.root,
                                    headBefore,
                                    refreshFiles(repo, conflictedPaths)
                                )
                            }
                        }

                        // Older git versions report an empty pick as "nothing to commit"
                        // without leaving CHERRY_PICK_HEAD behind.
                        result.errorOutputAsJoinedString.contains("nothing to commit", ignoreCase = true) ||
                            result.outputAsJoinedString.contains("nothing to commit", ignoreCase = true) ->
                            PickOutcome(TransferStatus.ALREADY_APPLIED, repo.root, headBefore)

                        else -> throw IllegalStateException(
                            result.errorOutputAsJoinedString.ifBlank { "Cherry-pick failed." }
                        )
                    }

                    if (outcome.status == TransferStatus.TRANSFERRED || outcome.status == TransferStatus.ALREADY_APPLIED) {
                        project.getService(CrossPickState::class.java)
                            .markTransferred(stateKey(sourceUrl, repo), row.fullSha)
                    }

                    if (outcome.status == TransferStatus.TRANSFERRED || outcome.status == TransferStatus.CONFLICT) {
                        refreshVcs(repo)
                    }

                    invokeOnDone(onDone, Result.success(outcome))
                } catch (e: Exception) {
                    // A failed pick can still have touched the working tree.
                    refreshVcs(repo)
                    invokeOnDone(onDone, Result.failure(e))
                }
            }
        }.queue()
    }

    /**
     * Runs `git cherry-pick --abort` against the target repository's in-flight
     * cherry-pick and refreshes VCS state. Reports a friendly error via [onDone] if no
     * cherry-pick is in progress.
     */
    fun abortInProgressPick(onDone: (Result<Unit>) -> Unit) {
        val repo = resolveRepository()
        if (repo == null) {
            invokeOnDone(onDone, Result.failure(IllegalStateException("No git repository found for this project.")))
            return
        }

        object : Task.Backgroundable(project, "CrossPick: Aborting cherry-pick", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    if (!GitOps.isCherryPickInProgress(repo)) {
                        invokeOnDone(
                            onDone,
                            Result.failure(IllegalStateException("No cherry-pick is currently in progress."))
                        )
                        return
                    }

                    val result = GitOps.cherryPickAbort(repo)
                    refreshVcs(repo)
                    if (!result.success()) {
                        invokeOnDone(
                            onDone,
                            Result.failure(IllegalStateException(result.errorOutputAsJoinedString.ifBlank { "Command failed." }))
                        )
                        return
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
     * through to [CrossPickState] under [sourceUrl]: [TransferStatus.SKIPPED] marks the
     * SHA skipped, [TransferStatus.TRANSFERRED] marks it transferred, and any other
     * status (e.g. reverting to [TransferStatus.PENDING]) clears it from both
     * persisted sets.
     */
    fun setRowStatus(row: CommitRow, status: TransferStatus, sourceUrl: String) {
        row.status = status

        val repo = resolveRepository() ?: return
        val stateService = project.getService(CrossPickState::class.java)
        val key = stateKey(sourceUrl, repo)
        when (status) {
            TransferStatus.SKIPPED -> stateService.markSkipped(key, row.fullSha)
            TransferStatus.TRANSFERRED -> stateService.markTransferred(key, row.fullSha)
            else -> stateService.clearRowState(key, row.fullSha)
        }
    }

    /** Asks (on the EDT, blocking the calling background thread) whether to pick onto a dirty tree. */
    private fun confirmDirtyWorkingTree(): Boolean {
        var answer = false
        ApplicationManager.getApplication().invokeAndWait {
            answer = Messages.showYesNoDialog(
                project,
                "The working tree has uncommitted changes. Cherry-pick anyway?",
                "CrossPick",
                "Continue Anyway",
                "Cancel",
                Messages.getWarningIcon()
            ) == Messages.YES
        }
        return answer
    }

    /**
     * Synchronously refreshes the files at repository-relative [paths] so the IDE sees
     * their conflict markers, and returns those that exist.
     */
    private fun refreshFiles(repo: GitRepository, paths: List<String>): List<VirtualFile> {
        val root = repo.root.toNioPath()
        val files = paths.mapNotNull { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve(it)) }
        VfsUtil.markDirtyAndRefresh(false, false, false, *files.toTypedArray())
        return files
    }

    /** Makes the IDE pick up what git just did: changes, conflicts and repository state. */
    private fun refreshVcs(repo: GitRepository) {
        VcsDirtyScopeManager.getInstance(project).markEverythingDirty()
        repo.update()
        VirtualFileManager.getInstance().asyncRefresh(null)
    }

    /** Builds the persisted-state lookup key "sourceUrl|targetRepoRoot" for [repo]. */
    private fun stateKey(url: String, repo: GitRepository): String = "$url|${repo.root.path}"

    private fun <T> invokeOnDone(onDone: (Result<T>) -> Unit, result: Result<T>) {
        ApplicationManager.getApplication().invokeLater {
            onDone(result)
        }
    }

    private companion object {
        val LOG = logger<CrossPickService>()
    }
}
