package org.octopusden.octopus.components.automation

/**
 * NOT PRODUCTION CODE. Deliberately defective, to observe what a red SonarCloud quality gate does
 * to a pull request. Delete this file; never merge it.
 */
object SonarGateProbe {

    fun alwaysTrue(value: Int): Boolean = value == value

    fun weakToken(): Int = java.util.Random().nextInt()
}
