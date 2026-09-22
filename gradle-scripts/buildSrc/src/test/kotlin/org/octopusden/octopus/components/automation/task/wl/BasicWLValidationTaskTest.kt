package org.octopusden.octopus.components.automation.task.wl

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.octopusden.octopus.components.automation.task.WLValidatorTask
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

internal class BasicWLValidationTaskTest {

    private fun Path.write(content: String) {
        Files.write(this, content.toByteArray())
    }

    @Test
    fun `validateSourceCode writes the skipped report alongside errors and success`(@TempDir tempDir: Path) {
        val sourceRoot = Files.createDirectories(tempDir.resolve("source"))

        val validatorConfig = tempDir.resolve("mapping-source.json").also { it.write("[]") }
        val fileFilterConfig = tempDir.resolve("file-filters.json").also {
            it.write(
                """{"includeDirs":["**"],"excludeDirs":[],"includeFiles":["*"],"excludeFiles":[],"excludeFileContentFilters":[]}""",
            )
        }
        val forbiddenPatterns = tempDir.resolve("wl-forbidden-patterns.json").also {
            it.write("""{"contains":[],"patterns":[],"exceptions":[],"restricted":"no-such-token"}""")
        }
        val reportDir = Files.createDirectories(tempDir.resolve("report")).toFile()

        val project = ProjectBuilder.builder().build()
        val task = project.tasks.create("wlValidate", WLValidatorTask::class.java)
        task.sourceRoot = sourceRoot.toString()
        task.validatorConfig = validatorConfig.toString()
        task.fileFilterConfig = fileFilterConfig.toString()
        task.forbiddenPatterns = forbiddenPatterns.toString()

        task.validateSourceCode(reportDir, "1.2.3")

        assertTrue(
            File(reportDir, "source-validation-skipped.txt").exists(),
            "the skipped report must be written, not just errors/success",
        )
    }
}
