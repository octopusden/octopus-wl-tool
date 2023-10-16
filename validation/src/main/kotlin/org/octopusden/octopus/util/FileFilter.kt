package org.octopusden.octopus.util

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.Paths
import java.util.stream.Stream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.io.path.inputStream
import kotlin.streams.toList

class FileFilter private constructor() {

    companion object {
        private const val WL_IGNORE_NAME = ".wlignore.json"
        private val log = LoggerFactory.getLogger(FileFilter::class.java)
        private val mapper = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
        private val zipSignatures = listOf("50 4B 03 04", "50 4B 05 06", "50 4B 07 08")

        @JvmStatic
        fun isZipFile(path: Path): Boolean {
            return isZipFile(Files.newInputStream(path))
        }

        @JvmStatic
        fun isZipFile(inputStream: InputStream): Boolean {
            inputStream.use { s ->
                val signature = StringBuilder()
                var i = 0
                while (i < 8 && s.available() > 0) {
                    signature.append(String.format("%02x ", s.read()))
                    i++
                }
                val hexSignature = signature.toString().trim { it <= ' ' }.uppercase()

                return zipSignatures.any {
                    hexSignature.contains(it)
                }
            }
        }

        @JvmStatic
        fun filter(globalConfig: FileFilterConfig, zipFile: ZipFile): Pair<List<ZipEntry>, List<ZipEntry>> {
            val pathZipEntryMap = zipFile.entries()
                .toList()
                .associateBy { Paths.get(it.name) }

            val (declined, accepted) = filter(
                globalConfig,
                { pathZipEntryMap.keys.stream() },
                { path -> path },
                { path ->
                    pathZipEntryMap[path]?.let { entry -> zipFile.getInputStream(entry) }
                        ?: throw IllegalStateException()
                },
                { path -> pathZipEntryMap[path]?.isDirectory ?: throw IllegalStateException() }
            )
            return declined.mapNotNull { pathZipEntryMap[it] } to accepted.mapNotNull { pathZipEntryMap[it] }
        }

        @JvmStatic
        fun filter(globalConfig: Path, sourceRoot: Path): Pair<List<Path>, List<Path>> {
            val config = loadConfigurations(globalConfig, sourceRoot)
            return filter(config, sourceRoot)
        }

        /**
         * return Pair<List<DeclinedFiles>,List<AcceptedFiles>>
         */
        @JvmStatic
        fun filter(
            config: FileFilterConfig,
            start: Path
        ): Pair<List<Path>, List<Path>> {
            return filter(
                config,
                { Files.walk(start) },
                { path -> start.relativize(path) },
                { path -> path.inputStream() },
                { path -> Files.isDirectory(path) }
            )
        }

        private fun filter(
            config: FileFilterConfig,
            pathStreamFunc: () -> Stream<Path>,
            pathRelativizeFunc: (Path) -> Path,
            extractDataFunc: (Path) -> InputStream,
            isDirectoryFunc: (Path) -> Boolean
        ): Pair<List<Path>, List<Path>> {

            log.info("Start validation, full FileFilterConfig:$config")
            val directoryFilter = prepareDirectoryFilter(config)
            val filesFilter = prepareFileFilter(config)
            val fileContentFilter =
                prepareFileContentFilter(config, extractDataFunc)

            val declinedFiles = mutableListOf<Path>()

            val acceptedFiles = pathStreamFunc.invoke().use { pathStream ->
                pathStream.filter { path -> !isDirectoryFunc(path) }
                    .filter { path ->
                        directoryFilter.invoke(path)
                            .also { accept ->
                                if (!accept) {
                                    declinedFiles.add(path)
                                }
                            }
                    }
                    .filter { path ->
                        filesFilter.invoke(pathRelativizeFunc.invoke(path))
                            .also { accept ->
                                if (!accept) {
                                    declinedFiles.add(path)
                                }
                            }
                    }
                    .filter { path ->
                        fileContentFilter.invoke(path)
                            .also { accept ->
                                if (!accept) {
                                    declinedFiles.add(path)
                                }
                            }
                    }.toList()
            }
            return declinedFiles to acceptedFiles
        }

        @JvmStatic
        internal fun prepareFileContentFilter(
            fileFilterConfig: FileFilterConfig,
            extractDataFunc: (Path) -> InputStream
        ): (Path) -> Boolean {
            val filters = fileFilterConfig.excludeFileContentFilters.map {
                val includeMask = it.applyToFiles.map(::prependGlob)
                getFileFilter(includeMask, emptyList()) to it.excludeByContent
            }

            val r = { p: Path ->
                if (filters.isEmpty()) {
                    true
                } else {
                    // allow file only if all filters pass
                    filters.all { filter ->
                        if (filter.first.invoke(p)) {
                            // file matching mask
                            extractDataFunc(p).bufferedReader()
                                .use { bufferedReader ->
                                    try {
                                        // return true only if none fileLines contains restricted lines
                                        bufferedReader.lineSequence()
                                            .none { line ->
                                                filter.second.any { lineToExclude ->
                                                    line.contains(lineToExclude, true)
                                                }
                                            }
                                    } catch (ex: Throwable) {
                                        true
                                    }
                                }
                        } else {
                            // file does not match mask
                            true
                        }
                    }
                }
            }
            return r
        }

        @JvmStatic
        internal fun prepareDirectoryFilter(fileFilterConfig: FileFilterConfig): (Path) -> Boolean {
            val includeDirs = fileFilterConfig.includeDirs.map(::prependGlob)
            val excludeDirs = fileFilterConfig.excludeDirs.map(::prependGlob)
            return getDirectoryFilter(includeDirs, excludeDirs)
        }

        @JvmStatic
        internal fun prepareFileFilter(fileFilterConfig: FileFilterConfig): (Path) -> Boolean {
            val includeFiles = fileFilterConfig.includeFiles.map(::prependGlob)

            val backCompRegex = "[^/]*".toRegex()
            val excludeFiles = fileFilterConfig.excludeFiles
                .flatMap { f ->
                    // backward compatibility RELENG-2118
                    if (backCompRegex.matches(f)) {
                        listOf(f, "**/$f")
                    } else {
                        listOf(f)
                    }
                }.map(::prependGlob)
            return getFileFilter(includeFiles, excludeFiles)
        }

        private fun loadConfigurations(globalConfig: Path, localConfig: Path): FileFilterConfig {

            val global = mapper.readValue(globalConfig.toFile(), FileFilterConfig::class.java)
            val localConfigPath = localConfig.resolve(WL_IGNORE_NAME)

            return if (Files.exists(localConfigPath) && Files.size(localConfigPath) > 0) {
                mergeConfigs(global, mapper.readValue(localConfigPath.toFile(), FileFilterConfig::class.java))
            } else {
                global
            }
        }

        @JvmStatic
        fun mergeConfigs(
            globalConfig: FileFilterConfig,
            localConfig: FileFilterConfig?
        ): FileFilterConfig {
            log.info("Merge global=$globalConfig\nwith local=$localConfig")
            return localConfig?.let { localConfigValue ->
                if (localConfigValue.includeDirs.isNotEmpty() || localConfigValue.includeFiles.isNotEmpty()) {
                    log.warn("Local includeDirs, includeFiles will be ignored")
                }
                FileFilterConfig(
                    includeDirs = globalConfig.includeDirs,
                    excludeDirs = (localConfigValue.excludeDirs + globalConfig.excludeDirs).distinct(),
                    includeFiles = globalConfig.includeFiles,
                    excludeFiles = (localConfigValue.excludeFiles + globalConfig.excludeFiles).distinct(),
                    excludeFileContentFilters = (localConfigValue.excludeFileContentFilters + globalConfig.excludeFileContentFilters).distinct()
                )
            } ?: globalConfig
        }

        private fun getDirectoryFilter(include: List<String>, exclude: List<String>): (Path) -> Boolean {
            val includeFilter = toMatchers(include)
            val excludeFilter = toMatchers(exclude)
            return { p: Path ->
                includeFilter.any { m -> m.matches(p) } &&
                        excludeFilter.none { m -> m.matches(p) }
            }
        }

        private fun getFileFilter(includeFiles: List<String>, excludeFiles: List<String>): (Path) -> Boolean {
            val includeFilter = toMatchers(includeFiles)
            val excludeFilter = toMatchers(excludeFiles)

            return { p: Path ->
                includeFilter.any { m -> m.matches(p.fileName) } &&
                        excludeFilter.none { m -> m.matches(p) }
            }
        }

        private fun toMatchers(patterns: List<String>): List<PathMatcher> {
            return patterns.map(FileSystems.getDefault()::getPathMatcher)
        }

        private fun prependGlob(pattern: String): String {
            return "glob:$pattern"
        }
    }
}
