package org.octopusden.octopus.components.automation.task

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.javaparser.JavaParser
import com.github.javaparser.ast.nodeTypes.NodeWithIdentifier
import org.octopusden.octopus.tools.wl.PatternCalculator
import org.octopusden.octopus.tools.wl.validation.validator.CopyrightValidator
import org.octopusden.octopus.util.FileFilter
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.FileReader
import java.io.FilterInputStream
import java.io.InputStream
import java.io.Reader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.bufferedReader
import kotlin.io.path.extension
import kotlin.io.path.fileSize
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile

class WLSourceValidator(private val sourceRoot: Path, validationConfig: Path, val filterConfig: Path, forbiddenPatterns: Path) {
    val validationRules: List<FileValidationRule>
    private val copyrightValidator: CopyrightValidator
    private val props: WLProperties
    private val exceptionItems: List<String>
    private val exceptionsPattern: Regex
    private val restrictedItems: List<String>

    init {
        validateConfigFiles(sourceRoot, validationConfig, filterConfig, forbiddenPatterns)

        props = objectMapper.readValue(forbiddenPatterns.toFile())
        exceptionItems = props.exceptions
        exceptionsPattern = Regex(PatternCalculator().calculate(exceptionItems))
        restrictedItems = listOf(props.restricted)
        copyrightValidator = CopyrightValidator(props.contains, props.patterns)
        FileReader(validationConfig.toFile()).use {
            validationRules = loadValidationRules(it, props.restricted)
        }
    }

    fun validate(): ProjectValidationResult {
        val fileContentProblems: MutableMap<Path, List<ValidationProblem>> = HashMap()
        val notScanned: MutableMap<Path, String> = LinkedHashMap()

        val (skippedFiles, filesToCheck) = FileFilter.filter(filterConfig.toAbsolutePath(), sourceRoot)
        val fileNameProblems: Map<String, String> = checkForNameProblems(filesToCheck)

        filesToCheck.forEachIndexed { index, file ->
            logger.info("Validate $file")
            val binary = FileContent.isBinary(file)
            val checkFileResult = checkFileContentWithDoubleCheck(file, binary)
            val copyRightValidationResult = if (file.isRegularFile()) {
                validateCopyright(file, binary)
            } else {
                emptyList()
            }
            if (checkFileResult.second.isNotEmpty() || copyRightValidationResult.isNotEmpty()) {
                fileContentProblems[checkFileResult.first] = checkFileResult.second + copyRightValidationResult
            }
            // loud on purpose: a check that did not run makes a clean result mean "found nothing in
            // what was read", and the report is the only place that difference can be seen
            skipReason(file, binary)?.let { reason ->
                logger.warn("Not fully scanned $file: $reason")
                notScanned[file.relativizeAgainstSourceRoot()] = reason
            }
            if ((index + 1) % 100 == 0) {
                logger.info("Validated ${index + 1} files")
            }
        }

        logger.info("Validated ${filesToCheck.size} files")

        val suggestedReplacements = fileContentProblems.values.flatMap(::distinctProblems).toMap()

        val projectValidationResult =
            ProjectValidationResult(
                fileNameProblems,
                fileContentProblems,
                suggestedReplacements,
                skippedFiles.map {
                    sourceRoot.relativize(it)
                },
                notScanned,
            )
        logger.info("Validation finished successfully")

        return projectValidationResult
    }

    /**
     * What of this file was not looked at, or null when all of it was. Reported whatever the outcome:
     * what did not run is a fact about the scan, and deciding when a gap is worth mentioning is how
     * gaps stop being mentioned.
     */
    private fun skipReason(file: Path, binary: Boolean): String? = when {
        binary && exceedsBinaryLimit(file) ->
            "unscanned: size (${file.fileSize()} bytes, over the ${binaryLimit()} this JVM's heap allows)"

        !binary && exceedsLightCheckLimit(file) ->
            "partially scanned: double check skipped (size ${file.fileSize()} bytes, limit $LIGHT_CHECK_MAX_FILE_SIZE)"

        else -> null
    }

    /**
     * Only the double check has a size limit among the text checks, because only it materialises the
     * whole file as a String. The others stream, which bounds their memory by what a single line, token
     * or AST costs - not by the file's size, though not to any fixed ceiling either.
     */
    private fun exceedsLightCheckLimit(file: Path) = file.isRegularFile() &&
        file.fileSize() >= LIGHT_CHECK_MAX_FILE_SIZE

    /**
     * Binary content is read as its printable runs, and measured that costs roughly the file's own size
     * in heap on top of a constant of churn: on this machine a fragmented 256 MB binary peaked at
     * 262 MB and died outright in a 256 MB heap, while 128 MB peaked at 140 MB and survived one. So the
     * limit is a share of the heap this JVM was actually given rather than a round number - a quarter,
     * which is that measured 1:1 need with margin. It follows the environment, so the report says which
     * limit applied.
     */
    private fun exceedsBinaryLimit(file: Path) = file.isRegularFile() && file.fileSize() > binaryLimit()

    private fun binaryLimit() = Runtime.getRuntime().maxMemory() / BINARY_HEAP_SHARE

    private fun refuseBinary(file: Path): Pair<Path, List<ValidationProblem>> {
        logger.warn("Not scanning binary $file: size ${file.fileSize()} needs more heap than ${binaryLimit()} allows")
        return file.relativizeAgainstSourceRoot() to emptyList()
    }

    @JvmOverloads
    fun checkFileContentWithDoubleCheck(
        fileToCheck: Path,
        binary: Boolean = FileContent.isBinary(fileToCheck),
    ): Pair<Path, List<ValidationProblem>> {
        if (binary && exceedsBinaryLimit(fileToCheck)) {
            return refuseBinary(fileToCheck)
        }
        val checkFileContent = checkFileContent(fileToCheck, binary)
        return if (checkFileContent.second.isNotEmpty()) {
            checkFileContent
        } else {
            val doubleCheckResult = checkFileContentLight(fileToCheck, binary)
            if (doubleCheckResult.second.isNotEmpty()) {
                logger.warn("Double check found problem in $fileToCheck")
            }
            doubleCheckResult
        }
    }

    private fun distinctProblems(validationProblems: List<ValidationProblem>): List<Pair<String, String>> = validationProblems.map {
        it.problemToken to it.suggestedReplacement
    }

    private fun checkForNameProblems(filesToCheck: List<Path>): Map<String, String> = filesToCheck.mapNotNull(::verifyFileName).toMap()

    private fun verifyFileName(file: Path): Pair<String, String>? {
        val testTokenAgainstRules =
            TextTokenHandler(validationRules, exceptionItems).testTokenAgainstRules(file.fileName.toString(), 0, 0, 0)
        return testTokenAgainstRules?.let { return it.problemToken to it.suggestedReplacement }
    }

    private fun validateCopyright(file: Path, binary: Boolean): List<ValidationProblem> = if (binary) {
        if (exceedsBinaryLimit(file)) {
            emptyList()
        } else {
            validateCopyrightOfBinary(file)
        }
    } else {
        file.inputStream().use { inputStream -> copyrightValidator.validate(inputStream) }
    }

    private fun validateCopyrightOfBinary(file: Path): List<ValidationProblem> = file.inputStream().buffered().use { source ->
        val runs = PrintableRunsInputStream(source)
        copyrightValidator.validate(runs).map(runs::asBinaryProblem)
    }

    private fun checkFileContentLight(filePath: Path, binary: Boolean): Pair<Path, List<ValidationProblem>> {
        if (binary) {
            return filePath.relativizeAgainstSourceRoot() to checkBinaryContentLight(filePath)
        }
        if (exceedsLightCheckLimit(filePath)) {
            logger.info("skip double check of $filePath due to size=${filePath.fileSize()}")
            return filePath.relativizeAgainstSourceRoot() to emptyList()
        }
        // maskExceptions, not a literal replace of exceptionItems: the items come from the config
        // verbatim, so one spelled with capitals never matched the lowercased text and the file was
        // reported here while the binary path, which masks case-insensitively, suppressed it.
        val text = maskExceptions(filePath.toFile().readText().lowercase())
        val validationProblems = restrictedItems.mapNotNull { restrictedItem ->
            if (text.contains(restrictedItem)) {
                ValidationProblem(-1, -1, -1, "", restrictedItem, restrictedItem, "UNKNOWN_REPLACEMENT")
            } else {
                null
            }
        }
        return filePath.relativizeAgainstSourceRoot() to validationProblems
    }

    /**
     * Same paranoid substring check as [checkFileContentLight], but over the printable runs of a binary
     * instead of its whole content decoded as a String.
     */
    private fun checkBinaryContentLight(filePath: Path): List<ValidationProblem> = filePath.inputStream().buffered().use { source ->
        val runs = PrintableRunsInputStream(source)
        runs.bufferedReader().useLines { lines ->
            lines.withIndex().firstNotNullOfOrNull { (index, line) ->
                val text = maskExceptions(line.lowercase())
                restrictedItems.firstNotNullOfOrNull { restrictedItem ->
                    val position = text.indexOf(restrictedItem)
                    if (position >= 0) {
                        ValidationProblem(
                            -1,
                            -1,
                            -1,
                            "",
                            restrictedItem,
                            restrictedItem,
                            "UNKNOWN_REPLACEMENT",
                            byteOffset = runs.offsetOf(index + 1, position),
                        )
                    } else {
                        null
                    }
                }
            }?.let(::listOf) ?: emptyList()
        }
    }

    /**
     * Binary content has no lines: it is scanned as a stream of printable runs and problems are located
     * by byte offset, so a report entry stays short and readable instead of quoting the surrounding bytes.
     */
    private fun processBinaryFile(file: Path): Outcome<List<ValidationProblem>> = try {
        file.inputStream().buffered().use { source ->
            val runs = PrintableRunsInputStream(source)
            val problems = processLines(runs.bufferedReader())
            Ok(problems.map(runs::asBinaryProblem))
        }
    } catch (ex: Throwable) {
        Er(ex)
    }

    @JvmOverloads
    fun checkFileContent(file: Path, binary: Boolean = FileContent.isBinary(file)): Pair<Path, List<ValidationProblem>> = when {
        binary && exceedsBinaryLimit(file) -> refuseBinary(file)
        else -> scanFileContent(file, binary)
    }

    private fun scanFileContent(file: Path, binary: Boolean): Pair<Path, List<ValidationProblem>> {
        logger.debug("Start validation for file={}", file.relativizeAgainstSourceRoot())
        if (binary) {
            return when (val result = processBinaryFile(file)) {
                is Ok -> file.relativizeAgainstSourceRoot() to result.value

                is Er -> {
                    logger.error("Can't process binary file=${file.relativizeAgainstSourceRoot()}", result.error)
                    file.relativizeAgainstSourceRoot() to emptyList()
                }
            }
        }
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
                    validationProblems.error.message,
                )
                // fallback to simple text processing
                when (val result = processTextFile(file)) {
                    is Ok -> file.relativizeAgainstSourceRoot() to result.value

                    is Er -> {
                        logger.error("Can't process file=${file.relativizeAgainstSourceRoot()}", result.error)
                        file.relativizeAgainstSourceRoot() to emptyList()
                    }
                }
            }
        }
    }

    private fun processTextFile(file: Path): Outcome<List<ValidationProblem>> = try {
        file.bufferedReader().use { bufferedReader -> Ok(processLines(bufferedReader)) }
    } catch (ex: Throwable) {
        Er(ex)
    }

    private fun processLines(reader: BufferedReader): List<ValidationProblem> {
        val validationProblems: MutableList<ValidationProblem> = ArrayList()
        var lineNumber = 0
        var line = reader.readLine()
        while (line != null) {
            lineNumber += 1
            validationProblems.addAll(processText(line, lineNumber))
            line = reader.readLine()
        }
        return validationProblems
    }

    private fun processText(text: String, lineNumber: Int): List<ValidationProblem> {
        val validationProblems: MutableList<ValidationProblem> = ArrayList()
        // tokens come in the order they occur, so walking a cursor keeps a repeated token at its own position
        var cursor = 0
        // rules are matched against a token whose exception items are masked out, so a position found in
        // the raw line could point at a rule occurrence inside a permitted item
        val masked = maskExceptions(text)
        text.split().forEach { token ->
            val startPos = text.indexOf(token, cursor).takeIf { it >= 0 } ?: cursor
            val endPos = startPos + token.length
            cursor = endPos

            val result = TextTokenHandler(validationRules, exceptionItems).testTokenAgainstRules(
                token,
                lineNumber,
                startPos,
                endPos,
            )
            if (result != null) {
                // a problem is located by the rule that matched, not by the token around it: in a binary a
                // single token can be kilobytes of string table, and the position is all a report entry has
                // bounded by the token: rules are matched against a token whose exceptions became
                // PLACEHOLDER, so a rule literal occurring only inside that placeholder text is
                // absent from `masked` here - an unbounded search would then report the next
                // occurrence, in an unrelated token, as this problem's position and context
                val ruleStart = masked.indexOf(result.validationProblem, startPos, ignoreCase = true)
                    .takeIf { it >= startPos && it + result.validationProblem.length <= endPos } ?: -1
                validationProblems.add(
                    if (ruleStart >= 0) {
                        val ruleEnd = ruleStart + result.validationProblem.length
                        result.copy(
                            startPosition = ruleStart,
                            endPosition = ruleEnd,
                            context = text.withContext(ruleStart, ruleEnd),
                        )
                    } else {
                        result.copy(context = text.withContext(startPos, endPos))
                    },
                )
            }
        }
        return validationProblems
    }

    /** Same-length mask, so a position found in the masked text is a position in the original. */
    private fun maskExceptions(text: String) = exceptionsPattern.replace(text) { "#".repeat(it.value.length) }

    private fun processStructuredFormat(objectMapper: ObjectMapper, file: Path): Outcome<List<ValidationProblem>> = try {
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

    private fun processJavaSourceFile(file: Path): Outcome<List<ValidationProblem>> = try {
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
        // TODO: hidding potential bugs!
        Er(ex)
    }

    private fun Path.relativizeAgainstSourceRoot() = sourceRoot.relativize(this)

    companion object {
        private val logger = LoggerFactory.getLogger(WLSourceValidator::class.java)
        private val objectMapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

        /** The limit the double check had before the size guard was moved around, kept as it was. */
        internal const val LIGHT_CHECK_MAX_FILE_SIZE = 10000000L
        private const val BINARY_HEAP_SHARE = 4

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

        internal fun extendMapping(mappings: List<MappingConfig>, restricted: String): Map<String, String> = mappings.flatMap {
            process(it, restricted)
        }.toMap()

        private fun process(mappingConfig: MappingConfig, restricted: String): List<Pair<String, String>> = listOf(
            snakeCase(mappingConfig.originTokenized) to snakeCase(mappingConfig.replacementTokenized),
            camelCase(mappingConfig.originTokenized) to camelCase(mappingConfig.replacementTokenized),
            camelCaseFirstSentenceCase(mappingConfig.originTokenized) to camelCaseFirstSentenceCase(mappingConfig.replacementTokenized),
            restrictedCapitalized(mappingConfig.originTokenized, restricted) to camelCase(mappingConfig.replacementTokenized),
            mappingConfig.origin to mappingConfig.replacement,
            mappingConfig.origin.lowercase() to mappingConfig.replacement.lowercase(),
        )

        private fun restrictedCapitalized(string: String, restrictedItem: String, delimiter: String = ",", separator: String = ""): String =
            if (string.startsWith(restrictedItem, true)) {
                val sb = StringBuilder()
                val parts = string.split(delimiter)
                val restrictedToken = parts[0]
                sb.append(restrictedToken.uppercase())
                sb.append(parts.drop(1).joinToString(separator = separator, transform = String::capitalize))
                sb.toString()
            } else {
                camelCase(string, delimiter, separator)
            }

        private fun snakeCase(string: String, delimiter: String = ",", separator: String = "_"): String =
            string.split(delimiter).joinToString(separator = separator, transform = String::uppercase)

        private fun camelCase(string: String, delimiter: String = ",", separator: String = ""): String =
            string.split(delimiter).joinToString(separator = separator, transform = String::capitalize)

        private fun camelCaseFirstSentenceCase(string: String, delimiter: String = ",", separator: String = ""): String =
            camelCase(string, delimiter, separator).decapitalize()
    }
}

internal fun String.split(): List<String> = this.split(" ", ",", ".", "=", ":", "(", ")", "\"", "\\", "/", "{", "}", "$", "<", ">")
    .filter(String::isNotBlank)

data class FileValidationRule(val rule: String, val suggestedReplacement: String)

data class ProjectValidationResult(
    val fileNameProblems: Map<String, String>,
    val fileContentProblems: Map<Path, List<ValidationProblem>>,
    val suggestedReplacements: Map<String, String>,
    val skippedFilesAndFolders: List<Path>,
    val notScanned: Map<Path, String> = emptyMap(),
) {
    fun isNotEmpty(): Boolean = fileNameProblems.isNotEmpty() || fileContentProblems.isNotEmpty()
    fun isEmpty(): Boolean = !isNotEmpty()
}

data class MappingConfig(val origin: String, val replacement: String, val originTokenized: String, val replacementTokenized: String)

sealed class Outcome<out T>

data class Ok<out T>(val value: T) : Outcome<T>()

data class Er(val error: Throwable) : Outcome<Nothing>()

/**
 * Turns a binary stream into the stream of its printable ASCII runs, one run per line, and remembers the
 * byte offset every run starts at. Lets the line-based validators work on a binary without giant lines,
 * without meaningless line numbers and without reading the file into memory.
 *
 * Runs shorter than [MIN_RUN_LENGTH] are dropped: in machine code printable bytes turn up by accident all
 * the time, and a short rule then matches instruction bytes, while real string constants are longer. Same idea as
 * `strings -n`.
 *
 * ponytail: two known ceilings - a forbidden literal shorter than [MIN_RUN_LENGTH] standing alone between
 * non-printable bytes is not seen, and runs are ASCII-only, so a UTF-8 encoded non-ASCII literal is not
 * seen either. Lower the threshold or decode runs as UTF-8 if such a literal ever has to be caught.
 */
internal class PrintableRunsInputStream(source: InputStream) : FilterInputStream(source) {
    private var runOffsets = LongArray(INITIAL_RUNS)
    private var runCount = 0
    private val pending = ByteArray(MIN_RUN_LENGTH)
    private var pendingLength = 0
    private var pendingIndex = 0
    private var inRun = false
    private var runStart = 0L
    private var position = 0L

    override fun read(): Int {
        if (inRun) {
            if (pendingIndex < pendingLength) {
                return pending[pendingIndex++].toInt() and 0xFF
            }
            pendingLength = 0
            pendingIndex = 0
        }
        while (true) {
            val byte = `in`.read()
            if (byte < 0) {
                pendingLength = 0
                return if (inRun) {
                    inRun = false
                    LINE_FEED_BYTE
                } else {
                    -1
                }
            }
            position++
            if (byte == TAB_BYTE || byte in PRINTABLE_FIRST..PRINTABLE_LAST) {
                if (inRun) {
                    return byte
                }
                if (pendingLength == 0) {
                    runStart = position - 1
                }
                pending[pendingLength++] = byte.toByte()
                if (pendingLength == MIN_RUN_LENGTH) {
                    inRun = true
                    addRunOffset(runStart)
                    pendingIndex = 1
                    return pending[0].toInt() and 0xFF
                }
            } else {
                pendingLength = 0
                if (inRun) {
                    inRun = false
                    return LINE_FEED_BYTE
                }
            }
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        var read = 0
        while (read < length) {
            val byte = read()
            if (byte < 0) {
                break
            }
            buffer[offset + read++] = byte.toByte()
        }
        return if (read == 0 && length > 0) -1 else read
    }

    private fun addRunOffset(offset: Long) {
        if (runCount == runOffsets.size) {
            runOffsets = runOffsets.copyOf(runCount * 2)
        }
        runOffsets[runCount++] = offset
    }

    /**
     * Byte offset of [column] in the [line]-th run. Runs are only known once read, so call it after the
     * stream is consumed.
     */
    fun offsetOf(line: Int, column: Int): Long {
        if (line < 1 || line > runCount) {
            return -1
        }
        return runOffsets[line - 1] + column.coerceAtLeast(0)
    }

    fun asBinaryProblem(problem: ValidationProblem): ValidationProblem = problem.copy(
        line = -1,
        startPosition = -1,
        endPosition = -1,
        byteOffset = offsetOf(problem.line, problem.startPosition),
    )

    companion object {
        private const val MIN_RUN_LENGTH = 6
        private const val INITIAL_RUNS = 1024
        private const val TAB_BYTE = 0x09
        private const val LINE_FEED_BYTE = 0x0A
        private const val PRINTABLE_FIRST = 0x20
        private const val PRINTABLE_LAST = 0x7E
    }
}

/**
 * Tells text and binary content apart, by content and not by file name: the files this matters for
 * (compiled executables) often have no extension at all.
 */
internal object FileContent {
    /** Content that is not text. It is validated - as its printable runs rather than as lines. */
    fun isBinary(file: Path): Boolean = probe(file, BINARY_PROBE_SIZE).any { it == ZERO_BYTE }

    // a probe must never abort a validation run: an unreadable file is a file we could not classify,
    // not a reason to stop looking at the rest of the tree
    @Suppress("TooGenericExceptionCaught")
    private fun probe(file: Path, size: Int): ByteArray = try {
        val probe = ByteArray(size)
        var probed = 0
        file.inputStream().buffered().use { input ->
            var read = 0
            while (probed < size && read >= 0) {
                read = input.read(probe, probed, size - probed)
                probed += maxOf(read, 0)
            }
        }
        probe.copyOf(probed)
    } catch (ex: Throwable) {
        log.warn("Can't probe file=$file", ex)
        ByteArray(0)
    }

    private val log = LoggerFactory.getLogger(FileContent::class.java)

    private const val BINARY_PROBE_SIZE = 8192
    private const val ZERO_BYTE: Byte = 0
}
