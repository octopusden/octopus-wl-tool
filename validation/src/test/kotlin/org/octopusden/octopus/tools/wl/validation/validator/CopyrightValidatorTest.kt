package org.octopusden.octopus.tools.wl.validation.validator

import org.octopusden.octopus.components.automation.task.ValidationProblem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.inputStream
import kotlin.io.path.toPath

internal class CopyrightValidatorTest {

    private val copyrightValidator = CopyrightValidator(
        CopyrightValidator.Properties(
            listOf("octopus"),
            listOf(
                "(?i).*(\\(c\\)|copyright).*octopus\\s*den.*",
                "(?i).*octopus\\s*den.*rights.+reserved.*",
                "(?i).*(octopus\\s*den\\s+(ltd|Corp|Utilities)).*",
                "(?i).*(company|vendor(?!-id)|providerName|license(?!-maven-plugin)|author|created|legal|product|trademark).*[^\\./]octopus\\s*den(?!group-parent).*",
                "(?i).*url.*=.*octopus\\s*den.*(\\.org).*",
                ".*OctopusDen.*",
                ".*Implementation-Vendor:\\s.*[Oo]ctopus.*"
            ).map { it.toRegex() }
        )
    )

    @Test
    fun test() {
        val fileName = "with-copyright.txt"
        val results = validateTextFile(fileName)
        assertEquals(getLinesCount(fileName), results.size)
    }

    private fun getLinesCount(fileName: String): Int {
        var lineCount: Long
        Files.lines(fileName2Path(fileName)).use { stream -> lineCount = stream.count() }
        return lineCount.toInt()
    }

    @Test
    fun testNoValidationErrors() {
        val results = validateTextFile("allowed.txt")
        assertEquals(0, results.size, "Unexpected errors: $results")
    }

    @Test
    fun testDocWithLongStrings() {
        val results = validateTextFile("long-string.doc")
        assertEquals(1, results.size)
    }

    @Test
    fun test2() {
        val results = CopyrightValidatorTest::class.java.getResource("/copyright2.txt")
            .openStream()
            .use { inputStream -> copyrightValidator.validate(inputStream) }
        assertEquals(99, results.size)
    }

    private fun validateTextFile(file: String): List<ValidationProblem> {
        val results = fileName2Path(file)
            .inputStream()
            .use { inputStream -> copyrightValidator.validate(inputStream) }
        println(results)
        return results
    }

    private fun fileName2Path(file: String) =
        CopyrightValidatorTest::class.java.getResource("/$file").toURI().toPath()
}
