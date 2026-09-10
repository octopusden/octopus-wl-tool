package org.octopusden.octopus.tools.wl.validation.validator

import org.octopusden.octopus.components.automation.task.ValidationProblem
import org.octopusden.octopus.components.automation.task.withContext
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class CopyrightValidator @JvmOverloads constructor(
    private val contains: List<String>,
    patterns: List<Regex>,
    private val stringValidationTimeoutSec: Long = STRING_VALIDATION_TIMEOUT_SEC_DEFAULT,
    private val threadCount: Int = THREAD_COUNT_DEFAULT,
) {
    /**
     * Matched with surrounding `.*` dropped, reported as configured. That `.*` makes the match group cover
     * the whole line, so the reported excerpt shows the beginning of the line instead of the hit - fatal on
     * a binary, where a "line" is tens of kilobytes. Dropping it does not change whether [Matcher.find]
     * matches, only where; the configured text is kept so a report entry stays greppable in the config.
     */
    private val patterns = patterns.map { narrowToMatch(it) to it.pattern }

    /**
     * Per instance, not per [validate] call: a caller that validates many small inputs - every entry of
     * an archive, every file of a source tree - otherwise creates two thread pools per input and never
     * awaits their termination.
     *
     * Idle threads time out and core threads are not exempt, so the pools shrink back to nothing on
     * their own and the class needs no lifecycle in its public API. A fixed pool would not: this runs
     * inside the Gradle daemon, which outlives the build, and every build constructs a new validator -
     * its threads would accumulate there for as long as the daemon lives. Daemon threads only keep the
     * JVM from being held open; they do nothing about piling up inside one that stays.
     */
    private val taskPool = daemonPool("wl-copyright-task")
    private val timeoutPool = daemonPool("wl-copyright-timeout")

    fun validate(content: InputStream): List<ValidationProblem> {
        // an empty pattern list is how the configuration says "no copyright validation"; without this the
        // whole input is still read and every line dispatched to the pools, to match against nothing
        if (patterns.isEmpty()) {
            return emptyList()
        }
        // the stream belongs to the caller: closing it forced consumers to copy every input to a temp file
        val bufferedReader = content.bufferedReader()

        val permits = threadCount * 5
        val semaphore = Semaphore(permits, true)
        log.trace("Thread Pool size $threadCount")

        val containsPhrases = contains.joinToString()
        val errors = mutableListOf<ValidationProblem>()
        var lineNumber = 0
        var line = bufferedReader.readLine()

        while (line != null) {
            lineNumber += 1

            log.debug("Check line for contains.any($containsPhrases), line $lineNumber")
            if (contains.isEmpty() || contains.any { line.contains(it, true) }) {
                log.debug("Submit validation, line $lineNumber")
                semaphore.acquire()
                submit(semaphore, lineNumber, line, errors)
            }

            line = bufferedReader.readLine()
        }

        while (semaphore.availablePermits() < permits) {
            log.trace("Wait for shutdown")
            TimeUnit.MILLISECONDS.sleep(100)
        }

        log.info("Scanned $lineNumber strings")
        // a copy under the same lock the appenders take: the permit is released as soon as a task is
        // cancelled, so a task that timed out can still be inside its finally when this returns, and
        // handing out the live list risks a ConcurrentModificationException in the caller. It does not
        // make the straggler's finding appear here - a line that timed out is already given up on, and
        // holding the permit until the task really ended would let one hung line stall the file.
        return synchronized(errors) { errors.toList() }
    }

    private fun daemonPool(name: String) = ThreadPoolExecutor(
        threadCount,
        threadCount,
        IDLE_THREAD_TIMEOUT_SEC,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(),
    ) { runnable ->
        Thread(runnable, name).apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }

    private fun submit(semaphore: Semaphore, nLine: Int, string: String, errors: MutableList<ValidationProblem>) {
        timeoutPool.submit {
            val start = CountDownLatch(1)
            val future = submit(start, nLine, string, errors)
            start.await()

            try {
                future.get(stringValidationTimeoutSec, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                log.debug("Validation timeout, line $nLine")
            } finally {
                if (future.cancel(true)) {
                    log.trace("Validation canceled, line: $nLine")
                } else {
                    log.trace("Validation already finished, line: $nLine")
                }
                semaphore.release()
            }
        }
    }

    private fun submit(start: CountDownLatch, nLine: Int, string: String, errors: MutableList<ValidationProblem>) = taskPool.submit {
        start.countDown()
        var validationProblem: ValidationProblem? = null
        try {
            log.debug("Start validation, line: $nLine")
            patterns
                .firstNotNullOfOrNull { (regex, configuredPattern) ->
                    val matcher = regex.toPattern()
                        .matcher(InterruptibleCharSequence(string))

                    if (matcher.find()) {
                        configuredPattern to matcher.toMatchResult()
                    } else {
                        null
                    }
                }?.let { (configuredPattern, matchResult) ->
                    val wrongString = matchResult.group()
                    log.debug("Validation error, line: $nLine")
                    val problemToken = wrongString.shortString()
                    validationProblem = ValidationProblem(
                        nLine,
                        matchResult.start(),
                        matchResult.end(),
                        configuredPattern,
                        problemToken,
                        problemToken,
                        "",
                        context = string.withContext(matchResult.start(), matchResult.end()),
                    )
                }
        } catch (e: InterruptibleCharSequence.InterruptedRuntimeException) {
            log.warn("Validation interrupted, line $nLine")
            /*
            validationProblem = ValidationProblem(
                nLine, 0, string.length - 1,
                "", string, "Validation interrupted by timeout", ""
            )
             */
        } finally {
            validationProblem?.let { validationProblemValue ->
                log.trace("Add validation problem: $validationProblemValue")
                synchronized(errors) {
                    errors.add(
                        validationProblemValue,
                    )
                }
            }
            log.debug("String validation completed, line: $nLine")
        }
    }

    private fun String.shortString(): String {
        return if (this.length <= VALIDATION_TOKEN_LENGTH) {
            return this
        } else {
            "${this.substring(0, VALIDATION_TOKEN_LENGTH - 3)}..."
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(CopyrightValidator::class.java)
        private val FLAG_PREFIX = Regex("^\\(\\?[a-zA-Z]+\\)")

        private fun narrowToMatch(regex: Regex): Regex {
            val flags = FLAG_PREFIX.find(regex.pattern)?.value ?: ""
            var body = regex.pattern.removePrefix(flags)
            if (body.startsWith(".*")) {
                body = body.substring(2)
            }
            val escapedDot = body.dropLast(2).takeLastWhile { it == '\\' }.length % 2 == 1
            if (body.endsWith(".*") && !escapedDot) {
                body = body.dropLast(2)
            }
            return try {
                if (body.isEmpty()) regex else Regex(flags + body, regex.options)
            } catch (ex: Exception) {
                log.warn("Can't narrow pattern=${regex.pattern}, using it as is", ex)
                regex
            }
        }

        private const val IDLE_THREAD_TIMEOUT_SEC: Long = 30
        private const val STRING_VALIDATION_TIMEOUT_SEC_DEFAULT: Long = 30
        private const val THREAD_COUNT_DEFAULT = 20
        private const val VALIDATION_TOKEN_LENGTH = 80
    }
}
