# 00. Shared Failure Library Contract

## Swift / Apple Platform Standard

For this project, Swift code in iOS/macOS is not an unrelated native island. It is a project/platform layer of the KMP system.

Therefore Swift project-layer failure handling must use:

```text
KmpResult<T>
KmpFailure
KmpResultSwift<T> for Swift-safe consumption
```

Swift `Error`, `throws`, `NSError`, and `Result<T, Error>` are local-only tools. They are allowed only inside private/native leaf functions, Apple API wrappers, or tiny implementation details.

They must not be used as the standard failure-processing model for Swift repositories, services, adapters, ViewModels, background workers, SDK bridges, KMP-facing functions, logging/diagnostics, or transport boundaries.

At those boundaries, expected failures must become `KmpFailure` and must be returned/processed through `KmpResult`.


## Mandatory KmpFailure Rule

`KmpFailure` is obligatory for all expected project-level failures in every KMP/platform layer.

This applies to:

```text
commonMain
androidMain
iosMain
macosMain
jvmMain
windowsMain
Android app adapters
Swift iOS adapters
Swift macOS adapters
transport/protobuf adapters
SDK APIs
repositories
services
use cases
background project operations
logging/diagnostics boundaries
```

Native platform errors are allowed only as temporary local implementation details. They are not the standard failure-processing model for any project/platform layer.

Before a failure crosses any project, module, SDK, platform, repository, service, adapter, transport, logging, diagnostics, or app-facing result boundary, it must be represented as `KmpFailure` and returned as `KmpResult.Failure`.

Do not expose these as the project-level failure contract:

```text
Throwable
Exception
NSError
Swift Error
Swift Result<T, Error>
Kotlin Result<T>
null
plain String error messages
```

Default:

```kotlin
return KmpResult.Failure(failure)
```

Not:

```kotlin
throw failure.toException()
```

Not:

```kotlin
return null
```

Not:

```swift
try? await operation()
```


This document defines the contract used by all platform-specific failure-handling patterns.

The project-level failure type is:

```kotlin
KmpResult<T>
```

The project-level failure object is:

```kotlin
KmpFailure
```

The Swift-safe projection for Swift/iOS/macOS consumption is:

```kotlin
KmpResultSwift<T>
KmpResult<T>.asKmpResultSwift()
```

---

## 1. Core Model

```kotlin
sealed class KmpResult<out T> {

    data class Success<T>(
        val value: T,
    ) : KmpResult<T>()

    data class Failure(
        val failure: KmpFailure,
    ) : KmpResult<Nothing>()
}
```

`KmpResult.Failure` is `KmpResult<Nothing>` because a failure has no success value. This is correct for Kotlin, because `Nothing` is a subtype of every type and `KmpResult` is covariant.

For Swift interop, expose a flat Swift-safe projection:

```kotlin
enum class KmpResultSwiftStatus {
    SUCCESS,
    FAILURE,
}

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
```

`KmpResultSwift` is a projection, not a replacement for `KmpResult`.
It must preserve the original `KmpFailure` object so the failure chain remains intact.

```kotlin
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
```

```kotlin
enum class KmpFailureOrigin {
    COMMON,
    ANDROID,
    IOS,
    MACOS,
    WINDOWS,
    JVM,
    UNKNOWN,
}
```

---

## 2. Meaning of Fields

```text
message
```

Human-readable explanation of what failed at this layer.

```text
code
```

Stable machine-readable category. Examples: `NETWORK_ERROR`, `AUTH_FAILED`, `VALIDATION_ERROR`.

```text
retryable
```

Behavior hint. It should not be guessed by the UI.

```text
cause
```

Previous failure layer. This is the replacement for `Throwable.cause` / Swift contextual error cause.

```text
source
```

The class/function/layer that added this failure wrapper.

Examples:

```text
UserRepository.loadCurrentUser
CartApi.fetchCart
AndroidCartPresenter.loadCart
IosCartAdapter.loadCart
```

```text
origin
```

The platform/layer that created this wrapper.

```text
nativeType
```

Original native exception/error type, if known.

Examples:

```text
java.io.IOException
URLError
NSError
KotlinxSerializationException
```

```text
debugInfo
```

Diagnostic text such as stack trace, native error description, or serialized debug details.

```text
metadata
```

Portable extra fields.

---

## 3. Required Helpers

The library should provide:

```kotlin
kmpRunCatching { ... }
kmpSuspendRunCatching { ... }

KmpResult.map(...)
KmpResult.mapCatching(...)
KmpResult.mapFailure(...)
KmpResult.recover(...)
KmpResult.recoverCatching(...)
KmpResult.fold(...)
KmpResult.getOrThrow()
KmpResult.failureOrNull()

KmpFailure.wrap(...)
KmpFailure.chainString()
KmpFailure.debugString()
Throwable.toKmpFailure(...)
```

---

## 4. Why Not Use Kotlin Result or Swift Result as the Project Contract?

Kotlin `Result<T>` is good for vanilla Kotlin, but it is not ideal as the cross-platform project contract.

Swift `Result<T, Error>` is good for vanilla Swift, but it cannot preserve the same portable chain across KMP, Android, and iOS.

`KmpResult<T>` + `KmpFailure` gives one shared failure language:

```text
KMP common code
Android code
iOS adapter code
Swift code
future macOS code
future Windows/JVM code
transport/protobuf adapters
logs and diagnostics
```

---

## 4.1. `failureOrNull()` and `getOrNull()` Policy

`failureOrNull()` is allowed, but only for failure inspection at consuming boundaries.

It is the KMP equivalent of Kotlin `exceptionOrNull()`.

Good use:

```kotlin
fun runSyncJob() {
    val result = syncRepository.sync()

    result.failureOrNull()?.let { failure ->
        logger.error(failure.debugString())
    }
}
```

Bad use:

```kotlin
fun loadUser(): KmpResult<User> {
    val result = repository.loadUser()

    result.failureOrNull()?.let { failure ->
        logger.error(failure.debugString())
    }

    return result
}
```

Why bad:

```text
The function still returns the same KmpResult.
The failure may be logged again by the real consuming boundary.
```

`getOrNull()` should not be part of the default library.

It converts failure into `null` and can silently hide the failure chain.

Bad:

```kotlin
val user: User? = repository.loadUser().getOrNull()
```

Good:

```kotlin
repository.loadUser().fold(
    onSuccess = { user ->
        render(user)
    },
    onFailure = { failure ->
        logger.error(failure.debugString())
        renderError(failure.message)
    }
)
```

Rule:

```text
Use failureOrNull() only at consuming boundaries.
Do not use getOrNull() as a normal project pattern.
Prefer fold, map, mapFailure, or getOrThrow depending on intent.
```

---

## 5. Platform Transition Rule

When a failure crosses a platform boundary:

```text
Preserve the existing KmpFailure.
Wrap only if the receiving platform adds meaningful context.
Never replace the existing failure chain with a plain string or fresh native error.
```

Bad:

```text
iOS receives KmpFailure and replaces it with NSError.localizedDescription.
Android receives KmpFailure and replaces it with RuntimeException(message).
KMP receives platform error and drops original debug information.
```

Good:

```text
iOS receives KmpFailure and wraps it with source="IosCartAdapter.loadCart".
Android receives KmpFailure and wraps it with source="AndroidCartPresenter.loadCart".
KMP receives Throwable and converts it to KmpFailure with debugInfo.
```

---

## 6. Logging Rule

Functions that return `KmpResult<T>` do not log.

They wrap and return.

Logging happens only at consuming boundaries:

```text
UI rendering
background job terminal point
transport response creation
crash/diagnostic reporting
legacy callback
command handler
```

---

## 7. Serialization Rule

If `KmpFailure` is serialized, preserve at least:

```text
message
code
retryable
cause
source
origin
nativeType
debugInfo
metadata
```

Do not serialize only `message`, because that destroys the chain.

---

## Swift-Safe Result Projection

Swift should not directly consume Kotlin sealed subclasses such as:

```swift
KmpResultSuccess<T>
KmpResultFailure
```

Reason:

```text
KmpResult.Failure is KmpResult<Nothing>.
Kotlin/Native exports Nothing as KotlinNothing.
An API may return KmpResult<ConcreteType>, while the failure branch is structurally KmpResult<Nothing>.
Direct Swift casts to sealed subclasses are fragile.
```

Use the global projection:

```swift
let resultSwift = result.asKmpResultSwift()

switch resultSwift.status {
case .success:
    let value = resultSwift.value

case .failure:
    let failure = resultSwift.failure

default:
    break
}
```

Rules:

```text
Kotlin/Android/JVM may consume KmpResult<T> directly with fold or sealed when.
Swift/iOS/macOS should consume KmpResult<T> through asKmpResultSwift().
KmpResultSwift must keep the original KmpFailure object, not flatten it to a string.
```

---

## KmpFailureException Policy

Expected project failures are returned as:

```kotlin
KmpResult.Failure(failure)
```

Do not use this as normal failure delivery:

```kotlin
throw failure.toException()
```

Allowed only:

```text
private/internal *OrThrow helpers
rare external adapters that must throw
immediate caller protects with kmpRunCatching/kmpSuspendRunCatching
normal project caller returns KmpResult<T>
```

Forbidden:

```text
KmpFailureException crossing SDK / Swift / Flutter / public API boundaries
KmpFailureException from functions returning T?
KmpFailureException from normal mapper/repository/service code
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
