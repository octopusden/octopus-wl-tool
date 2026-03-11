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
            if (containsBoundedRule(tokenWithPlaceholder, rule.rule)) {
                val suggestedReplacement = replaceBoundedRule(
                    tokenWithPlaceholder,
                    rule.rule,
                    rule.suggestedReplacement
                ).replaceBack(replacer)
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

    private fun containsBoundedRule(token: String, rule: String): Boolean {
        if (rule.isEmpty()) {
            return false
        }

        var index = token.indexOf(rule)
        while (index >= 0) {
            val endExclusive = index + rule.length
            if (isBoundary(token, index - 1) && isBoundary(token, endExclusive)) {
                return true
            }
            index = token.indexOf(rule, index + 1)
        }
        return false
    }

    private fun replaceBoundedRule(token: String, rule: String, replacement: String): String {
        if (rule.isEmpty()) {
            return token
        }

        val sb = StringBuilder()
        var cursor = 0
        var index = token.indexOf(rule)
        while (index >= 0) {
            val endExclusive = index + rule.length
            sb.append(token, cursor, index)
            if (isBoundary(token, index - 1) && isBoundary(token, endExclusive)) {
                sb.append(replacement)
            } else {
                sb.append(token, index, endExclusive)
            }
            cursor = endExclusive
            index = token.indexOf(rule, cursor)
        }
        sb.append(token, cursor, token.length)
        return sb.toString()
    }

    private fun isBoundary(token: String, index: Int): Boolean {
        if (index < 0 || index >= token.length) {
            return true
        }
        return !token[index].isLetterOrDigit()
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
