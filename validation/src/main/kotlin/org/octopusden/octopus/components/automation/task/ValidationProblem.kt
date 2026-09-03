package org.octopusden.octopus.components.automation.task

data class ValidationProblem(
    val line: Int,
    val startPosition: Int,
    val endPosition: Int,
    val brokenRegex: String,
    val problemToken: String,
    val validationProblem: String,
    val suggestedReplacement: String,
    /**
     * Byte offset of the problem in the source file, -1 when unknown.
     * Binary files have no meaningful lines, so the offset is the only usable position there.
     */
    val byteOffset: Long = -1,
    val binary: Boolean = false,
    /** [problemToken] surrounded by a bit of the text it was found in, empty when that text is unknown. */
    val context: String = ""
)

private const val CONTEXT_LENGTH = 15
private const val MAX_MATCH_LENGTH = 80
private const val ELLIPSIS = "..."

/**
 * The matched fragment plus a bit of what surrounds it: enough to recognize what was found, short enough
 * to keep a report entry readable when the match itself is a chunk of a binary.
 */
fun String.withContext(start: Int, end: Int): String {
    val matchStart = start.coerceIn(0, length)
    val matchEnd = end.coerceIn(matchStart, length)
    val from = (matchStart - CONTEXT_LENGTH).coerceAtLeast(0)
    val to = (matchEnd + CONTEXT_LENGTH).coerceAtMost(length)
    val match = substring(matchStart, matchEnd).let {
        if (it.length <= MAX_MATCH_LENGTH) it else it.take(MAX_MATCH_LENGTH - ELLIPSIS.length) + ELLIPSIS
    }
    return (if (from > 0) ELLIPSIS else "") + substring(from, matchStart) +
            match + substring(matchEnd, to) + (if (to < length) ELLIPSIS else "")
}
