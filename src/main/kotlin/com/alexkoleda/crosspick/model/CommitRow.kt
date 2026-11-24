package com.alexkoleda.crosspick.model

/**
 * Transfer lifecycle of a single [CommitRow] as it moves through the CrossPick UI.
 */
enum class TransferStatus {
    /** Not yet cherry-picked into the target repository. */
    PENDING,

    /** A cherry-pick operation is currently running for this commit. */
    IN_PROGRESS,

    /** Successfully cherry-picked into the target repository. */
    TRANSFERRED,

    /** The commit's changes were already present in the target repository. */
    ALREADY_APPLIED,

    /**
     * The cherry-pick halted on conflicts and is waiting for the user to resolve them
     * and commit (or abort). Resolves to [TRANSFERRED] or back to [PENDING] once the
     * target repository leaves the cherry-picking state.
     */
    CONFLICT,

    /** Deliberately skipped by the user; will not be transferred. */
    SKIPPED
}

/**
 * Represents a single commit discovered in the source repository, along with its
 * current transfer state relative to the target (currently open) repository.
 *
 * Instances are mutable with respect to [status] and [message] so the
 * table model backing the UI can update rows in place as background work completes.
 */
data class CommitRow(
    val fullSha: String,
    val shortSha: String,
    val authorName: String,
    val authorEmail: String,
    val commitTimeMillis: Long,
    val subject: String,
    var status: TransferStatus = TransferStatus.PENDING,
    var message: String? = null
)
