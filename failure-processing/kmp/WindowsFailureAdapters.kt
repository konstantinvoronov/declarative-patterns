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

package com.example.failure.windows

import com.example.failure.KmpFailure
import com.example.failure.KmpFailureOrigin
import com.example.failure.toKmpFailure
import com.example.failure.wrap

/**
 * Future Windows/JVM-specific helper.
 * Use when Windows/JVM platform code creates or wraps failures.
 */

fun Throwable.toWindowsKmpFailure(
    message: String? = null,
    code: String? = null,
    retryable: Boolean = false,
    source: String? = null,
    metadata: Map<String, String> = emptyMap(),
): KmpFailure {
    return toKmpFailure(
        message = message,
        code = code,
        retryable = retryable,
        source = source,
        origin = KmpFailureOrigin.WINDOWS,
        metadata = metadata,
    )
}

fun KmpFailure.wrapWindows(
    message: String,
    code: String? = null,
    retryable: Boolean = this.retryable,
    source: String? = null,
    nativeType: String? = null,
    debugInfo: String? = null,
    metadata: Map<String, String> = emptyMap(),
): KmpFailure {
    return wrap(
        message = message,
        code = code,
        retryable = retryable,
        source = source,
        origin = KmpFailureOrigin.WINDOWS,
        nativeType = nativeType,
        debugInfo = debugInfo,
        metadata = metadata,
    )
}
