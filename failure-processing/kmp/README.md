# Declarative Failure Patterns — Cross-Platform Setup

## Swift standard

Swift/iOS/macOS project code must use `KmpResult` / `KmpFailure` as the standard failure-processing model.

Swift `Error`, `throws`, `NSError`, and `Result<T, Error>` are local-only tools for private native leaf functions and Apple API wrappers.

They must not be the normal failure contract for Swift repositories, services, adapters, ViewModels, background workers, SDK bridges, KMP-facing APIs, transport, logging, or diagnostics boundaries.

At those boundaries, expected failures must become `KmpFailure` and must be returned/processed through `KmpResult` or exposed through `KmpResultSwift`.


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

Native platform errors are allowed only as temporary local implementation details. They are not the standard failure-processing model for project/platform layers.

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



## AI-agent hard rule

Default:

```kotlin
return KmpResult.Failure(failure)
```

Do not generate:

```kotlin
throw KmpFailure(...).toException()
```

Allowed only when all are true:

```text
function is private/internal *OrThrow, or rare external adapter that must throw
immediate caller protects it with kmpRunCatching/kmpSuspendRunCatching
normal project caller returns KmpResult<T>
exception does not cross SDK / Swift / Flutter / public API boundary
```


This package contains project-level failure-handling patterns and helper code for a Kotlin Multiplatform project.

The shared contract is:

```text
KmpResult<T>
KmpFailure
KmpFailure.cause
KmpFailure.wrap(...)
KmpResultSwift<T>
KmpResult<T>.asKmpResultSwift()
kmpRunCatching(...)
kmpSuspendRunCatching(...)
fold(...)
getOrThrow()
debugString()
```

---

## 1. Files in this package

```text
README.md
00_failure_library_contract.md
01_kmp_declarative_failure_patterns.md
02_android_declarative_failure_patterns.md
03_ios_swift_declarative_failure_patterns.md
04_macos_declarative_failure_patterns.md
05_windows_jvm_declarative_failure_patterns.md

KmpFailureLibrary.kt
AndroidFailureAdapters.kt
IosFailureAdapters.kt
MacosFailureAdapters.kt
WindowsFailureAdapters.kt
SwiftKmpFailureUsage.swift
```

---

## 2. Minimal setup

For a normal KMP project, the minimum required file is:

```text
KmpFailureLibrary.kt
```

Put it into:

```text
shared/src/commonMain/kotlin/<your/package>/failure/KmpFailureLibrary.kt
```

Example:

```text
shared/src/commonMain/kotlin/com/yourcompany/shared/failure/KmpFailureLibrary.kt
```

Then update the package declaration at the top of the file:

```kotlin
package com.yourcompany.shared.failure
```

This gives all shared code access to:

```kotlin
KmpResult<T>
KmpFailure
KmpFailureOrigin
KmpResultSwift<T>
KmpResultSwiftStatus
asKmpResultSwift()
kmpRunCatching
kmpSuspendRunCatching
map
mapCatching
mapFailure
recover
recoverCatching
fold
getOrThrow
failureOrNull
debugString
```

---


---

## 2.1 Swift-safe result projection

The shared library also defines:

```kotlin
KmpResultSwift<T>
KmpResultSwiftStatus
KmpResult<T>.asKmpResultSwift()
```

Purpose:

```text
Swift/iOS/macOS should not consume KmpResult<T> by directly casting to KmpResultSuccess<T> or KmpResultFailure.
KmpResult.Failure is KmpResult<Nothing>, which Kotlin/Native exports through KotlinNothing.
Use asKmpResultSwift() at Swift consumption boundaries.
```

Swift pattern:

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

Kotlin/Android/JVM code should continue using `KmpResult<T>` directly with `fold` or sealed `when`.

## 3. Recommended project layout

Use this structure:

```text
shared/
  src/
    commonMain/
      kotlin/
        com/yourcompany/shared/
          failure/
            KmpFailureLibrary.kt

    androidMain/
      kotlin/
        com/yourcompany/shared/
          failure/
            AndroidFailureAdapters.kt

    iosMain/
      kotlin/
        com/yourcompany/shared/
          failure/
            IosFailureAdapters.kt

    macosMain/
      kotlin/
        com/yourcompany/shared/
          failure/
            MacosFailureAdapters.kt

    jvmMain/ or windowsMain/
      kotlin/
        com/yourcompany/shared/
          failure/
            WindowsFailureAdapters.kt
```

If you do not have `macosMain`, `jvmMain`, or `windowsMain` yet, do not add those files now.

---

## 4. Where each file goes

### Required

```text
KmpFailureLibrary.kt
```

Place in:

```text
shared/src/commonMain/kotlin/<your/package>/failure/
```

Why:

```text
It defines the shared cross-platform failure contract.
All platforms depend on it.
```

---

### Android adapter

```text
AndroidFailureAdapters.kt
```

Place in:

```text
shared/src/androidMain/kotlin/<your/package>/failure/
```

Use it when Android platform code creates or wraps failures.

Example use cases:

```text
Android file access
Android permissions
Android SDK calls
Android ViewModel/presenter wrapper
Android WorkManager worker
Android-specific logging/crash adapter
```

If Android only consumes `KmpResult<T>` from common code and does not create/wrap platform failures, this file is not required.

---

### iOS Kotlin-side adapter

```text
IosFailureAdapters.kt
```

Place in:

```text
shared/src/iosMain/kotlin/<your/package>/failure/
```

Use it when Kotlin code in `iosMain` creates or wraps iOS-origin failures.

Example use cases:

```text
iosMain actual implementations
Kotlin wrappers around iOS APIs
Kotlin-side iOS adapters
Kotlin functions receiving iOS failure descriptions
```

If all iOS failure creation happens in Swift, this Kotlin-side file may not be needed.

---

### Swift helper

```text
SwiftKmpFailureUsage.swift
```

Place in your iOS application target, not inside `shared/src`.

Examples:

```text
iosApp/Failure/SwiftKmpFailureUsage.swift
iosApp/Shared/Failure/SwiftKmpFailureUsage.swift
```

Use it when Swift catches native `Error` and needs to convert it into `KmpFailure`.

Example use cases:

```text
Swift API call fails
Swift local storage fails
Swift permission/location/camera service fails
Swift adapter receives existing KmpFailure and wraps it
Swift returns failure back to KMP
```

---

### macOS adapter

```text
MacosFailureAdapters.kt
```

Place in:

```text
shared/src/macosMain/kotlin/<your/package>/failure/
```

Only add this when you create a macOS source set.

---

### Windows/JVM adapter

```text
WindowsFailureAdapters.kt
```

Place in:

```text
shared/src/jvmMain/kotlin/<your/package>/failure/
```

or future Windows-specific source set:

```text
shared/src/windowsMain/kotlin/<your/package>/failure/
```

Only add this when you create a JVM/Windows target.

---

## 5. Gradle dependencies

`KmpFailureLibrary.kt` uses:

```kotlin
kotlinx.coroutines.CancellationException
kotlinx.serialization.Serializable
```

So your shared module should have:

```kotlin
commonMain.dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:<version>")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:<version>")
}
```

And the Kotlin serialization plugin should be enabled if you keep `@Serializable`:

```kotlin
plugins {
    kotlin("plugin.serialization") version "<kotlin-version>"
}
```

If you do not need serialization yet, you may remove `@Serializable` imports and annotations.

---

## 6. Basic usage in commonMain

```kotlin
class UserRepository(
    private val userApi: UserApi,
) {
    suspend fun loadCurrentUser(): KmpResult<User> {
        return kmpSuspendRunCatching {
            val dto = userApi.fetchUserDto().getOrThrow()
            dto.toDomain()
        }.mapFailure { failure ->
            failure.wrap(
                message = "UserRepository.loadCurrentUser failed to load current user",
                source = "UserRepository.loadCurrentUser",
            )
        }
    }
}
```

---

## 7. Basic usage in Android

```kotlin
viewModelScope.launch {
    state.value = UserState.Loading

    userRepository.loadCurrentUser().fold(
        onSuccess = { user ->
            state.value = UserState.Ready(user)
        },
        onFailure = { failure ->
            logger.error(failure.debugString())

            state.value = UserState.Error(
                message = failure.message,
                retryable = failure.retryable,
            )
        }
    )
}
```

If Android adds its own context:

```kotlin
val wrapped = failure.wrap(
    message = "AndroidUserViewModel.loadUser failed to render user screen",
    source = "AndroidUserViewModel.loadUser",
    origin = KmpFailureOrigin.ANDROID,
)
```

Or, with the Android adapter:

```kotlin
val wrapped = failure.wrapAndroid(
    message = "AndroidUserViewModel.loadUser failed to render user screen",
    source = "AndroidUserViewModel.loadUser",
)
```

---

## 8. Basic usage in Swift

Swift may receive `KmpFailure` from the shared framework.

If Swift only displays/logs the failure, do not recreate it.

```swift
logger.error(failure.debugString())
showError(failure.message)
```

If Swift catches a native `Error`, convert it into `KmpFailure`:

```swift
let failure = makeIosKmpFailure(
    from: error,
    source: "IosSettingsService.loadSettings"
)
```

If Swift receives an existing `KmpFailure` and adds meaningful iOS context, wrap it:

```swift
let wrapped = wrapIosKmpFailure(
    failure,
    message: "IosCartAdapter.loadCart failed to return cart to Swift",
    source: "IosCartAdapter.loadCart"
)
```

Rule:

```text
Do not replace an existing KmpFailure.
Wrap it and preserve it as cause.
```

---

## 9. What not to do

Do not put all adapter files into `commonMain`.

Bad:

```text
shared/src/commonMain/kotlin/.../AndroidFailureAdapters.kt
shared/src/commonMain/kotlin/.../IosFailureAdapters.kt
```

Why bad:

```text
Platform-specific helpers belong to platform source sets.
commonMain should stay platform-neutral.
```

Do not use nullable/optional values to hide failure.

Bad:

```kotlin
fun loadUser(): User?
```

Good:

```kotlin
fun loadUser(): KmpResult<User>
```

Do not log and return the same failure from non-boundary functions.

Bad:

```kotlin
fun loadUser(): KmpResult<User> {
    val result = repository.loadUser()

    result.failureOrNull()?.let {
        logger.error(it.debugString())
    }

    return result
}
```

Good:

```kotlin
fun loadUser(): KmpResult<User> {
    return repository.loadUser()
        .mapFailure { failure ->
            failure.wrap(
                message = "UserService.loadUser failed to load user",
                source = "UserService.loadUser",
            )
        }
}
```

---

## 10. Which files should I copy right now?

Start with this:

```text
shared/src/commonMain/kotlin/<your/package>/failure/KmpFailureLibrary.kt
```

Then copy only the adapter files you actually need:

```text
AndroidFailureAdapters.kt -> androidMain, if Android creates/wraps failures.
IosFailureAdapters.kt -> iosMain, if Kotlin iosMain creates/wraps failures.
SwiftKmpFailureUsage.swift -> iOS app target, if Swift creates/wraps failures.
```

For the pattern documentation, put the Markdown files wherever you keep developer rules:

```text
docs/failure-processing/
```

Suggested layout:

```text
docs/failure-processing/
  00_failure_library_contract.md
  01_kmp_declarative_failure_patterns.md
  02_android_declarative_failure_patterns.md
  03_ios_swift_declarative_failure_patterns.md
04_macos_declarative_failure_patterns.md
05_windows_jvm_declarative_failure_patterns.md
```

---

## 11. Quick decision table

| Situation | Required file |
|---|---|
| Shared KMP code returns `KmpResult<T>` | `KmpFailureLibrary.kt` in `commonMain` |
| Android only consumes shared results | only `KmpFailureLibrary.kt` |
| Android catches Android exceptions | add `AndroidFailureAdapters.kt` to `androidMain` |
| iOS Kotlin `actual` code creates failures | add `IosFailureAdapters.kt` to `iosMain` |
| Swift catches native `Error` and returns it to KMP | add `SwiftKmpFailureUsage.swift` to iOS app |
| Future macOS source set wraps failures | add `MacosFailureAdapters.kt` to `macosMain` |
| Future JVM/Windows target wraps failures | add `WindowsFailureAdapters.kt` to `jvmMain` / `windowsMain` |

---

## v4 update

The Android and iOS guides now include the missing routine-level patterns from the vanilla Kotlin/Swift guides:

```text
Logging ownership with bad/good examples
map / mapCatching / flatMap transformations
recover / recoverCatching fallback patterns
sequential orchestration with getOrThrow()
wrapping rule for every meaningful function boundary
suspend/cancellation examples
validation examples
optional value examples
routine that must throw
anti-pattern sections
```

The shared library now also includes:

```text
KmpResult.flatMap(...)
KmpFailure.toException()
```

---

## 12. `failureOrNull()` and `getOrNull()` policy

`failureOrNull()` is allowed only at consuming boundaries, for example logging/reporting when the result is not returned further.

Good:

```kotlin
fun runSyncJob() {
    val result = syncRepository.sync()

    result.failureOrNull()?.let { failure ->
        logger.error(failure.debugString())
    }
}
```

Bad:

```kotlin
fun loadUser(): KmpResult<User> {
    val result = repository.loadUser()

    result.failureOrNull()?.let { failure ->
        logger.error(failure.debugString())
    }

    return result
}
```

`getOrNull()` is intentionally not included in the default library.

Reason:

```text
getOrNull() converts a real failure into null and can hide the failure chain.
```

Use `fold` at consuming boundaries, `map`/`mapCatching` for transformations, and `getOrThrow()` only inside `kmpRunCatching` / `kmpSuspendRunCatching` orchestration.


---

## 13. SwiftKmpFailureUsage.swift

`SwiftKmpFailureUsage.swift` is an iOS app-side helper file.

It is referenced by:

```text
README.md
03_ios_swift_declarative_failure_patterns.md
04_macos_declarative_failure_patterns.md
05_windows_jvm_declarative_failure_patterns.md
```

It should now also be understandable on its own.

It contains direct instructions and examples for:

```text
converting Swift Error to KmpFailure
wrapping existing KmpFailure with iOS context
using a Swift Error wrapper for KmpFailure
logging only at consuming boundaries
anti-pattern reminders
```

Put it into the iOS app target:

```text
iosApp/Failure/SwiftKmpFailureUsage.swift
```

Do not put it into `shared/src/commonMain` or `shared/src/iosMain`.


---

## 14. macOS and Windows/JVM rules

macOS and Windows/JVM rules are included even if those targets are not active yet.

You do not need implementation files for inactive targets.

Use these files when the targets appear:

```text
04_macos_declarative_failure_patterns.md
05_windows_jvm_declarative_failure_patterns.md
MacosFailureAdapters.kt
WindowsFailureAdapters.kt
```

Recommended placement:

```text
MacosFailureAdapters.kt
shared/src/macosMain/kotlin/<your/package>/failure/

WindowsFailureAdapters.kt
shared/src/jvmMain/kotlin/<your/package>/failure/
```

or future:

```text
shared/src/windowsMain/kotlin/<your/package>/failure/
```

Short rule:

```text
No active target = no required platform adapter.
Planned target = keep the rules documented now.
Active target = add the adapter and follow the same KmpFailure chain policy.
```

macOS mostly follows the iOS/Swift model.

Windows/JVM mostly follows the Android/Kotlin model without Android-specific UI/WorkManager details.

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
