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

package com.example.failure.android

import com.example.failure.KmpFailure
import com.example.failure.KmpFailureOrigin
import com.example.failure.toKmpFailure
import com.example.failure.wrap

/**
 * Android-specific helpers for creating and wrapping KmpFailure.
 *
 * These helpers are optional convenience functions.
 * The core rule is still:
 *
 * - preserve the existing KmpFailure chain;
 * - wrap only when Android adds meaningful context;
 * - use origin = ANDROID for Android-created wrappers.
 */

fun Throwable.toAndroidKmpFailure(
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
        origin = KmpFailureOrigin.ANDROID,
        metadata = metadata,
    )
}

fun KmpFailure.wrapAndroid(
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
        origin = KmpFailureOrigin.ANDROID,
        nativeType = nativeType,
        debugInfo = debugInfo,
        metadata = metadata,
    )
}
