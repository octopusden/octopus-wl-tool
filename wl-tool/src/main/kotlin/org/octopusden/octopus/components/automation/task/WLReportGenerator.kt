package org.octopusden.octopus.components.automation.task

import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path

public class WLReportGenerator {

    fun printValidationReport(
        validationResult: ProjectValidationResult,
        errorsReportFile: File,
        successReportFile: File,
        skippedReportFile: File,
        version: String,
    ) {
        printSkippedReport(validationResult, skippedReportFile, version)
        if (validationResult.isNotEmpty()) {
            printErrorsReport(validationResult, errorsReportFile, version)
        } else {
            successReportFile.printWriter().use { out ->
                out.println("Version $version")
            }
        }
    }

    private fun printErrorsReport(validationResult: ProjectValidationResult, errorsReportFile: File, version: String) {
        logger.info("Publishing report to $errorsReportFile")
        logger.info(
            "Found ${validationResult.fileNameProblems.size} file items & ${validationResult.fileContentProblems.size} source items",
        )
        errorsReportFile.printWriter().use { out ->
            out.println("Version $version")

            if (validationResult.fileNameProblems.isNotEmpty()) {
                out.println("\n===========File renaming =======================\n")
                validationResult.fileNameProblems.forEach { old, new ->
                    out.println("Rename $old -> $new")
                }
            }
            out.println("\n=========== Content Validation Errors  =======================\n")
            validationResult.fileContentProblems.forEach { file, problems ->
                val reportedFile = file.forReport()
                out.println("\n======== $reportedFile ====\n")

                problems.forEach { item ->
                    out.println(record(reportedFile, item))
                }
            }
        }
    }

    /**
     * Written always, including when nothing was skipped: an absent file cannot be told apart from
     * "everything was scanned", and that is exactly the distinction this report exists for.
     */
    private fun printSkippedReport(validationResult: ProjectValidationResult, skippedReportFile: File, version: String) {
        logger.info("Publishing skipped files report to $skippedReportFile")
        skippedReportFile.printWriter().use { out ->
            out.println("Version $version")
            validationResult.skippedFilesAndFolders.forEach { file ->
                out.println("${file.forReport()}: excluded by filter")
            }
            validationResult.unscannedFiles.forEach { (file, reason) ->
                out.println("${file.forReport()}: $reason")
            }
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(WLReportGenerator::class.java)

        private const val MAX_RULE_LENGTH = 60
        private const val ELLIPSIS = "..."
        private val UNREADABLE = Regex("[\\p{Cntrl}\\uFFFD\"]+")

        internal fun record(file: String, item: ValidationProblem): String {
            // a binary finding has no line to point at, the byte offset is its only locator
            val location = if (item.byteOffset >= 0) {
                "offset=${item.byteOffset}"
            } else {
                "${item.line},${item.startPosition}"
            }
            val rule = item.brokenRegex.ifEmpty { item.validationProblem }
            val found = item.context.ifEmpty { item.problemToken }
            return "$file:$location \"${found.readable(MAX_EXCERPT_LENGTH)}\" mustn't match rule: \"${rule.readable(MAX_RULE_LENGTH)}\""
        }

        private fun String.readable(limit: Int): String {
            val cleaned = replace(UNREADABLE, " ").trim()
            return if (cleaned.length <= limit) cleaned else cleaned.take(limit - ELLIPSIS.length) + ELLIPSIS
        }

        private fun Path.forReport() = toString().replace('\\', '/')
    }
}
