package org.octopusden.octopus.components.automation.task

import org.octopusden.octopus.components.automation.task.TextTokenHandler.Companion.PLACEHOLDER
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.regex.Matcher
import java.util.regex.Pattern

internal class TextTokenHandlerTest {

    private val exceptions = listOf("brand2u", "brand2they")
    @Test
    fun testTokenAgainstRules() {
        val textTokenHandler = TextTokenHandler(listOf(
            FileValidationRule("brand2all", "sonic"),
            FileValidationRule("brand2me", "nuken")
        ), exceptions)
        val validationProblem =
            textTokenHandler.testTokenAgainstRules("org.octopusden.octopus.system.brand2all", 1, 1, 10)
        assertNotNull(validationProblem)
        assertEquals("org.octopusden.octopus.system.sonic", validationProblem?.suggestedReplacement)


        val validationProblem2 =
            textTokenHandler.testTokenAgainstRules("org.octopusden.octopus.brand2me.zenit.brand2all", 1, 1, 10)
        assertNotNull(validationProblem)
        //TODO (single replacement only)!
        assertEquals("org.octopusden.octopus.brand2me.zenit.sonic", validationProblem2?.suggestedReplacement)

    }

    @Test
    fun testRegex() {
        val matcher: Matcher = Pattern.compile("([bB][rR][aA][nN][dD]2[uU])|([bB][rR][aA][nN][dD]2[tT][hH][eE][yY])")
            .matcher("org.octopusden.octopus.brand2U.zenit.BRAND2U")
        val matches: MutableList<String> = ArrayList()
        while (matcher.find()) {
            matches.add(matcher.group(1))
        }
        assertEquals(2, matches.size)
    }

    @Test
    fun testFold() {
        val value = exceptions.fold("BRAND2U zenit brand2u aabd Brand2u ".lowercase()) { result, element ->
            result.replace(
                element,
                PLACEHOLDER
            )
        }
        println(value)
        assertFalse(value.lowercase().contains("brand2"))
    }

    @Test
    fun testExcludeBRAND2U() {
        val textTokenHandler = TextTokenHandler(listOf(
            FileValidationRule("brand2all", "sonic"),
            FileValidationRule("brand2", "b"),
            FileValidationRule("BRAND2", "B"),
            FileValidationRule("Brand2", "b"),
            FileValidationRule("branD2", "b")
        ), exceptions)
        val validationProblem =
            textTokenHandler.testTokenAgainstRules("org.octopusden.octopus.brand2u.zenit.brand2all", 1, 1, 10)
        assertNotNull(validationProblem)
        assertEquals("org.octopusden.octopus.brand2u.zenit.sonic", validationProblem?.suggestedReplacement)

        assertNoProblem(textTokenHandler, "org.octopusden.octopus.brand2u.zenit")
        assertNoProblem(textTokenHandler, "org.octopusden.octopus.BRAND2U.zenit")
        assertNoProblem(textTokenHandler, "org.octopusden.octopus.branD2u.zenit")
    }

    private fun assertNoProblem(
        textTokenHandler: TextTokenHandler,
        token: String
    ) {
        val validationProblem2 =
            textTokenHandler.testTokenAgainstRules(token, 1, 1, 10)
        assertNull(validationProblem2)
    }

    @Test
    fun testShouldNotMatchRuleInsideAnotherToken() {
        val textTokenHandler = TextTokenHandler(
            listOf(FileValidationRule("OWS_", "OWS_")),
            emptyList()
        )
        val validationProblem =
            textTokenHandler.testTokenAgainstRules("REQUIRED_WORKFLOWS_JSON", 1, 1, 24)
        assertNull(validationProblem)
    }

    @Test
    fun testShouldMatchStandaloneTokenRule() {
        val textTokenHandler = TextTokenHandler(
            listOf(FileValidationRule("OWS_", "OWS_")),
            emptyList()
        )
        val validationProblem =
            textTokenHandler.testTokenAgainstRules("OWS_", 1, 1, 4)
        assertNotNull(validationProblem)
        assertEquals("OWS_", validationProblem?.suggestedReplacement)
    }

    @Test
    fun testShouldNotMatchWordRuleInsideAnotherWord() {
        val textTokenHandler = TextTokenHandler(
            listOf(FileValidationRule("issuing", "issuing")),
            emptyList()
        )
        val validationProblem =
            textTokenHandler.testTokenAgainstRules("reissuing", 1, 1, 9)
        assertNull(validationProblem)
    }

    @Test
    fun testShouldMatchStandaloneWordRule() {
        val textTokenHandler = TextTokenHandler(
            listOf(FileValidationRule("issuing", "issuing")),
            emptyList()
        )
        val validationProblem =
            textTokenHandler.testTokenAgainstRules("issuing", 1, 1, 7)
        assertNotNull(validationProblem)
        assertEquals("issuing", validationProblem?.suggestedReplacement)
    }

    @Test
    fun testShouldKeepSubstringMatchingForAlphaNumericRule() {
        val textTokenHandler = TextTokenHandler(
            listOf(FileValidationRule("brand2", "b")),
            emptyList()
        )
        val validationProblem =
            textTokenHandler.testTokenAgainstRules("brand2maps", 1, 1, 10)
        assertNotNull(validationProblem)
        assertEquals("bmaps", validationProblem?.suggestedReplacement)
    }
}
