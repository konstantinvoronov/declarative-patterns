# 04. Declarative Failure Handling Patterns on macOS

## macOS Swift Project Failure Standard

In this project, Swift is a project/platform layer of the KMP system.

Therefore Swift project code must use the shared failure contract as the standard failure-processing model:

```text
KmpResult<T>
KmpFailure
KmpResultSwift<T> for Swift-safe consumption of KmpResult<T>
```

Swift `Error`, `throws`, `NSError`, and `Result<T, Error>` are local-only tools.
They are allowed only inside private/native leaf functions, Apple API wrappers, or tiny implementation details that do not expose project failure.

They must not be used as the normal failure-processing model for:

```text
Swift repositories
Swift services
Swift adapters
Swift ViewModels / app operation handlers
background workers
push/background wake handlers
SDK bridges
KMP-facing functions
transport boundaries
logging/diagnostics boundaries
```

At these boundaries, expected failures must be represented as `KmpFailure` and processed through `KmpResult`.

Good project-layer Swift:

```swift
func loadUserResult() async -> KmpResult {
    do {
        let user = try await nativeUserClient.loadUser()
        return KmpResultSuccess(value: user)
    } catch is CancellationError {
        throw error
    } catch {
        return KmpResultFailure(
            failure: makeMacosKmpFailure(
                from: error,
                source: "MacosUserRepository.loadUserResult"
            )
        )
    }
}
```

Good private local native leaf:

```swift
private func loadUserNative() async throws -> User {
    try await nativeUserClient.loadUser()
}
```

Bad project-layer Swift:

```swift
func loadUser() async throws -> User {
    try await nativeUserClient.loadUser()
}
```

Bad project-layer Swift:

```swift
func loadUser() async -> User? {
    try? await nativeUserClient.loadUser()
}
```

Rule:

```text
Swift project layer uses KmpResult / KmpFailure as standard.
Native Swift errors are allowed only below that layer.
Private native throws must be caught and converted before leaving the project-layer function.
```


## Mandatory macOS Failure Contract

macOS project-layer failures must use `KmpFailure`.

Swift/macOS project-layer failure handling must use `KmpResult` / `KmpFailure` as the standard contract. Swift `Error`, `NSError`, AppKit errors, JVM/Kotlin exceptions, and native macOS errors are local-only implementation details. Before the failure crosses a project, SDK, repository, service, adapter, logging, diagnostics, or shared KMP boundary, convert it to `KmpFailure` and return `KmpResult.Failure`.

Good Swift macOS:

```swift
func loadSettingsResult() async -> KmpResult {
    do {
        let settings = try await nativeSettingsClient.load()
        return KmpResultSuccess(value: settings)
    } catch {
        return KmpResultFailure(
            failure: makeMacosKmpFailure(
                from: error,
                source: "MacosSettingsRepository.loadSettingsResult"
            )
        )
    }
}
```

Bad:

```swift
func loadSettings() async throws -> Settings { try await nativeSettingsClient.load() }
```

when this is a project/SDK/shared failure boundary.


This document defines macOS-side patterns using the shared failure library.

Use this for:

```text
macosMain Kotlin code
macOS Swift/AppKit/SwiftUI app code
macOS platform API calls
macOS file/storage/network adapters
macOS UI state conversion
macOS logging/crash reporting
```

The project-level failure contract remains:

```text
KmpResult<T>
KmpFailure
```

macOS is conceptually close to iOS when the native app layer is Swift, but it may also have Kotlin `macosMain` code.

---

## 1. Do We Need macOS Rules Now?

If the project does not have a macOS target yet, you do not need macOS implementation files now.

But if the architecture is intended to support macOS later, keep this document because it fixes the future rules early.

Rule:

```text
No active macOS target = no required macOS code.
Future macOS target = follow the same KmpFailure chain rules from the start.
```

---

## 2. macOS Role

macOS may do three things:

```text
1. Consume KmpResult<T> / KmpFailure from shared KMP.
2. Convert macOS native Error / NSError into KmpFailure.
3. Wrap existing KmpFailure with macOS-specific context.
```

macOS must not destroy the existing failure chain.

---

## 3. Kotlin macosMain Pattern

Use `KmpResult<T>` in `macosMain` Kotlin code.

### Good pattern

```kotlin
fun readMacosSettings(): KmpResult<Settings> {
    return kmpRunCatching {
        settingsStorage.readSettings()
    }.mapFailure { failure ->
        failure.wrapMacos(
            message = "MacosSettingsStorage.readMacosSettings failed to read macOS settings",
            source = "MacosSettingsStorage.readMacosSettings",
        )
    }
}
```

### Bad pattern

```kotlin
fun readMacosSettings(): Settings? {
    return try {
        settingsStorage.readSettings()
    } catch (e: Throwable) {
        null
    }
}
```

Why bad:

```text
A real failure becomes null.
The KmpFailure chain is lost.
```

---

## 4. Swift macOS Pattern

If the macOS app is Swift/AppKit/SwiftUI, follow the same principles as iOS.

### Convert native Swift Error to KmpFailure

```swift
let failure = KmpFailure(
    message: error.localizedDescription,
    code: nil,
    retryable: false,
    cause: nil,
    source: "MacosSettingsService.loadSettings",
    origin: KmpFailureOrigin.macos,
    nativeType: String(describing: type(of: error)),
    debugInfo: String(describing: error),
    metadata: [:]
)
```

### Wrap existing KmpFailure

```swift
let wrapped = KmpFailure(
    message: "MacosDashboardAdapter.loadDashboard failed to return dashboard to macOS UI",
    code: failure.code,
    retryable: failure.retryable,
    cause: failure,
    source: "MacosDashboardAdapter.loadDashboard",
    origin: KmpFailureOrigin.macos,
    nativeType: nil,
    debugInfo: nil,
    metadata: [:]
)
```

Rule:

```text
Swift macOS code may create KmpFailure from native Error.
Swift macOS code may wrap existing KmpFailure.
Swift macOS code must not replace or flatten an existing KmpFailure chain.
```

---


---

## Swift macOS Consumption Uses KmpResultSwift

If the macOS app layer is Swift/AppKit/SwiftUI, consume shared `KmpResult<T>` through:

```swift
let resultSwift = result.asKmpResultSwift()
```

Do not directly cast to generated sealed subclasses:

```swift
case let success as KmpResultSuccess<User>
case let failure as KmpResultFailure
```

Good:

```swift
let result = try await settingsRepository.loadSettings()
let resultSwift = result.asKmpResultSwift()

switch resultSwift.status {
case .success:
    guard let settings = resultSwift.value as? Settings else {
        showError("KmpResult success state has no Settings value")
        return
    }

    render(settings)

case .failure:
    guard let failure = resultSwift.failure else {
        showError("KmpResult failure state has no KmpFailure")
        return
    }

    logger.error(failure.debugString())
    showError(failure.message)

default:
    showError("Unsupported KmpResultSwiftStatus")
}
```

Rule:

```text
macosMain Kotlin code uses KmpResult<T> directly.
Swift macOS app code consumes KmpResult<T> through asKmpResultSwift().
KmpResultSwift must preserve the original KmpFailure object.
```

## 5. macOS UI State Conversion

Convert `KmpFailure` into clean UI state at the edge.

### Good pattern

```kotlin
data class MacosErrorUiState(
    val message: String,
    val retryable: Boolean,
)

fun KmpFailure.toMacosErrorUiState(): MacosErrorUiState {
    return MacosErrorUiState(
        message = message,
        retryable = retryable,
    )
}
```

### Bad pattern

```kotlin
MacosErrorUiState(
    message = failure.debugString(),
    retryable = false,
)
```

Why bad:

```text
debugString is for logs and diagnostics, not normal user-facing UI.
```

---

## 6. macOS Logging Ownership Rule

Functions that return `KmpResult<T>` must not log.

They wrap and return.

Logging belongs to consuming boundaries.

### Bad pattern

```kotlin
fun loadDocument(): KmpResult<Document> {
    val result = documentRepository.loadDocument()

    result.failureOrNull()?.let { failure ->
        logger.error(failure.debugString())
    }

    return result
}
```

### Good pattern

```kotlin
fun loadDocument(): KmpResult<Document> {
    return documentRepository.loadDocument()
        .mapFailure { failure ->
            failure.wrapMacos(
                message = "MacosDocumentService.loadDocument failed to load document",
                source = "MacosDocumentService.loadDocument",
            )
        }
}
```

### Good consuming boundary

```kotlin
documentService.loadDocument().fold(
    onSuccess = { document ->
        render(document)
    },
    onFailure = { failure ->
        logger.error(failure.debugString())
        renderError(failure.message)
    }
)
```

---

## 7. macOS Orchestration

Use `getOrThrow()` inside `kmpRunCatching` / `kmpSuspendRunCatching`.

### Good pattern

```kotlin
suspend fun buildMacosDashboard(): KmpResult<DashboardState> {
    return kmpSuspendRunCatching {
        val user = userRepository.loadUser().getOrThrow()
        val bookings = bookingRepository.loadBookings(user.id).getOrThrow()
        val payments = paymentRepository.loadPayments(user.id).getOrThrow()

        DashboardState.Ready(user, bookings, payments)
    }.mapFailure { failure ->
        failure.wrapMacos(
            message = "MacosDashboardService.buildMacosDashboard failed to build dashboard state",
            source = "MacosDashboardService.buildMacosDashboard",
        )
    }
}
```

### Bad pattern

```kotlin
suspend fun buildMacosDashboard(): KmpResult<DashboardState> {
    return userRepository.loadUser().flatMap { user ->
        bookingRepository.loadBookings(user.id).flatMap { bookings ->
            paymentRepository.loadPayments(user.id).map { payments ->
                DashboardState.Ready(user, bookings, payments)
            }
        }
    }
}
```

This is not always wrong, but for multi-step orchestration it often becomes harder to read and can miss a unified function-level wrapper.

---

## 8. macOS Fallback

Use `recover` / `recoverCatching` only when fallback is a valid success.

### Good pattern

```kotlin
fun loadMacosConfig(): KmpResult<AppConfig> {
    return remoteConfigRepository.loadConfig()
        .recoverCatching {
            localMacosConfigRepository.loadConfig().getOrThrow()
        }
        .mapFailure { failure ->
            failure.wrapMacos(
                message = "MacosConfigRepository.loadMacosConfig failed to load remote config and fallback local config",
                source = "MacosConfigRepository.loadMacosConfig",
            )
        }
}
```

### Bad pattern

```kotlin
fun loadUser(): KmpResult<User> {
    return userRepository.loadUser()
        .recover {
            User.empty()
        }
}
```

unless `User.empty()` is a valid business state.

---

## 9. macOS Optional Values

Do not use failure for normal absence.

### Good pattern

```kotlin
fun findCachedDocument(): KmpResult<Document?> {
    return kmpRunCatching {
        cache.findDocument()
    }.mapFailure { failure ->
        failure.wrapMacos(
            message = "MacosDocumentCache.findCachedDocument failed to read cached document",
            source = "MacosDocumentCache.findCachedDocument",
        )
    }
}
```

Meaning:

```text
KmpResult.Failure = cache read failed.
KmpResult.Success(null) = cache works, but document is absent.
```

---

## 10. Routine That Must Throw

Some macOS APIs may require throwing.

Use this only at integration boundaries.

### Good pattern

```kotlin
fun loadDocumentOrThrow(): Document {
    return try {
        documentRepository.loadDocument().getOrThrow()
    } catch (e: KmpFailureException) {
        throw e.failure.wrapMacos(
            message = "MacosDocumentService.loadDocumentOrThrow failed to load document",
            source = "MacosDocumentService.loadDocumentOrThrow",
        ).toException()
    }
}
```

---

## 11. macOS Anti-Patterns

Bad:

```kotlin
fun loadDocument(): Document?
```

when failure is meaningful.

Bad:

```kotlin
KmpFailure(message = failure.message)
```

because it loses the cause chain.

Bad:

```kotlin
failure.wrapMacos("Failed")
```

because the message is vague.

Bad:

```kotlin
val document = documentRepository.loadDocument().getOrNull()
```

because a real failure becomes null.

Good:

```kotlin
documentRepository.loadDocument().fold(
    onSuccess = { document -> render(document) },
    onFailure = { failure ->
        logger.error(failure.debugString())
        renderError(failure.message)
    }
)
```

---

## 12. macOS AI / Developer Rules

1. Use `KmpResult<T>` for macOS fallible project operations.
2. Use `origin = MACOS` when macOS adds a failure wrapper.
3. Preserve existing `KmpFailure` as `cause`.
4. Do not replace a shared failure with a Swift Error, NSError, Throwable, or string.
5. Use `map`, `mapCatching`, `flatMap`, `recover`, and `fold` with the same meaning as KMP.
6. Use `getOrThrow()` inside catching helpers for orchestration.
7. Log only at consuming boundaries.
8. Do not use `getOrNull()` as a normal project pattern.

---

## KmpFailureException Must Not Cross Swift macOS Boundary

Swift/macOS code consumes `KmpResult<T>` through `asKmpResultSwift()`.

Expected failures must arrive as `KmpResult.Failure`, not as unhandled `KmpFailureException`.

Bad:

```kotlin
throw failure.toException()
```

Good:

```kotlin
return KmpResult.Failure(failure)
```

---

## Local Native No-Op Is Not KmpFailure

Do not create `KmpFailure` for local native bookkeeping that is not a project operation failure.

Good native diagnostic/no-op:

```swift
guard taskToEnd != .invalid else {
    os_log(
        "[Push][Silent] background task end skipped label=%{public}@ reason=%{public}@",
        log: log,
        type: .info,
        label,
        reason
    )
    return
}
```

This is not a `KmpFailure` when:

```text
the branch is expected idempotency / duplicate callback handling
no KMP/project operation failed
nothing must be returned to shared KMP
nothing crosses SDK / adapter / repository / service / transport boundary
the only action is local native lifecycle logging
```

Make it `KmpFailure` only when the event becomes a project-level operation failure.

Good KmpFailure case:

```swift
return KmpResultFailure(
    failure: makeIosKmpFailure(
        from: error,
        source: "IosPushBackgroundWake.handleSilentPush"
    )
)
```

Rule:

```text
KmpFailure is obligatory for project-level failures.
KmpFailure is not required for local native no-op/idempotency diagnostics.
```

---

## Platform-Native Error Conversion Rule

Platform-native errors are local-only.

When a native error becomes part of project logic, convert it to `KmpFailure`.

Good:

```text
Android Throwable -> KmpFailure -> KmpResult.Failure
Swift Error / NSError -> KmpFailure -> KmpResult.Failure
JVM Exception -> KmpFailure -> KmpResult.Failure
Windows platform exception -> KmpFailure -> KmpResult.Failure
macOS Swift Error -> KmpFailure -> KmpResult.Failure
```

Bad:

```text
Throwable escapes as project contract
NSError escapes as project contract
Swift Error escapes as project contract
Kotlin Result<T> becomes project contract
Swift Result<T, Error> becomes project contract
null hides project failure
String message replaces KmpFailure
```
