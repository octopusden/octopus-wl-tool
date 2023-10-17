package org.octopusden.octopus.components.automation.task

import org.octopusden.octopus.components.automation.task.wl.BasicWLValidationTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import java.io.File

open class WLValidatorTask : BasicWLValidationTask() {

    @get:Input
    @set:Option(option = "publish-path", description = "path to store metadata")
    var publishPath = ""

    @get:Input
    @set:Option(option = "revision", description = "Revision to validate")
    var revision = ""

    @TaskAction
    fun validate() {
        val reportDir = File(publishPath)
        reportDir.mkdirs()
        val validationSuccessful = validateSourceCode(reportDir, revision)
        if (!validationSuccessful) {
            logger.info("found validation problems")
        }
    }
}
