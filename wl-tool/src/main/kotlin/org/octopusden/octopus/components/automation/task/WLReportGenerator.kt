package org.octopusden.octopus.components.automation.task

import org.slf4j.LoggerFactory
import java.io.File

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
                logger.info("Found ${validationResult.fileNameProblems} file items & ${validationResult.fileContentProblems} source items")
                out.println("Version $version")

                if (validationResult.fileNameProblems.isNotEmpty()) {
                    out.println("\n===========File renaming =======================\n")
                    validationResult.fileNameProblems.forEach { old, new ->
                        out.println("Rename ${old} -> ${new}")
                    }
                }
                out.println("\n=========== Content Validation Errors  =======================\n")
                validationResult.fileContentProblems.forEach { file, v ->
                    out.println("\n======== $file ====\n")

                    v.forEach { item ->
                        if (item.brokenRegex.isNotEmpty()) {
                            out.println("line=${item.line} \"${item.validationProblem.trim()}\" mustn't match reqexp: \"${item.brokenRegex}")
                        } else {
                            out.println("$file:${item.line},${item.startPosition} \"${item.problemToken}\" \"${item.validationProblem} ")
                        }
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
    }
}