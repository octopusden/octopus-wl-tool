package org.octopusden.octopus.components.automation.task

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.javaparser.JavaParser
import com.github.javaparser.ast.nodeTypes.NodeWithIdentifier
import org.octopusden.octopus.tools.wl.validation.validator.CopyrightValidator
import org.octopusden.octopus.util.FileFilter
import org.slf4j.LoggerFactory
import java.io.FileReader
import java.io.Reader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.bufferedReader
import kotlin.io.path.extension
import kotlin.io.path.fileSize
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile

class WLSourceValidator(
    private val sourceRoot: Path,
    validationConfig: Path,
    val filterConfig: Path,
    forbiddenPatterns: Path
) {
    val validationRules: List<FileValidationRule>
    private val copyrightValidator:CopyrightValidator
    private val props : WLProperties
    private val exceptionItems : List<String>
    private val restrictedItems : List<String>

    init {
        validateConfigFiles(sourceRoot, validationConfig, filterConfig, forbiddenPatterns)

        props  = objectMapper.readValue(forbiddenPatterns.toFile())
        exceptionItems = props.exceptions
        restrictedItems = listOf(props.restricted)
        copyrightValidator = CopyrightValidator(props)
        FileReader(validationConfig.toFile()).use {
            validationRules = loadValidationRules(it, props.restricted)
        }
    }

    fun validate(): ProjectValidationResult {
        val fileContentProblems: MutableMap<Path, List<ValidationProblem>> = HashMap()

        val (skippedFiles, filesToCheck) = FileFilter.filter(filterConfig.toAbsolutePath(), sourceRoot)
        val fileNameProblems: Map<String, String> = checkForNameProblems(filesToCheck)

        filesToCheck.forEachIndexed { index, file ->
            logger.info("Validate $file")
            val checkFileResult = checkFileContentWithDoubleCheck(file)
            val copyRightValidationResult = if (file.isRegularFile()) {
                val sizeKB = file.fileSize().div(1000)
                if (logger.isTraceEnabled) {
                    logger.trace("Size $file: ${sizeKB}KB")
                }
                file.inputStream()
                    .use { inputStream -> copyrightValidator.validate(inputStream) }
            } else {
                emptyList()
            }
            if (checkFileResult.second.isNotEmpty() || copyRightValidationResult.isNotEmpty()) {
                fileContentProblems[checkFileResult.first] = checkFileResult.second + copyRightValidationResult
            }
            if ((index + 1) % 100 == 0) {
                logger.info("Validated ${index + 1} files")
            }
        }

        logger.info("Validated ${filesToCheck.size} files")

        val suggestedReplacements = fileContentProblems.values.flatMap(::distinctProblems).toMap()

        val projectValidationResult =
            ProjectValidationResult(fileNameProblems, fileContentProblems, suggestedReplacements, skippedFiles.map { sourceRoot.relativize(it) })
        logger.info("Validation finished successfully")

        return projectValidationResult
    }

    fun checkFileContentWithDoubleCheck(fileToCheck: Path): Pair<Path, List<ValidationProblem>> {
        val checkFileContent = checkFileContent(fileToCheck)
        return if (checkFileContent.second.isNotEmpty()) {
            checkFileContent
        } else {
            val doubleCheckResult = checkFileContentLight(fileToCheck)
            if (doubleCheckResult.second.isNotEmpty()) {
                logger.warn("Double check found problem in $fileToCheck")
            }
            doubleCheckResult
        }
    }

    private fun distinctProblems(validationProblems: List<ValidationProblem>): List<Pair<String, String>> {
        return validationProblems.map { it.problemToken to it.suggestedReplacement }
    }

    private fun checkForNameProblems(filesToCheck: List<Path>): Map<String, String> {
        return filesToCheck.mapNotNull(::verifyFileName).toMap()
    }

    private fun verifyFileName(file: Path): Pair<String, String>? {
        val testTokenAgainstRules =
            TextTokenHandler(validationRules, exceptionItems).testTokenAgainstRules(file.fileName.toString(), 0, 0, 0)
        return testTokenAgainstRules?.let { return it.problemToken to it.suggestedReplacement }
    }

    private fun checkFileContentLight(filePath: Path): Pair<Path, List<ValidationProblem>> {
        val fileSize = filePath.toFile().length()
        val validationProblems = if (fileSize < MAX_FILE_SIZE) {
            val initialText = filePath.toFile().readText().lowercase()
            val text = exceptionItems.fold(initialText) { result, element ->
                result.replace(
                    element,
                    TextTokenHandler.PLACEHOLDER
                )
            }
            restrictedItems.map { restrictedItem ->
                if (text.lowercase().contains(restrictedItem)) {
                    ValidationProblem(-1, -1, -1, "", restrictedItem, restrictedItem, "UNKNOWN_REPLACEMENT")
                } else {
                    null
                }
            }.filterNotNull()
        } else {
            logger.info("skip $filePath due to size=${filePath.toFile().length()}")
            emptyList()
        }
        return filePath to validationProblems
    }


    fun checkFileContent(file: Path): Pair<Path, List<ValidationProblem>> {
        logger.debug("Start validation for file={}", file.relativizeAgainstSourceRoot())
        val validationProblems = when (file.extension) {
            "java" -> processJavaSourceFile(file)
            "xml" -> processStructuredFormat(XmlMapper(), file)
            "json" -> processStructuredFormat(ObjectMapper(), file)
            else -> processTextFile(file)
        }

        return when (validationProblems) {
            is Ok -> file.relativizeAgainstSourceRoot() to validationProblems.value
            is Er -> {
                logger.warn(
                    "Can't process file=${file.relativizeAgainstSourceRoot()}, fallback to text processing",
                    validationProblems.error.message
                )
                // fallback to simple text processing
                when (val result = processTextFile(file)) {
                    is Ok -> file.relativizeAgainstSourceRoot() to result.value
                    is Er -> {
                        logger.error("Can't process file=${file.relativizeAgainstSourceRoot()}", result.error)
                        file to emptyList()
                    }
                }
            }
        }
    }

    private fun processTextFile(
        file: Path,
    ): Outcome<List<ValidationProblem>> {
        return try {
            val validationProblems: MutableList<ValidationProblem> = ArrayList()
            file.bufferedReader().use { bufferedReader ->
                var lineNumber = 0
                var line = bufferedReader.readLine()
                while (line != null) {
                    lineNumber += 1

                    val result = processText(line, lineNumber)
                    validationProblems.addAll(result)

                    line = bufferedReader.readLine()
                }
                Ok(validationProblems)
            }
        } catch (ex: Throwable) {
            Er(ex)
        }
    }

    private fun processText(text: String, lineNumber: Int): List<ValidationProblem> {
        val validationProblems: MutableList<ValidationProblem> = ArrayList()
        text.split().forEach { token ->
            val startPos = text.indexOf(token)
            val endPos = startPos + token.length

            val result = TextTokenHandler(validationRules, exceptionItems).testTokenAgainstRules(
                token,
                lineNumber,
                startPos,
                endPos
            )
            if (result != null) {
                validationProblems.add(result)
            }
        }
        return validationProblems
    }

    private fun processStructuredFormat(
        objectMapper: ObjectMapper,
        file: Path,
    ): Outcome<List<ValidationProblem>> {
        return try {
            val parser = objectMapper.createParser(file.toFile())

            var location = parser.currentLocation
            var token = parser.nextToken()
            val validationProblems: MutableList<ValidationProblem> = ArrayList()

            while (token != null) {
                if (!token.isStructStart && !token.isStructEnd) {
                    val tokenText = parser.text
                    val line = location.lineNr

                    validationProblems.addAll(processText(tokenText, line))
                }
                token = parser.nextToken()
                location = parser.currentLocation
            }
            Ok(validationProblems)
        } catch (ex: Throwable) {
            Er(ex)
        }
    }

    private fun processJavaSourceFile(file: Path): Outcome<List<ValidationProblem>> {
        return try {
            val parseResult = JavaParser().parse(file)
            if (parseResult.isSuccessful) {
                val validationProblems: MutableList<ValidationProblem> = ArrayList()

                if (parseResult.commentsCollection.isPresent) {
                    parseResult.commentsCollection.get().comments.forEach {
                        val commentText = it.content
                        val result = processText(commentText, it.begin.get().line)
                        validationProblems.addAll(result)
                    }
                }

                val ast = parseResult.result.get()
                ast.walk { node ->
                    if (node is NodeWithIdentifier<*>) {
                        val identifier = node.identifier
                        val nodeBegin = node.begin.get()
                        val nodeEnd = node.end.get()

                        val line = nodeBegin.line
                        val startPos = nodeEnd.column - identifier.length
                        val endPos = nodeEnd.column

                        val result = TextTokenHandler(validationRules, exceptionItems).testTokenAgainstRules(
                            identifier,
                            line,
                            startPos,
                            endPos,
                        )

                        if (result != null) {
                            validationProblems.add(result)
                        }
                    }
                }
                Ok(validationProblems)
            } else {
                Er(Exception("Parse error=${parseResult.problems}"))
            }
        } catch (ex: Throwable) {
            //TODO: hidding potential bugs!
            Er(ex)
        }
    }

    private fun Path.relativizeAgainstSourceRoot() = sourceRoot.relativize(this)

    companion object {
        private val logger = LoggerFactory.getLogger(WLSourceValidator::class.java)
        private val objectMapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

        private const val MAX_FILE_SIZE = 10000000

        private fun validateConfigFiles(vararg paths: Path) {
            paths.forEach { p ->
                if (Files.notExists(p)) {
                    throw IllegalArgumentException("Config=${p.toAbsolutePath()} does not exist")
                }
            }
        }

        fun loadValidationRules(reader: Reader, restricted: String): List<FileValidationRule> {
            val initMappings: List<MappingConfig> = objectMapper.readValue(reader)
            val mappings = extendMapping(initMappings, restricted)
            return mappings.entries.map { FileValidationRule(it.key, it.value) }
                .sortedByDescending { it.rule.length }
        }

        internal fun extendMapping(mappings: List<MappingConfig>, restricted: String): Map<String, String> {
            return mappings.flatMap{process(it, restricted)}.toMap()
        }

        private fun process(mappingConfig: MappingConfig, restricted: String): List<Pair<String, String>> {
            return listOf(
                snakeCase(mappingConfig.originTokenized) to snakeCase(mappingConfig.replacementTokenized),
                camelCase(mappingConfig.originTokenized) to camelCase(mappingConfig.replacementTokenized),
                camelCaseFirstSentenceCase(mappingConfig.originTokenized) to camelCaseFirstSentenceCase(mappingConfig.replacementTokenized),
                restrictedCapitalized(mappingConfig.originTokenized, restricted) to camelCase(mappingConfig.replacementTokenized),
                mappingConfig.origin to mappingConfig.replacement,
                mappingConfig.origin.lowercase() to mappingConfig.replacement.lowercase()
            )
        }

        private fun restrictedCapitalized(string: String, restrictedItem: String, delimiter: String = ",", separator: String = ""): String {
            return if (string.startsWith(restrictedItem, true)) {
                val sb = StringBuilder()
                val parts = string.split(delimiter)
                val restrictedToken = parts[0]
                sb.append(restrictedToken.uppercase())
                sb.append(parts.drop(1).joinToString(separator = separator, transform = String::capitalize))
                sb.toString()
            } else {
                camelCase(string, delimiter, separator)
            }
        }

        private fun snakeCase(string: String, delimiter: String = ",", separator: String = "_"): String {
            return string.split(delimiter).joinToString(separator = separator, transform = String::uppercase)
        }

        private fun camelCase(string: String, delimiter: String = ",", separator: String = ""): String {
            return string.split(delimiter).joinToString(separator = separator, transform = String::capitalize)
        }

        private fun camelCaseFirstSentenceCase(
            string: String,
            delimiter: String = ",",
            separator: String = ""
        ): String {
            return camelCase(string, delimiter, separator).decapitalize()
        }
    }
}

internal fun String.split(): List<String> {
    return this.split(" ", ",", ".", "=", ":", "(", ")", "\"", "\\", "/", "{", "}", "$", "<", ">")
        .filter(String::isNotBlank)
}

data class FileValidationRule(
    val rule: String,
    val suggestedReplacement: String,
)

data class ProjectValidationResult(
    val fileNameProblems: Map<String, String>,
    val fileContentProblems: Map<Path, List<ValidationProblem>>,
    val suggestedReplacements: Map<String, String>,
    val skippedFilesAndFolders: List<Path>,
) {
    fun isNotEmpty(): Boolean {
        return fileNameProblems.isNotEmpty() || fileContentProblems.isNotEmpty()
    }
    fun isEmpty(): Boolean {
        return !isNotEmpty()
    }
}

data class MappingConfig(
    val origin: String,
    val replacement: String,
    val originTokenized: String,
    val replacementTokenized: String,
)

sealed class Outcome<out T>

data class Ok<out T>(val value: T) : Outcome<T>()

data class Er(val error: Throwable) : Outcome<Nothing>()
