package org.octopusden.octopus.components.automation.task

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectories
import kotlin.io.path.toPath
import kotlin.io.path.writeBytes

private const val ORIGIN_LOWERCASE = "brand2all"

private const val REPLACEMENT = "bipolar-disorder"

internal class WLSourceValidatorTest {

    private val filterConfig = getResourceAsPath("/wl-config/file-filters.json")
    private val mappingConfig = getResourceAsPath("/wl-config/mapping-source.json")
    private val testProject = getResourceAsPath("/test-project")
    private val forbiddenPatterns = getResourceAsPath("/wl-config/wl-forbidden-patterns.json")

    private val prodLikeValidator: WLSourceValidator = WLSourceValidator(
        sourceRoot = testProject,
        validationConfig = getResourceAsPath("/prod-like-config/mapping.json"),
        filterConfig = filterConfig,
        forbiddenPatterns = forbiddenPatterns
    )

    @Test
    fun lowerCaseOriginShouldBePresentInRules() {
        val rules = WLSourceValidator.loadValidationRules(
            StringReader(
                "[  {\n" +
                        "    \"origin\": \"$ORIGIN_LOWERCASE\",\n" +
                        "    \"replacement\": \"$REPLACEMENT\",\n" +
                        "    \"originTokenized\": \"brand2,all\",\n" +
                        "    \"replacementTokenized\": \"desktop,client\"\n" +
                        "  }\n]"
            ), "brand2"
        )
        assertEquals(5, rules.size)
        assertTrue(
            rules.any { it.rule == ORIGIN_LOWERCASE && it.suggestedReplacement == REPLACEMENT },
            rules.toString()
        )

    }

    @Test
    fun tokenizationTest() {
        val data = mapOf(
            """def testDialect = DialectFactory.loadCompiledDialect(BaseTest.getResourceAsStream("brand2yes4_test.prm"))"""
                    to listOf(
                "def",
                "testDialect",
                "DialectFactory",
                "loadCompiledDialect",
                "BaseTest",
                "getResourceAsStream",
                "brand2yes4_test",
                "prm"
            ),
            """("C:\\Projects\\ts\\feature_yes_adapter\\src\\main\\dialects\\brand2yes4.iso")"""
                    to listOf(
                "C",
                "Projects",
                "ts",
                "feature_yes_adapter",
                "src",
                "main",
                "dialects",
                "brand2yes4",
                "iso"
            ),
            """"/home/micro-macro/WEB-INF/test/pacs.008.001.02.xml""""
                    to listOf(
                "home",
                "micro-macro",
                "WEB-INF",
                "test",
                "pacs",
                "008",
                "001",
                "02",
                "xml"
            ),
            """app.stopService("brand2yes")"""
                    to listOf(
                "app",
                "stopService",
                "brand2yes"
            )
        )
        data.entries.forEach {
            assertEquals(it.value, it.key.split())
        }
    }

    @Test
    fun `check that content exclusions works on prod-like configuration`() {
        assertHasProblem(listOf("Brand2All"), prodLikeValidator, "/data/dataWithProblems.txt")
        assertHasProblem(listOf("BRAND2", "brand2maps", "Brand2somEthing"), prodLikeValidator, "/data/mixedTokens.txt")
        assertHasProblem(listOf("BRAND2", "BRAND2Maps", "BRAND2Maps", "BRAND2Maps"), prodLikeValidator, "/data/1.model")
        assertNoProblem(prodLikeValidator, "/data/withExcludedTokens1.txt")
        assertNoProblem(prodLikeValidator, "/data/brand2they.txt")
    }

    private fun assertNoProblem(
        validator: WLSourceValidator,
        file: String
    ) {
        val checkFileContent = validator.checkFileContent(getResourceAsPath(file))
        assertTrue(checkFileContent.second.isEmpty(), "$file must not have problems but was ${checkFileContent.second.joinToString { "\"${it.problemToken}\"" }}")
    }
    private fun assertHasProblem(
        expectedProblemTokens: List<String>,
        validator: WLSourceValidator,
        file: String
    ) {
        val checkFileContent = validator.checkFileContentWithDoubleCheck(getResourceAsPath(file))
        val problems = checkFileContent.second.map { it.problemToken }
        assertEquals(expectedProblemTokens, problems, "Not expected problems in $file")
    }

    @Test
    fun `validation report for project without problems must be empty`() {
        val validator = WLSourceValidator(
            sourceRoot = getResourceAsPath("/no-problem-project"),
            validationConfig = getResourceAsPath("/prod-like-config/mapping.json"),
            filterConfig = filterConfig,
            forbiddenPatterns = forbiddenPatterns
        )
        val report = validator.validate()
        assertTrue(report.isEmpty())
    }

    @Test
    fun validateTest() {
        val validator = WLSourceValidator(
            sourceRoot = testProject,
            validationConfig = mappingConfig,
            filterConfig = filterConfig,
            forbiddenPatterns = forbiddenPatterns
        )

        val expected = ProjectValidationResult(
            fileNameProblems = mapOf("IncludedClass.java" to "NewClass.java"),
            fileContentProblems = mapOf(
                Paths.get("dir-to-include/IncludedClass.java") to listOf(
                    ValidationProblem(
                        line = 4,
                        startPosition = 1,
                        endPosition = 10,
                        brokenRegex = "",
                        problemToken = "someToken",
                        validationProblem = "someToken",
                        suggestedReplacement = "newToken",
                        context = " someToken"
                    ),
                    ValidationProblem(
                        line = 7,
                        startPosition = 16,
                        endPosition = 26,
                        brokenRegex = "",
                        problemToken = "someMethod",
                        validationProblem = "someMethod",
                        suggestedReplacement = "newMethod"
                    ),
                    ValidationProblem(
                        line = 8,
                        startPosition = 15,
                        endPosition = 27,
                        brokenRegex = "",
                        problemToken = "someVariable",
                        validationProblem = "someVariable",
                        suggestedReplacement = "newVar"
                    )
                ),
                Paths.get("dir-to-include/TextFile.txt") to listOf(
                    ValidationProblem(
                        line = 2,
                        startPosition = 19,
                        endPosition = 29,
                        brokenRegex = "",
                        problemToken = "someMethod",
                        validationProblem = "someMethod",
                        suggestedReplacement = "newMethod",
                        context = "... with problems someMethod"
                    )
                )
            ),
            suggestedReplacements = mapOf(
                "someToken" to "newToken",
                "someMethod" to "newToken",
                "someMethod" to "newMethod",
                "someVariable" to "newVar"
            ),
            skippedFilesAndFolders = listOf(
                Paths.get("dir-to-include/ExcludedByContent.xml"),
                Paths.get("dir-to-exclude/SomeExcludedClass.java"),
                Paths.get("OneMoreExcludedClass.java")
            )
        )
        val actual = validator.validate()

        assertEquals(expected.skippedFilesAndFolders.sorted(), actual.skippedFilesAndFolders.sorted())
        assertEquals(expected.fileContentProblems.toSortedMap(), actual.fileContentProblems.toSortedMap())
        assertEquals(expected.fileNameProblems.toSortedMap(), actual.fileNameProblems.toSortedMap())
        assertEquals(expected.suggestedReplacements.toSortedMap(), actual.suggestedReplacements.toSortedMap())
    }

    @Test
    fun `problem in a binary file is reported as a short offset record`(@TempDir reportDir: Path) {
        val binaryRoot = reportDir.resolve("project").createDirectories()
        val junk = ByteArray(4096)
        val run = "X".repeat(200) + "internal-brand2-build" + "Y".repeat(200)
        val binary = binaryRoot.resolve("sentinel-cli")
        binary.writeBytes(junk + run.toByteArray(StandardCharsets.US_ASCII) + junk)

        val errorsReport = reportDir.resolve("errors.txt").toFile()
        WLReportGenerator().printValidationReport(
            WLSourceValidator(
                sourceRoot = binaryRoot,
                validationConfig = getResourceAsPath("/prod-like-config/mapping.json"),
                filterConfig = filterConfig,
                forbiddenPatterns = forbiddenPatterns
            ).validate(),
            errorsReport,
            reportDir.resolve("success.txt").toFile(),
            "1.0"
        )

        val report = String(errorsReport.readBytes(), StandardCharsets.UTF_8)
        assertFalse(report.any { it.isISOControl() && it != '\n' && it != '\r' } || report.contains('\uFFFD'),
            "Report must stay readable text, was $report")

        val records = report.lines().filter { it.startsWith("sentinel-cli:") }
        assertEquals(1, records.size, "Expected a single record, was $report")
        val record = records.single()
        assertTrue(record.length < 200, "Record must be short, was ${record.length} chars: $record")
        assertEquals(4, record.count { it == '"' }, "Quotes must be balanced: $record")
        // 4096 bytes of junk + 200 filler chars + "internal-": the offset points at the literal, not at the run
        assertTrue(record.startsWith("sentinel-cli:offset=4305 "), "Record must locate the hit by offset: $record")
        assertTrue(record.contains("brand2"), "Record must show the matched literal: $record")
        assertTrue(record.endsWith("mustn't match rule: \"brand2\""), "Record must name the rule: $record")
    }

    @Test
    fun `printable islands of machine code are skipped, string constants are not`() {
        val bytes = byteArrayOf(0, 0) + "abc".toByteArray() + byteArrayOf(0) +
                "a string constant".toByteArray() + byteArrayOf(0, 0)
        val runs = PrintableRunsInputStream(bytes.inputStream())

        assertEquals("a string constant\n", runs.bufferedReader().readText())
        assertEquals(6L, runs.offsetOf(1, 0))
    }

    @Test
    fun extendMappingTest() {

        val actual = WLSourceValidator.extendMapping(
            listOf(
                MappingConfig(
                    origin = "some-token",
                    replacement = "new-token",
                    originTokenized = "some,token",
                    replacementTokenized = "new,token"
                ),
                MappingConfig(
                    origin = "oldToken",
                    replacement = "newToken",
                    originTokenized = "old,token",
                    replacementTokenized = "new,token"
                ),
                MappingConfig(
                    origin = "old",
                    replacement = "new",
                    originTokenized = "old",
                    replacementTokenized = "new"
                ),
                MappingConfig(
                    origin = "oldnospaces",
                    replacement = "newnospaces",
                    originTokenized = "old,no,spaces",
                    replacementTokenized = "new,no,spaces"
                ),
            ), "brand2"
        )

        val expected = mapOf(
            "SOME_TOKEN" to "NEW_TOKEN",
            "SomeToken" to "NewToken",
            "someToken" to "newToken",
            "some-token" to "new-token",
            "OLD_TOKEN" to "NEW_TOKEN",
            "OldToken" to "NewToken",
            "oldToken" to "newToken",
            "oldtoken" to "newtoken",
            "OLD" to "NEW",
            "old" to "new",
            "Old" to "New",
            "OLD_NO_SPACES" to "NEW_NO_SPACES",
            "OldNoSpaces" to "NewNoSpaces",
            "oldNoSpaces" to "newNoSpaces",
            "oldnospaces" to "newnospaces"
        )
        assertEquals(expected.toSortedMap(), actual.toSortedMap())
    }

    private fun getResourceAsPath(relativePath: String): Path {
        return WLSourceValidatorTest::class.java
            .getResource(relativePath)
            ?.toURI()
            ?.toPath()
            ?: throw IllegalStateException("Can't find $relativePath in resources")
    }
}
