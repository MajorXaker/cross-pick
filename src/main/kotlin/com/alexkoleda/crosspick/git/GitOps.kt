package com.alexkoleda.crosspick.git

import com.alexkoleda.crosspick.model.CommitRow
import com.intellij.openapi.diagnostic.logger
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitCommandResult
import git4idea.commands.GitLineHandler
import git4idea.commands.GitUntrackedFilesOverwrittenByOperationDetector
import git4idea.repo.GitRepository
import java.security.MessageDigest

/**
 * Thin, version-stable wrappers around raw `git` invocations executed through
 * [Git.getInstance] and [GitLineHandler]. Deliberately avoids internal/experimental
 * git4idea APIs (e.g. `GitLogUtil`, `GitCherryPicker`, `GitConflictResolver`) in favor
 * of parsing plain `git` command output, which stays stable across IDE versions.
 */
object GitOps {

    private val LOG = logger<GitOps>()

    private const val UNIT_SEP = "\u001f"
    private val CHERRY_PICK_REGEX =
        Regex("""\(cherry picked from commit ([0-9a-fA-F]{7,40})\)""")

    /**
     * Derives a stable, filesystem/remote-name-safe identifier for [url], of the form
     * "crosspick-&lt;12 hex chars&gt;" so repeated scans of the same source URL reuse the
     * same git remote name.
     */
    fun remoteNameFor(url: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(url.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "crosspick-" + hex.take(12)
    }

    /**
     * Ensures [repo] has a remote named [name] pointing at [url]. Adds it if missing,
     * updates the URL via `remote set-url` if it differs, or does nothing if already
     * correctly configured.
     */
    fun ensureRemote(repo: GitRepository, name: String, url: String) {
        val existing = repo.remotes.firstOrNull { it.name == name }
        if (existing == null) {
            val handler = GitLineHandler(repo.project, repo.root, GitCommand.REMOTE)
            handler.addParameters("add", name, url)
            Git.getInstance().runCommand(handler).throwOnError()
            return
        }
        val currentUrl = existing.firstUrl
        if (currentUrl != url) {
            val handler = GitLineHandler(repo.project, repo.root, GitCommand.REMOTE)
            handler.addParameters("set-url", name, url)
            Git.getInstance().runCommand(handler).throwOnError()
        }
    }

    /**
     * Fetches [branch] from remote [remoteName] into `repo`, without tags.
     */
    fun fetchBranch(repo: GitRepository, remoteName: String, branch: String): GitCommandResult {
        val handler = GitLineHandler(repo.project, repo.root, GitCommand.FETCH)
        handler.addParameters(remoteName, branch, "--no-tags")
        return Git.getInstance().runCommand(handler)
    }

    /**
     * Returns true if [sha] resolves to a commit object reachable in [repo]'s object
     * database, using `git cat-file -e &lt;sha&gt;^{commit}`.
     */
    fun commitExists(repo: GitRepository, sha: String): Boolean {
        val handler = GitLineHandler(repo.project, repo.root, GitCommand.CAT_FILE)
        handler.addParameters("-e", "$sha^{commit}")
        return Git.getInstance().runCommand(handler).success()
    }

    /**
     * Returns true if [sha] is an ancestor of [ref] (i.e. already contained in the
     * history of [ref]), using `git merge-base --is-ancestor`.
     */
    fun isAncestor(repo: GitRepository, sha: String, ref: String): Boolean {
        val handler = GitLineHandler(repo.project, repo.root, GitCommand.MERGE_BASE)
        handler.addParameters("--is-ancestor", sha, ref)
        return Git.getInstance().runCommand(handler).success()
    }

    /**
     * Lists all non-merge commits in `(initialSha, remoteRef]`, oldest first, by running
     * `git log --no-merges --reverse --date-order --pretty=format:...` and parsing the
     * unit-separator-delimited output. Returns an empty list on failure.
     */
    fun listCommits(repo: GitRepository, initialSha: String, remoteRef: String): List<CommitRow> {
        val format = "%H$UNIT_SEP%an$UNIT_SEP%ae$UNIT_SEP%ct$UNIT_SEP%s"
        val handler = GitLineHandler(repo.project, repo.root, GitCommand.LOG)
        handler.addParameters(
            "--no-merges",
            "--reverse",
            "--date-order",
            "--pretty=format:$format",
            "$initialSha..$remoteRef"
        )
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) {
            LOG.warn("git log failed: ${result.errorOutputAsJoinedString}")
            return emptyList()
        }
        val rows = mutableListOf<CommitRow>()
        for (line in result.output) {
            if (line.isBlank()) continue
            val parts = line.split(UNIT_SEP)
            if (parts.size < 5) continue
            val fullSha = parts[0]
            val authorName = parts[1]
            val authorEmail = parts[2]
            val epochSeconds = parts[3].toLongOrNull() ?: 0L
            val subject = parts.subList(4, parts.size).joinToString(UNIT_SEP)
            rows.add(
                CommitRow(
                    fullSha = fullSha,
                    shortSha = fullSha.take(8),
                    authorName = authorName,
                    authorEmail = authorEmail,
                    commitTimeMillis = epochSeconds * 1000L,
                    subject = subject
                )
            )
        }
        return rows
    }

    /**
     * Scans the current `HEAD` history of [repo] for commits carrying a
     * "cherry picked from commit &lt;sha&gt;" trailer (as added by `git cherry-pick -x`)
     * and returns the set of referenced source SHAs. [targetBranchRange] is passed
     * verbatim as the revision range argument to `git log`, e.g. "HEAD" or
     * "origin/main..HEAD".
     */
    fun findAlreadyPicked(repo: GitRepository, targetBranchRange: String): Set<String> {
        val handler = GitLineHandler(repo.project, repo.root, GitCommand.LOG)
        handler.addParameters("--grep=cherry picked from commit", "--pretty=format:%B$UNIT_SEP", targetBranchRange)
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) {
            LOG.warn("git log --grep failed: ${result.errorOutputAsJoinedString}")
            return emptySet()
        }
        val joined = result.output.joinToString("\n")
        val shas = mutableSetOf<String>()
        for (match in CHERRY_PICK_REGEX.findAll(joined)) {
            shas.add(match.groupValues[1])
        }
        return shas
    }

    /**
     * Runs `git cherry-pick -x &lt;sha&gt;` against [repo], attaching an untracked-files
     * detector when possible so callers can distinguish that failure mode if needed.
     */
    fun cherryPick(repo: GitRepository, sha: String): GitCommandResult {
        val handler = GitLineHandler(repo.project, repo.root, GitCommand.CHERRY_PICK)
        handler.addParameters("-x", sha)
        val detector = GitUntrackedFilesOverwrittenByOperationDetector(repo.root)
        handler.addLineListener(detector)
        return Git.getInstance().runCommand(handler)
    }

    /** Runs `git cherry-pick --continue`. */
    fun cherryPickContinue(repo: GitRepository): GitCommandResult {
        val handler = GitLineHandler(repo.project, repo.root, GitCommand.CHERRY_PICK)
        handler.addParameters("--continue")
        return Git.getInstance().runCommand(handler)
    }

    /** Runs `git cherry-pick --abort`. */
    fun cherryPickAbort(repo: GitRepository): GitCommandResult {
        val handler = GitLineHandler(repo.project, repo.root, GitCommand.CHERRY_PICK)
        handler.addParameters("--abort")
        return Git.getInstance().runCommand(handler)
    }

    /** Runs `git cherry-pick --skip`. */
    fun cherryPickSkip(repo: GitRepository): GitCommandResult {
        val handler = GitLineHandler(repo.project, repo.root, GitCommand.CHERRY_PICK)
        handler.addParameters("--skip")
        return Git.getInstance().runCommand(handler)
    }

    /** Runs `git cherry-pick --quit`. */
    fun cherryPickQuit(repo: GitRepository): GitCommandResult {
        val handler = GitLineHandler(repo.project, repo.root, GitCommand.CHERRY_PICK)
        handler.addParameters("--quit")
        return Git.getInstance().runCommand(handler)
    }

    /**
     * Returns true if a cherry-pick is currently in progress in [repo], i.e.
     * `CHERRY_PICK_HEAD` exists, checked via
     * `git rev-parse --verify --quiet CHERRY_PICK_HEAD`.
     */
    fun isCherryPickInProgress(repo: GitRepository): Boolean {
        val handler = GitLineHandler(repo.project, repo.root, GitCommand.REV_PARSE)
        handler.addParameters("--verify", "--quiet", "CHERRY_PICK_HEAD")
        val result = Git.getInstance().runCommand(handler)
        return result.success() && result.outputAsJoinedString.isNotBlank()
    }

    /**
     * Returns true if `git status --porcelain` reports no pending changes in [repo].
     */
    fun isCleanWorkingTree(repo: GitRepository): Boolean {
        val handler = GitLineHandler(repo.project, repo.root, GitCommand.STATUS)
        handler.addParameters("--porcelain")
        val result = Git.getInstance().runCommand(handler)
        return result.success() && result.output.none { it.isNotBlank() }
    }

    /**
     * Returns the full SHA of the current HEAD commit, or null if the operation fails.
     */
    fun getCurrentHeadSha(repo: GitRepository): String? {
        val handler = GitLineHandler(repo.project, repo.root, GitCommand.REV_PARSE)
        handler.addParameters("HEAD")
        val result = Git.getInstance().runCommand(handler)
        return if (result.success()) result.outputAsJoinedString.trim() else null
    }
}
