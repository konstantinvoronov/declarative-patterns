/*
 * Swift/Apple project failure standard:
 *
 * iOS/macOS project code must use KmpResult / KmpFailure as the standard
 * failure-processing model at repositories, services, adapters, background
 * workers, SDK bridges, KMP-facing APIs, transport, logging, and diagnostics
 * boundaries.
 *
 * Swift Error, NSError, throws, and Result<T, Error> are local-only tools for
 * private native leaf functions and Apple API wrappers.
 */

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

package com.example.failure.ios

import com.example.failure.KmpFailure
import com.example.failure.KmpFailureOrigin
import com.example.failure.wrap

/**
 * iOS-specific Kotlin-side helpers for creating and wrapping KmpFailure.
 *
 * Use these in iosMain or in iOS adapter code when Kotlin receives
 * an iOS/native failure description and needs to keep the failure chain portable.
 *
 * Swift-side code may also construct KmpFailure directly or use Swift helpers.
 */

fun iosKmpFailure(
    message: String,
    code: String? = null,
    retryable: Boolean = false,
    cause: KmpFailure? = null,
    source: String? = null,
    nativeType: String? = null,
    debugInfo: String? = null,
    metadata: Map<String, String> = emptyMap(),
): KmpFailure {
    return KmpFailure(
        message = message,
        code = code,
        retryable = retryable,
        cause = cause,
        source = source,
        origin = KmpFailureOrigin.IOS,
        nativeType = nativeType,
        debugInfo = debugInfo,
        metadata = metadata,
    )
}

fun KmpFailure.wrapIos(
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
        origin = KmpFailureOrigin.IOS,
        nativeType = nativeType,
        debugInfo = debugInfo,
        metadata = metadata,
    )
}
