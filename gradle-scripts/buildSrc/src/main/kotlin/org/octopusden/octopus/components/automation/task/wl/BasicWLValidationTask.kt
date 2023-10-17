package org.octopusden.octopus.components.automation.task.wl

import org.octopusden.octopus.components.automation.task.WLReportGenerator
import org.octopusden.octopus.components.automation.task.WLSourceValidator
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.options.Option
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileWriter
import java.nio.file.Paths

abstract class BasicWLValidationTask : DefaultTask() {

    @get:Input
    @set:Option(option = "source-root", description = "Source root for start validation")
    var sourceRoot = "."

    @get:Input
    @set:Option(option = "configuration-path", description = "Path for mapping configuration")
    var validatorConfig = "./mapping-source.json"

    @get:Input
    @set:Option(option = "file-filter-config", description = "Path for file filter configuration")
    var fileFilterConfig = "./file-filters.json"

    @get:Input
    @set:Option(option = "forbidden-patterns", description = "Path for file filter configuration")
    var forbiddenPatterns = "./wl-forbidden-patterns.json"

    @get:Input
    @set:Option(option = "overwriteDep", description = "Overwrite Dependencies File")
    var overwriteDependenciesValidationResult: Boolean = true

    @get:Input
    @set:Option(option = "overwrite-source-result", description = "Overwrite Source Validation File")
    var overwriteSourceValidationResult: Boolean = true

    fun validateSourceCode(reportDir: File, version: String): Boolean {
        logger.info("Starting validation of ${sourceRoot}")
        val wlSourceValidator = WLSourceValidator(
            Paths.get(sourceRoot),
            Paths.get(validatorConfig),
            Paths.get(fileFilterConfig),
            Paths.get(forbiddenPatterns)
        )

        val validationResult = wlSourceValidator.validate()
        WLReportGenerator().printValidationReport(
            validationResult,
            getSourceValidationErrorsFile(reportDir),
            getSourceValidationSuccessFile(reportDir),
            version
        )
        val fullMappingJson = File(reportDir, "full-mapping.json")
        logger.info("Storing full mapping to ${fullMappingJson.absolutePath}")
        FileWriter(fullMappingJson).use {
            it.write(wlSourceValidator.validationRules.map { "\"${it.rule}\" : \"${it.suggestedReplacement}\"" }
                .joinToString("\n", "[", "]"))
        }
        return validationResult.isEmpty()
    }


    fun getSourceValidationErrorsFile(reportDir: File) =
        File(reportDir, "$SOURCE_VALIDATION_PREFIX-errors.txt")

    fun getSourceValidationSuccessFile(reportDir: File) =
        File(reportDir, "$SOURCE_VALIDATION_PREFIX-success.txt")

    companion object {
        protected val log = LoggerFactory.getLogger(BasicWLValidationTask::class.java)
        const val SOURCE_VALIDATION_PREFIX = "source-validation"
    }


}
