package org.octopusden.octopus.components.automation.task

import org.octopusden.octopus.tools.wl.PatternCalculator
import org.slf4j.LoggerFactory
import java.util.regex.Matcher
import java.util.regex.Pattern

class TextTokenHandler(private val validationRules: List<FileValidationRule>, private val exceptions: List<String>) {
    private val pattern: Pattern = Pattern.compile(PatternCalculator().calculate(exceptions))

    fun testTokenAgainstRules(
        token: String,
        line: Int,
        startPos: Int,
        endPos: Int,
    ): ValidationProblem? {
        val replacer = Replacer(pattern, token)
        val tokenWithPlaceholder = replacer.replace()
        for (rule in validationRules) {
            if (tokenWithPlaceholder.contains(rule.rule)) {
                val suggestedReplacement = token.replace(rule.rule, rule.suggestedReplacement).replaceBack(replacer)
                return ValidationProblem(
                    line,
                    startPos,
                    endPos,
                    "",
                    token,
                    rule.rule,
                    suggestedReplacement
                )
            }
        }
        return null
    }

    class Replacer(val pattern: Pattern, val token: String) {
        val matches = mutableListOf<String>()

        fun replace(): String {
            val matcher: Matcher = pattern.matcher(token)
            while (matcher.find()) {
                for (i in  0.. matcher.groupCount())  {
                    val element = matcher.group(i)
                    if (element != null) {
                        logger.debug("Adding for replacing ${i}th $element")
                        matches.add(element)
                    }
                }
            }
            var replacingToken = token
            matches.forEach { replacingToken = replacingToken.replaceFirst(it, PLACEHOLDER) }
            return replacingToken
        }

        fun replaceBack(token: String): String {
            var tokengReplacing = token
            matches.forEach {
                tokengReplacing = tokengReplacing.replaceFirst(PLACEHOLDER, it)
            }
            return tokengReplacing
        }

    }

    companion object {
        const val PLACEHOLDER = "$#WL_PLACEHOLDER#$"
        private val logger = LoggerFactory.getLogger(TextTokenHandler::class.java)
    }

}

private fun String.replaceBack(replacer: TextTokenHandler.Replacer): String {
    return replacer.replaceBack(this)
}
