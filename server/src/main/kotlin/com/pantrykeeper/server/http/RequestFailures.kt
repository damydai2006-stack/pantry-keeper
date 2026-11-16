package com.pantrykeeper.server.http

import org.slf4j.LoggerFactory

/**
 * Client mistakes are warnings. A real 500 keeps the stack, but the exception
 * message is replaced so a phone number or request body cannot reach the log.
 */
object RequestFailures {
    private val log = LoggerFactory.getLogger("pantry")
    private val mediaTypes = setOf(
        "ContentTransformationException",
        "UnsupportedMediaTypeException",
        "CannotTransformContentToTypeException",
    )
    private val clientTypes = setOf(
        "BadRequestException",
        "JsonConvertException",
        "SerializationException",
    )

    fun client(cause: Throwable) {
        log.warn("request failed: {}", cause.javaClass.name)
    }

    fun server(cause: Throwable) {
        log.error("request failed: {}", cause.javaClass.name, redacted(cause))
    }

    fun redacted(cause: Throwable): Throwable {
        val bare = RuntimeException(cause.javaClass.name)
        bare.stackTrace = cause.stackTrace
        return bare
    }

    fun isMediaType(cause: Throwable): Boolean = chain(cause).any { it.javaClass.simpleName in mediaTypes }

    fun isClient(cause: Throwable): Boolean = chain(cause).any { it.javaClass.simpleName in clientTypes }

    private fun chain(cause: Throwable): Sequence<Throwable> = sequence {
        var current: Throwable? = cause
        val seen = HashSet<Throwable>()
        while (current != null && seen.add(current)) {
            yield(current)
            current = current.cause
        }
    }
}
