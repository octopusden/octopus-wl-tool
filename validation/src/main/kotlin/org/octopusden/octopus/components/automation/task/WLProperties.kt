package org.octopusden.octopus.components.automation.task

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.octopusden.octopus.tools.wl.validation.validator.CopyrightValidator

@JsonIgnoreProperties(ignoreUnknown = true)
class WLProperties(
    @JsonProperty("contains")
    wContains: List<String>,
    @JsonProperty("patterns")
    wPatterns: List<Regex>,
    val exceptions: List<String>,
    val restricted: String
) : CopyrightValidator.CopyrightProperties(wContains, wPatterns)
