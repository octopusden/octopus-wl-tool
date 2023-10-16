package org.octopusden.octopus.tools.wl.validation.validator

import org.octopusden.octopus.util.FileFilter
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Stream
import java.util.zip.ZipFile

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ForbiddenFileNameValidatorTest {

    @ParameterizedTest
    @MethodSource("forbiddenFileNameValidatorParameters")
    fun testForbiddenFileNameValidator(path: Path, support: Boolean, expectedResult: List<String>) {

        val validator = ForbiddenFileNameValidator(ALLOWED_FILE_NAMES)
        Assertions.assertEquals(support, FileFilter.isZipFile(path))

        if (support) {
            val fileNames = ZipFile(path.toFile())
                .use {
                    val fileName = path.fileName
                    it.entries()
                        .toList()
                        .map { zipEntry -> "${fileName}${File.separator}${zipEntry.name}" }
                        .toSet()
                }

            val actualValidationResult = validator.validate(fileNames)
            Assertions.assertIterableEquals(expectedResult.sorted(), actualValidationResult.sorted())
        }
    }

    private fun forbiddenFileNameValidatorParameters(): Stream<Arguments> = Stream.of(
        Arguments.of(Paths.get("src/test/resources/distribution/filename-validator/distribution-allowed.zip"), true, emptyList<String>()),
        Arguments.of(
            Paths.get("src/test/resources/distribution/filename-validator/brand2-distribution-forbidden.zip"),
            true,
            listOf(
                "/brand2-distribution-forbidden.zip does not match $ALLOWED_FILE_NAMES",
                "/brand2-distribution-forbidden.zip/1/1/3/brand2-file.txt does not match $ALLOWED_FILE_NAMES",
                "/brand2-distribution-forbidden.zip/brand2/2/brand2-file.txt does not match $ALLOWED_FILE_NAMES",
                "/brand2-distribution-forbidden.zip/brand2/brand2-file.txt does not match $ALLOWED_FILE_NAMES",
                "/brand2-distribution-forbidden.zip/brand2-file.txt does not match $ALLOWED_FILE_NAMES"
            ) +
                    if (System.getProperty("os.name").lowercase().contains("win")) {
                        listOf(
                            "/brand2-distribution-forbidden.zip/brand2/ does not match $ALLOWED_FILE_NAMES",
                            "/brand2-distribution-forbidden.zip/brand2/1/ does not match ^(?!.*(brand2(?![Uu]))).*\$",
                            "/brand2-distribution-forbidden.zip/brand2/2/ does not match ^(?!.*(brand2(?![Uu]))).*\$",
                            "/brand2-distribution-forbidden.zip/brand2/1/brand2u-file.txt does not match ^(?!.*(brand2(?![Uu]))).*\$"
                        )
                    } else {
                        listOf(
                            "/brand2-distribution-forbidden.zip/brand2 does not match $ALLOWED_FILE_NAMES"
                        )
                    }
        ),
       Arguments.of(
            Paths.get("src/test/resources/distribution/filename-validator/distribution-unsupported.tar"),
            false,
            emptyList<String>()
        )
    )

    companion object {
        private const val ALLOWED_FILE_NAMES = "^(?!.*(brand2(?![Uu]))).*\$"
    }
}
