package org.octopusden.octopus.tools.wl

import kotlin.streams.toList

class PatternCalculator {

    fun calculate(items: List<String>): String {
        return items.joinToString("|") { it ->
            it.chars().mapToObj { it.toChar() }.toList()
                .joinToString("", "(", ")") { c ->
                    if (c.isLetter()) {
                        "[${c.lowercase()}${c.uppercase()}]"
                    } else {
                        c.toString()
                    }
                }
        }
    }

}