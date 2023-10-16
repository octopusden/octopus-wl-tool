package org.octopusden.octopus.components.automation.task

data class ValidationProblem(
    val line: Int,
    val startPosition: Int,
    val endPosition: Int,
    val brokenRegex: String,
    val problemToken: String,
    val validationProblem: String,
    val suggestedReplacement: String
)