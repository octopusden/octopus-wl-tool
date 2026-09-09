package org.octopusden.octopus.components.automation.task

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class WLReportGeneratorTest {

    /**
     * The report must not cap below what [withContext] can produce, or a long match loses the context that
     * was added to make it recognizable, and gets truncated twice.
     */
    @Test
    fun `a report entry keeps the whole excerpt withContext can produce`() {
        val line = "before".repeat(10) + "M".repeat(60) + "after".repeat(10)
        val context = line.withContext(60, 120)
        val problem = ValidationProblem(
            1, 60, 120, "", "token", "rule", "", byteOffset = 4096, context = context,
        )

        val record = WLReportGenerator.record("bin/tool", problem)

        assertTrue(
            context.length <= MAX_EXCERPT_LENGTH,
            "withContext produced ${context.length} chars, over the $MAX_EXCERPT_LENGTH budget: $context",
        )
        assertTrue(record.contains(context), "Entry must quote the excerpt whole, was: $record")
    }
}
