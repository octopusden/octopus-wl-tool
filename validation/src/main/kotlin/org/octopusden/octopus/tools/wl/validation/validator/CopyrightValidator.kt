package org.octopusden.octopus.tools.wl.validation.validator

import org.octopusden.octopus.components.automation.task.ValidationProblem
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class CopyrightValidator @JvmOverloads constructor(
    private val properties: Properties,
    private val stringValidationTimeoutSec: Long = STRING_VALIDATION_TIMEOUT_SEC_DEFAULT,
    private val threadCount: Int = THREAD_COUNT_DEFAULT
) {

    fun validate(content: InputStream): List<ValidationProblem> {
        return content.bufferedReader().use { bufferedReader ->

            val taskPool = Executors.newFixedThreadPool(threadCount) as ThreadPoolExecutor
            val timeoutPool = Executors.newFixedThreadPool(threadCount) as ThreadPoolExecutor
            val permits = threadCount * 5
            val semaphore = Semaphore(permits, true)
            log.trace("Thread Pool size $threadCount")

            val containsPhrases = properties.contains.joinToString()
            val errors = mutableListOf<ValidationProblem>()
            var lineNumber = 0
            var line = bufferedReader.readLine()

            while (line != null) {
                lineNumber += 1

                log.debug("Check line for contains.any($containsPhrases), line $lineNumber")
                if (properties.contains.isEmpty() || properties.contains.any { line.contains(it, true) }) {
                    log.debug("Submit validation, line $lineNumber")
                    semaphore.acquire()
                    submit(timeoutPool, taskPool, semaphore, lineNumber, line, errors)
                }

                line = bufferedReader.readLine()
            }

            while (semaphore.availablePermits() < permits) {
                log.trace("Wait for shutdown")
                TimeUnit.MILLISECONDS.sleep(100)
            }

            timeoutPool.shutdown()
            taskPool.shutdown()

            log.info("Scanned $lineNumber strings")
            errors
        }
    }

    private fun submit(
        timeoutPool: ThreadPoolExecutor,
        taskPool: ThreadPoolExecutor,
        semaphore: Semaphore,
        nLine: Int,
        string: String,
        errors: MutableList<ValidationProblem>
    ) {
        timeoutPool.submit {
            val start = CountDownLatch(1)
            val future = submit(taskPool, start, nLine, string, errors)
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

    private fun submit(
        taskPool: ThreadPoolExecutor,
        start: CountDownLatch,
        nLine: Int,
        string: String,
        errors: MutableList<ValidationProblem>
    ) = taskPool.submit {
        start.countDown()
        var validationProblem: ValidationProblem? = null
        try {
            log.debug("Start validation, line: $nLine")
            properties.patterns
                .firstNotNullOfOrNull { regex ->
                    val matcher = regex.toPattern()
                        .matcher(InterruptibleCharSequence(string))

                    if (matcher.find()) {
                        regex to matcher.toMatchResult()
                    } else {
                        null
                    }
                }?.let { (regex, matchResult) ->
                    val wrongString = matchResult.group()
                    log.debug("Validation error, line: $nLine")
                    val problemToken = wrongString.shortString()
                    validationProblem = ValidationProblem(
                        nLine, matchResult.start(), matchResult.end(),
                        regex.pattern, problemToken, problemToken, ""
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
                        validationProblemValue
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Properties(val contains: List<String>, val patterns: List<Regex>)
    companion object {
        private val log = LoggerFactory.getLogger(CopyrightValidator::class.java)
        private const val STRING_VALIDATION_TIMEOUT_SEC_DEFAULT: Long = 30
        private const val THREAD_COUNT_DEFAULT = 20
        private const val VALIDATION_TOKEN_LENGTH = 80
    }
}
