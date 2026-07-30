package com.alexkoleda.crosspick.export

import com.alexkoleda.crosspick.model.CommitRow
import com.alexkoleda.crosspick.model.TransferStatus
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Exports CrossPick sync results to CSV format.
 */
object CsvExporter {
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    /**
     * Exports the given [rows] to a CSV file at [outputPath].
     * Returns true if successful, false otherwise.
     */
    fun export(rows: List<CommitRow>, outputPath: String): Result<Unit> = runCatching {
        val file = File(outputPath)
        file.bufferedWriter().use { writer ->
            // Write CSV header
            writer.write("Source Commit SHA,Target Commit SHA,Status,Source Date,Source Author,Source Message\n")

            // Write data rows
            for (row in rows) {
                val targetSha = row.targetSha?.toCsvField() ?: ""
                val status = row.status.toCsvStatus()
                val date = dateFormatter.format(Instant.ofEpochMilli(row.commitTimeMillis))
                val author = row.authorName.toCsvField()
                val message = row.subject.toCsvField()

                writer.write(
                    "${row.fullSha.toCsvField()}," +
                    "$targetSha," +
                    "$status," +
                    "$date," +
                    "$author," +
                    "$message\n"
                )
            }
        }
    }

    private fun String.toCsvField(): String {
        // Escape quotes and wrap in quotes if contains comma, quote, or newline
        return if (contains(",") || contains("\"") || contains("\n")) {
            "\"${replace("\"", "\"\"")}\""
        } else {
            this
        }
    }

    private fun TransferStatus.toCsvStatus(): String = when (this) {
        TransferStatus.PENDING -> "Pending"
        TransferStatus.IN_PROGRESS -> "In Progress"
        TransferStatus.TRANSFERRED -> "Transferred"
        TransferStatus.ALREADY_APPLIED -> "Already Applied"
        TransferStatus.FAILED -> "Failed"
        TransferStatus.SKIPPED -> "Skipped"
    }
}
