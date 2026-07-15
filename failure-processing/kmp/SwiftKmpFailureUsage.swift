// Swift project failure standard:
//
// Swift/iOS/macOS project code must use KmpResult / KmpFailure as the standard
// failure-processing model.
//
// Swift Error, NSError, throws, and Result<T, Error> are local-only tools for
// private native leaf functions and Apple API wrappers. They must be caught and
// converted before leaving the project-layer function.
//
// Expected failures at Swift repositories, services, adapters, ViewModels,
// background workers, SDK bridges, KMP-facing APIs, transport, logging, or
// diagnostics boundaries must become KmpFailure and must be returned/processed
// through KmpResult or KmpResultSwift.

// Mandatory failure rule:
//
// KmpFailure is obligatory for all expected project-level failures crossing
// Swift/KMP/project boundaries.
//
// Swift Error, NSError, throws, and Result<T, Error> are local-only.
// Convert them to KmpFailure before returning into shared KMP / SDK / transport / diagnostics.
//
// Local native no-op/idempotency diagnostics do not require KmpFailure when no project
// operation failed and nothing crosses a boundary.

import Foundation

// =============================================================================
// SwiftKmpFailureUsage.swift
// =============================================================================
//
// Purpose:
//
// This file is for Swift/iOS app-side code that needs to participate in the
// shared KMP failure chain.
//
// Put this file into the iOS app target, for example:
//
//     iosApp/Failure/SwiftKmpFailureUsage.swift
//
// Do NOT put this file into:
//
//     shared/src/commonMain
//     shared/src/iosMain
//
// Those folders are Kotlin source sets. This file is for Swift code.
//
// -----------------------------------------------------------------------------
// When this file is needed:
//
// Use this file when Swift:
//
// 1. catches a native Swift Error / NSError;
// 2. needs to convert that Error into KmpFailure;
// 3. receives an existing KmpFailure and needs to add iOS context;
// 4. returns a failure back into KMP / Android / shared transport;
// 5. needs a Swift Error wrapper around KmpFailure for a throwing Swift API.
//
// -----------------------------------------------------------------------------
// When this file is NOT needed:
//
// You do not need this file if Swift only receives KmpResult from KMP and then
// shows UI or logs the failure.
//
// In that case Swift should consume KmpResult through the global projection:
//
//     let resultSwift = result.asKmpResultSwift()
//     switch resultSwift.status { ... }
//
// Do not directly cast to KmpResultSuccess<T> or KmpResultFailure in Swift.
//
// -----------------------------------------------------------------------------
// Main rule:
//
// If Swift receives an existing KmpFailure, do not replace it.
// Wrap it only if Swift adds meaningful context.
// Always preserve the existing failure as cause.
// =============================================================================


// MARK: - Convert Swift Error to KmpFailure

/// Converts a native Swift Error into the shared portable KmpFailure format.
///
/// Use this when Swift catches a native Error and must return it to KMP,
/// Android, shared transport, or another project-level boundary.
///
/// Good:
///
///     do {
///         let settings = try await settingsService.load()
///         return KmpResult.Success(value: settings)
///     } catch is CancellationError {
///         throw error
///     } catch {
///         return KmpResult.Failure(
///             failure: makeIosKmpFailure(
///                 from: error,
///                 source: "IosSettingsService.loadSettings"
///             )
///         )
///     }
///
/// Bad:
///
///     return nil
///
/// Bad:
///
///     return KmpFailure(message: error.localizedDescription)
///
/// The bad versions lose platform metadata, source, origin, and often the chain.
func makeIosKmpFailure(
    from error: Error,
    source: String? = nil,
    code: String? = nil,
    retryable: Bool = false,
    cause: KmpFailure? = nil,
    metadata: [String: String] = [:]
) -> KmpFailure {
    KmpFailure(
        message: error.localizedDescription,
        code: code,
        retryable: retryable,
        cause: cause,
        source: source,
        origin: KmpFailureOrigin.ios,
        nativeType: String(describing: type(of: error)),
        debugInfo: String(describing: error),
        metadata: metadata
    )
}


// MARK: - Wrap Existing KmpFailure with iOS Context

/// Wraps an existing KmpFailure with iOS-specific context.
///
/// Use this when Swift receives an existing KmpFailure and the current Swift
/// function adds meaningful responsibility.
///
/// Good:
///
///     let wrapped = wrapIosKmpFailure(
///         failure,
///         message: "IosCartAdapter.loadCart failed to return cart to Swift",
///         source: "IosCartAdapter.loadCart"
///     )
///
/// Bad:
///
///     let newFailure = KmpFailure(
///         message: failure.message,
///         code: failure.code,
///         retryable: failure.retryable,
///         cause: nil,
///         source: "IosCartAdapter.loadCart",
///         origin: .ios,
///         nativeType: nil,
///         debugInfo: nil,
///         metadata: [:]
///     )
///
/// The bad version loses the original cause chain.
func wrapIosKmpFailure(
    _ failure: KmpFailure,
    message: String,
    source: String,
    code: String? = nil,
    retryable: Bool? = nil,
    nativeType: String? = nil,
    debugInfo: String? = nil,
    metadata: [String: String] = [:]
) -> KmpFailure {
    KmpFailure(
        message: message,
        code: code ?? failure.code,
        retryable: retryable ?? failure.retryable,
        cause: failure,
        source: source,
        origin: KmpFailureOrigin.ios,
        nativeType: nativeType,
        debugInfo: debugInfo,
        metadata: metadata
    )
}


// MARK: - Swift Error Wrapper for KmpFailure

/// Swift-side Error wrapper for KmpFailure.
///
/// Use this only when a Swift API must throw but the real project failure is
/// already represented as KmpFailure.
///
/// This is useful when Kotlin's KmpFailureException is not ergonomic from Swift.
struct SwiftKmpFailureError: Error {
    let failure: KmpFailure
}


/// Throws a KmpFailure as a Swift Error wrapper.
func throwSwiftKmpFailure(_ failure: KmpFailure) throws -> Never {
    throw SwiftKmpFailureError(failure: failure)
}


/// Creates a Swift throwing-boundary wrapper around an existing KmpFailure.
///
/// Use this when a Swift function must throw but should still add iOS context.
///
/// Good:
///
///     func loadUserOrThrow() async throws -> User {
///         let result = await userRepository.loadUser()
///
///         do {
///             return try result.getOrThrow()
///         } catch let failureError as SwiftKmpFailureError {
///             throw wrapIosKmpFailureForThrowingBoundary(
///                 failureError.failure,
///                 message: "IosUserService.loadUserOrThrow failed to load user",
///                 source: "IosUserService.loadUserOrThrow"
///             )
///         }
///     }
func wrapIosKmpFailureForThrowingBoundary(
    _ failure: KmpFailure,
    message: String,
    source: String
) -> SwiftKmpFailureError {
    SwiftKmpFailureError(
        failure: wrapIosKmpFailure(
            failure,
            message: message,
            source: source
        )
    )
}


// MARK: - Consuming KmpFailure in Swift

/// Use this at consuming boundaries only: UI, logging, crash reporting,
/// final adapter response, or fire-and-forget task boundary.
///
/// Good:
///
///     func renderFailure(_ failure: KmpFailure) {
///         logger.error(failure.debugString())
///         showError(failure.message)
///     }
///
/// Bad inside a function that still returns KmpResult:
///
///     func loadUser() async -> KmpResult {
///         let result = await repository.loadUser()
///
///         if let failure = result.failureOrNull() {
///             logger.error(failure.debugString())
///         }
///
///         return result
///     }
///
/// The bad version logs too early and can cause duplicate logs.
func logIosKmpFailureAtBoundary(
    _ failure: KmpFailure,
    logger: (String) -> Void
) {
    logger(failure.debugString())
}


// MARK: - Anti-pattern reminders

// Bad:
//     try? await api.loadUser()
//
// Why:
//     It converts real failure into nil and loses the chain.
//
// Bad:
//     KmpFailure(message: error.localizedDescription)
//
// Why:
//     It loses origin, source, nativeType, debugInfo, and often cause.
//
// Bad:
//     let user = result.getOrNull()
//
// Why:
//     A real KmpFailure becomes nil.
//
// Good:
//     Use KmpResult fold/switch at consuming boundaries.
//     Use wrapIosKmpFailure when Swift adds context.
//     Use makeIosKmpFailure when Swift catches native Error.
//     Preserve CancellationError as cancellation.
