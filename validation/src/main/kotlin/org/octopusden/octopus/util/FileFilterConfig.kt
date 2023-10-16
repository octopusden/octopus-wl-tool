package org.octopusden.octopus.util

data class FileFilterConfig(
    val includeDirs: List<String> = emptyList(),
    val excludeDirs: List<String> = emptyList(),
    val includeFiles: List<String> = emptyList(),
    val excludeFiles: List<String> = emptyList(),
    val excludeFileContentFilters: List<FileContentFilterConfig> = emptyList(),
)
