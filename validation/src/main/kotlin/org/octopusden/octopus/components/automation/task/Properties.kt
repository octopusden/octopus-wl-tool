package org.octopusden.octopus.components.automation.task

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class Properties(
    val contains: List<String>,
    val patterns: List<Regex>,
    val exceptions: List<String>,
    val restricted: String
)