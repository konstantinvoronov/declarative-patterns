/*
 * Mandatory failure rule:
 *
 * KmpFailure is obligatory for all expected project-level failures in KMP/platform project logic.
 * Native errors may exist only as local implementation details.
 *
 * Before a failure crosses a project, SDK, module, repository, service, adapter, transport,
 * logging, diagnostics, or app-facing result boundary, convert it to KmpFailure and return
 * KmpResult.Failure.
 *
 * Local native no-op/idempotency diagnostics do not require KmpFailure when no project
 * operation failed and nothing crosses a boundary.
 */

package com.example.failure

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

@Serializable
sealed class KmpResult<out T> {

    @Serializable
    data class Success<T>(
        val value: T,
    ) : KmpResult<T>()

    @Serializable
    data class Failure(
        val failure: KmpFailure,
    ) : KmpResult<Nothing>()
}

@Serializable
enum class KmpResultSwiftStatus {
    SUCCESS,
    FAILURE,
}

@Serializable
data class KmpResultSwift<out T>(
    val status: KmpResultSwiftStatus,
    val value: T? = null,
    val failure: KmpFailure? = null,
)

fun <T> KmpResult<T>.asKmpResultSwift(): KmpResultSwift<T> {
    return when (this) {
        is KmpResult.Success -> KmpResultSwift(
            status = KmpResultSwiftStatus.SUCCESS,
            value = value,
            failure = null,
        )

        is KmpResult.Failure -> KmpResultSwift(
            status = KmpResultSwiftStatus.FAILURE,
            value = null,
            failure = failure,
        )
    }
}

@Serializable
data class KmpFailure(
    val message: String,
    val code: String? = null,
    val retryable: Boolean = false,
    val cause: KmpFailure? = null,
    val source: String? = null,
    val origin: KmpFailureOrigin = KmpFailureOrigin.COMMON,
    val nativeType: String? = null,
    val debugInfo: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
enum class KmpFailureOrigin {
    COMMON,
    ANDROID,
    IOS,
    MACOS,
    WINDOWS,
    JVM,
    UNKNOWN,
}

class KmpFailureException(
    val failure: KmpFailure,
) : RuntimeException(failure.message)

fun KmpFailure.toException(): KmpFailureException {
    return KmpFailureException(this)
}

inline fun <T> kmpRunCatching(
    block: () -> T,
): KmpResult<T> {
    return try {
        KmpResult.Success(block())
    } catch (throwable: Throwable) {
        KmpResult.Failure(throwable.toKmpFailure())
    }
}

suspend inline fun <T> kmpSuspendRunCatching(
    crossinline block: suspend () -> T,
): KmpResult<T> {
    return try {
        KmpResult.Success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        KmpResult.Failure(throwable.toKmpFailure())
    }
}

inline fun <T, R> KmpResult<T>.map(
    transform: (T) -> R,
): KmpResult<R> {
    return when (this) {
        is KmpResult.Success -> KmpResult.Success(transform(value))
        is KmpResult.Failure -> this
    }
}

inline fun <T, R> KmpResult<T>.mapCatching(
    transform: (T) -> R,
): KmpResult<R> {
    return when (this) {
        is KmpResult.Success -> kmpRunCatching {
            transform(value)
        }

        is KmpResult.Failure -> this
    }
}

inline fun <T, R> KmpResult<T>.flatMap(
    transform: (T) -> KmpResult<R>,
): KmpResult<R> {
    return when (this) {
        is KmpResult.Success -> transform(value)
        is KmpResult.Failure -> this
    }
}

inline fun <T> KmpResult<T>.mapFailure(
    transform: (KmpFailure) -> KmpFailure,
): KmpResult<T> {
    return when (this) {
        is KmpResult.Success -> this
        is KmpResult.Failure -> KmpResult.Failure(transform(failure))
    }
}

inline fun <T> KmpResult<T>.recover(
    transform: (KmpFailure) -> T,
): KmpResult<T> {
    return when (this) {
        is KmpResult.Success -> this
        is KmpResult.Failure -> KmpResult.Success(transform(failure))
    }
}

inline fun <T> KmpResult<T>.recoverCatching(
    transform: (KmpFailure) -> T,
): KmpResult<T> {
    return when (this) {
        is KmpResult.Success -> this
        is KmpResult.Failure -> kmpRunCatching {
            transform(failure)
        }
    }
}

inline fun <T, R> KmpResult<T>.fold(
    onSuccess: (T) -> R,
    onFailure: (KmpFailure) -> R,
): R {
    return when (this) {
        is KmpResult.Success -> onSuccess(value)
        is KmpResult.Failure -> onFailure(failure)
    }
}

/**
 * Protected orchestration helper.
 *
 * Use only inside kmpRunCatching/kmpSuspendRunCatching or private/internal *OrThrow helpers.
 * Do not use at SDK / Swift / Flutter / public boundaries.
 */
fun <T> KmpResult<T>.getOrThrow(): T {
    return when (this) {
        is KmpResult.Success -> value
        is KmpResult.Failure -> throw KmpFailureException(failure)
    }
}

fun <T> KmpResult<T>.failureOrNull(): KmpFailure? {
    return when (this) {
        is KmpResult.Success -> null
        is KmpResult.Failure -> failure
    }
}


fun KmpFailure.wrap(
    message: String,
    code: String? = null,
    retryable: Boolean = this.retryable,
    source: String? = null,
    origin: KmpFailureOrigin = KmpFailureOrigin.COMMON,
    nativeType: String? = null,
    debugInfo: String? = null,
    metadata: Map<String, String> = emptyMap(),
): KmpFailure {
    return KmpFailure(
        message = message,
        code = code ?: this.code,
        retryable = retryable,
        cause = this,
        source = source,
        origin = origin,
        nativeType = nativeType,
        debugInfo = debugInfo,
        metadata = metadata,
    )
}

fun KmpFailure.chainString(): String {
    return generateSequence(this) { it.cause }
        .joinToString(separator = "\ncaused by: ") { failure ->
            buildString {
                append("[")
                append(failure.origin.name)
                append("] ")

                failure.code?.let { code ->
                    append("[")
                    append(code)
                    append("] ")
                }

                failure.source?.let { source ->
                    append(source)
                    append(": ")
                }

                append(failure.message)

                if (failure.retryable) {
                    append(" retryable=true")
                }

                failure.nativeType?.let { nativeType ->
                    append(" nativeType=")
                    append(nativeType)
                }
            }
        }
}

fun KmpFailure.debugString(): String {
    return buildString {
        append(chainString())

        val debugItems = generateSequence(this@debugString) { it.cause }
            .mapNotNull { failure ->
                failure.debugInfo?.let { debugInfo ->
                    val label = failure.source ?: failure.nativeType ?: failure.origin.name
                    label to debugInfo
                }
            }
            .toList()

        if (debugItems.isNotEmpty()) {
            append("\n\nDebug info:")
            debugItems.forEachIndexed { index, item ->
                append("\n--- debug ")
                append(index + 1)
                append(" ")
                append(item.first)
                append(" ---\n")
                append(item.second)
            }
        }
    }
}

fun Throwable.toKmpFailure(
    message: String? = null,
    code: String? = null,
    retryable: Boolean = false,
    source: String? = null,
    origin: KmpFailureOrigin = KmpFailureOrigin.COMMON,
    metadata: Map<String, String> = emptyMap(),
): KmpFailure {
    return when (this) {
        is KmpFailureException -> failure

        else -> KmpFailure(
            message = message ?: this.message ?: "Unknown failure",
            code = code ?: this::class.simpleName ?: "UNKNOWN_FAILURE",
            retryable = retryable,
            cause = cause?.toKmpFailure(origin = origin),
            source = source,
            origin = origin,
            nativeType = this::class.qualifiedName ?: this::class.simpleName,
            debugInfo = safeDebugInfo(),
            metadata = metadata,
        )
    }
}

fun Throwable.safeDebugInfo(): String {
    return try {
        stackTraceToString()
    } catch (_: Throwable) {
        "${this::class.simpleName}: ${message ?: "Unknown failure"}"
    }
}
