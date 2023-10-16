package org.octopusden.octopus.util

data class FileContentFilterConfig(
    val applyToFiles: List<String>,
    val excludeByContent: List<String>
)
