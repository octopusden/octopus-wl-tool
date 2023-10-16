package org.octopusden.octopus.tools.wl.validation.validator

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.octopusden.octopus.util.FileContentFilterConfig
import org.octopusden.octopus.util.FileFilter
import org.octopusden.octopus.util.FileFilterConfig
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.ZipFile
import kotlin.io.path.inputStream
import kotlin.io.path.toPath
import kotlin.io.path.writeText

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FileFilterTest {

    @Test
    fun directoryFilter_emptyFilters() {
        val filter = FileFilter.prepareDirectoryFilter(
            FileFilterConfig(
                includeDirs = emptyList(),
                excludeDirs = emptyList()
            )
        )

        val excluded = listOf(
            Paths.get("/dir/include/File.java"),
            Paths.get("/dir/include/dir"),
            Paths.get("dir/include/dir")
        )

        excludedTest(filter, excluded)
    }

    @Test
    fun directoryFilterTest() {
        val filterConfig = FileFilterConfig(
            includeDirs = listOf("**/include/**"),
            excludeDirs = listOf("**/exclude/**")
        )
        val filter = FileFilter.prepareDirectoryFilter(filterConfig)

        val included = listOf(
            Paths.get("/dir/include/dir/File.java"),
            Paths.get("/include/dir"),
            Paths.get("dir/include/dir"),
        )
        val excluded = listOf(
            Paths.get("/dir/exclude/File.java"),
            Paths.get("dir/exclude"),
            Paths.get("/exclude/dir"),
        )

        includeTest(filter, included)
        excludedTest(filter, excluded)
    }

    @Test
    fun fileFilterTest_emptyFilters() {
        val filter = FileFilter.prepareDirectoryFilter(
            FileFilterConfig(
                includeFiles = emptyList(),
                excludeFiles = emptyList()
            )
        )

        val excluded = listOf(
            Paths.get("File.java"),
            Paths.get("Another.xml"),
            Paths.get("WithoutExt")
        )

        excludedTest(filter, excluded)
    }

    @Test
    fun fileFilterTest() {
        val filterConfig = FileFilterConfig(
            includeFiles = listOf("*.java"),
            excludeFiles = listOf("*.xml")
        )
        val filter = FileFilter.prepareFileFilter(filterConfig)

        val included = listOf(
            Paths.get("TestClass.java"),
        )
        val excluded = listOf(
            Paths.get("File.xml")
        )

        includeTest(filter, included)
        excludedTest(filter, excluded)
    }


    @Test
    fun fileFilterFilterTest() {
        val (declined, accepted) = FileFilter.filter(
            "/file-filter/file-filters.json".toResourcePath(),
            "/file-filter/project".toResourcePath()
        )
        val expectedAccepted = listOf(
            "/file-filter/project/noroot.txt".toResourcePath(),
            "/file-filter/project/file.txt".toResourcePath(),
        )
        val expectedDeclined = listOf(
            "/file-filter/project/.wlignore.json".toResourcePath(),
            "/file-filter/project/src/dir1/dir3/noroot.txt".toResourcePath(),
            "/file-filter/project/src/dir1/dir3/exclude.exe".toResourcePath(),
            "/file-filter/project/src/dir1/noroot.txt".toResourcePath(),
            "/file-filter/project/src/dir2/dir4/file.txt".toResourcePath(),
            "/file-filter/project/src/dir2/file.txt".toResourcePath(),
            "/file-filter/project/src/noroot.txt".toResourcePath(),
            "/file-filter/project/everywhere.txt".toResourcePath(),
            "/file-filter/project/src/dir1/dir3/certain.txt".toResourcePath(),
            "/file-filter/project/src/dir1/dir3/everywhere.txt".toResourcePath()
        )
        Assertions.assertTrue(expectedAccepted.containsAll(accepted))
        Assertions.assertTrue(accepted.containsAll(expectedAccepted))
        Assertions.assertTrue(expectedDeclined.containsAll(declined))
        Assertions.assertTrue(declined.containsAll(expectedDeclined))
    }

    @Test
    fun fileFilterFilterZipTest() {
        val config = "/file-filter-zip/file-filters.json".loadConfigFromResources(FileFilterConfig::class.java)
        val expectedAccepted = listOf(
            "noroot.txt",
            "file.txt",
        )
        val expectedDeclined = listOf(
            "src/dir1/dir3/noroot.txt",
            "src/dir1/noroot.txt",
            "src/dir2/dir4/file.txt",
            "src/dir2/file.txt",
            "src/noroot.txt",
            "everywhere.txt",
            "src/dir1/dir3/certain.txt",
            "src/dir1/dir3/everywhere.txt",
        )

        ZipFile("/file-filter-zip/distribution.zip".toResourcePath().toFile())
            .use { zipFile ->
                val (declinedEntries, acceptedEntries) = FileFilter.filter(
                    config,
                    zipFile
                )
                val accepted = acceptedEntries.map { it.name }
                val declined = declinedEntries.map { it.name }

                Assertions.assertTrue(expectedAccepted.containsAll(accepted))
                Assertions.assertTrue(accepted.containsAll(expectedAccepted))
                Assertions.assertTrue(expectedDeclined.containsAll(declined))
                Assertions.assertTrue(declined.containsAll(expectedDeclined))
            }
    }

    @Test
    fun fileContentFilterTest_empty() {
        val filterConfig = FileFilterConfig(
            excludeFileContentFilters = listOf()
        )
        val filter = FileFilter.prepareFileContentFilter(filterConfig) { it.inputStream() }

        val included = listOf(
            Paths.get("SomeFile.java")
        )

        includeTest(filter, included)
    }

    @Test
    fun fileContentFilterTest_severalRulesWithSameExt() {
        val fileContentConfig = listOf(
            FileContentFilterConfig(
                applyToFiles = listOf("*.xslt", "*.java"),
                excludeByContent = listOf("\"BRAND2Appl\"", "\"BRAND2Doc\"",)
            ),
            FileContentFilterConfig(
                applyToFiles = listOf("*.xslt"),
                excludeByContent = listOf("\"BRANDAppl\"")
            )
        )

        val config = FileFilterConfig(
            excludeFileContentFilters = fileContentConfig
        )

        val filter = FileFilter.prepareFileContentFilter(config) { it.inputStream() }

        val xsltFile = kotlin.io.path.createTempFile(suffix = ".xslt")
        xsltFile.writeText(
            """
<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
     <xsl:output method="xml" version="1.0" encoding="UTF-8" indent="yes" omit-xml-declaration="yes"/>
     <xsl:template match="/UFXMsg[MsgData/Application]">
          <UFXMsg direction="Rq" msg_type="Application" scheme="BRANDAppl">
"""
        )

        excludedTest(filter, listOf(xsltFile))
    }

    @Test
    fun fileContentFilterTest_severalRules() {
        val fileContentConfig = listOf(
            FileContentFilterConfig(
                applyToFiles = listOf("*.xml", "*.java"),
                excludeByContent = listOf("\"BRAND2Appl\"", "\"BRAND2Doc\"", "\"BRAND2Profile\"", "\"BRAND2ConfigInfo\"")
            ),
            FileContentFilterConfig(
                applyToFiles = listOf("*.xslt"),
                excludeByContent = listOf("\"BRAND2Appl\"", "\"BRAND2Doc\"")
            )
        )
        val config = FileFilterConfig(
            excludeFileContentFilters = fileContentConfig
        )

        val filter = FileFilter.prepareFileContentFilter(config) { it.inputStream() }

        val xsltFile = kotlin.io.path.createTempFile(suffix = ".xslt")
        xsltFile.writeText(
            """
<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
     <xsl:output method="xml" version="1.0" encoding="UTF-8" indent="yes" omit-xml-declaration="yes"/>
     <xsl:template match="/UFXMsg[MsgData/Application]">
          <UFXMsg direction="Rq" msg_type="Application" scheme="BRAND2Appl">
"""
        )
        excludedTest(filter, listOf(xsltFile))
    }

    @Test
    fun fileContentFilterTest() {
        val filterConfig =
            "/file-content-filter/file-filters.json".loadConfigFromResources(FileFilterConfig::class.java)
        val filter = FileFilter.prepareFileContentFilter(filterConfig) { it.inputStream() }

        val javaFile = "/file-content-filter/project/file.java".toResourcePath()
        val xmlFile = "/file-content-filter/project/file.xml".toResourcePath()
        val plainFile = "/file-content-filter/project/file.txt".toResourcePath()

        val excluded = listOf(javaFile, xmlFile, plainFile)

        val includedFile = "/file-content-filter/project/file.kt".toResourcePath()
        val included = listOf(includedFile)

        includeTest(filter, included)
        excludedTest(filter, excluded)
    }

    @Test
    fun fileZipContentFilterTest() {
        val config = "/file-content-filter/file-filters.json".loadConfigFromResources(FileFilterConfig::class.java)
        val archive = ZipFile("/file-content-filter/distribution.zip".toResourcePath().toFile())

        val (declined, accepted) = FileFilter.filter(config, archive)

        Assertions.assertIterableEquals(listOf("file.java", "file.txt", "file.xml"), declined.map { it.name })
        Assertions.assertIterableEquals(listOf("file.kt"), accepted.map { it.name })
    }

    private fun excludedTest(filter: (Path) -> Boolean, excluded: List<Path>) {
        excluded.forEach {
            Assertions.assertFalse(filter.invoke(it), "exclude test failed with $it")
        }
    }

    private fun includeTest(filter: (Path) -> Boolean, included: List<Path>) {
        included.forEach {
            Assertions.assertTrue(filter.invoke(it), "include test failed with: $it")
        }
    }

    companion object {
        private val mapper = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)

        private fun String.toResourcePath(): Path = this::class.java.getResource(this)
            ?.toURI()?.toPath() ?: throw IllegalArgumentException("There is '$this' in resources")

        private fun <T> String.loadConfigFromResources(aClass: Class<T>): T {
            return mapper.readValue(this::class.java.getResource(this), aClass)
        }
    }
}
