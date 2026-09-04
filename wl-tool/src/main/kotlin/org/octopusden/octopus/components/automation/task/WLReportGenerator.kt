package org.octopusden.octopus.components.automation.task

import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path

public class WLReportGenerator() {

    fun printValidationReport(
        validationResult: ProjectValidationResult,
        errorsReportFile: File,
        successReportFile: File,
        version: String
    ) {
        if (validationResult.isNotEmpty()) {
            logger.info("Publishing report to $errorsReportFile")
            errorsReportFile.printWriter().use { out ->
                logger.info("Found ${validationResult.fileNameProblems.size} file items & ${validationResult.fileContentProblems.size} source items")
                out.println("Version $version")

                if (validationResult.fileNameProblems.isNotEmpty()) {
                    out.println("\n===========File renaming =======================\n")
                    validationResult.fileNameProblems.forEach { old, new ->
                        out.println("Rename ${old} -> ${new}")
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
        } else {
            successReportFile.printWriter().use { out ->
                out.println("Version $version")
            }
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(WLReportGenerator::class.java)

        private const val MAX_TOKEN_LENGTH = 80
        private const val MAX_RULE_LENGTH = 60
        private const val ELLIPSIS = "..."
        private val UNREADABLE = Regex("[\\p{Cntrl}\\uFFFD\"]+")

        internal fun record(file: String, item: ValidationProblem): String {
            val location = if (item.binary) "offset=${item.byteOffset}" else "${item.line},${item.startPosition}"
            val rule = item.brokenRegex.ifEmpty { item.validationProblem }
            val found = item.context.ifEmpty { item.problemToken }
            return "$file:$location \"${excerpt(found, rule)}\" mustn't match rule: \"${rule.readable(MAX_RULE_LENGTH)}\""
        }

        /**
         * Keeps the matched literal visible: a token can be the whole content of a binary run, and cutting
         * it from the start is exactly what hid the real finding in the report.
         */
        private fun excerpt(token: String, rule: String): String {
            val readable = token.readable(Int.MAX_VALUE)
            if (readable.length <= MAX_TOKEN_LENGTH) {
                return readable
            }
            val hit = readable.indexOf(rule, ignoreCase = true)
            if (hit < 0) {
                return readable.readable(MAX_TOKEN_LENGTH)
            }
            val start = (hit - (MAX_TOKEN_LENGTH - rule.length.coerceAtMost(MAX_TOKEN_LENGTH)) / 2)
                .coerceIn(0, readable.length - MAX_TOKEN_LENGTH)
            val end = (start + MAX_TOKEN_LENGTH).coerceAtMost(readable.length)
            return (if (start > 0) ELLIPSIS else "") + readable.substring(start, end) + (if (end < readable.length) ELLIPSIS else "")
        }

        private fun String.readable(limit: Int): String {
            val cleaned = replace(UNREADABLE, " ").trim()
            return if (cleaned.length <= limit) cleaned else cleaned.take(limit - ELLIPSIS.length) + ELLIPSIS
        }

        private fun Path.forReport() = toString().replace('\\', '/')
    }
}
