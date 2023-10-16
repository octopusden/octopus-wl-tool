package org.octopusden.octopus.tools.wl

import org.junit.jupiter.api.Test

import org.junit.jupiter.api.Assertions.*

internal class PatternCalculatorTest {

    private val exceptions = listOf("brand2u", "brand2they")

    @Test
    fun testCalculate() {
        val patternCalculator = PatternCalculator()
        assertEquals("([zZ][eE][nN][iI][tT])", patternCalculator.calculate(listOf("Zenit")))
        assertEquals("([aA]4)", patternCalculator.calculate(listOf("A4")))
        assertEquals("([bB][rR][aA][nN][dD]2[uU])|([bB][rR][aA][nN][dD]2[tT][hH][eE][yY])", patternCalculator.calculate(exceptions))
    }
}