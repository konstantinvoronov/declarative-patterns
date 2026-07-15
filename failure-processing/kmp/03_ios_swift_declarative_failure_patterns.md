# 03. Declarative Failure Handling Patterns on iOS / Swift

## Swift Project Failure Standard

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
            failure: makeIosKmpFailure(
                from: error,
                source: "IosUserRepository.loadUserResult"
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


## Mandatory iOS Failure Contract

Swift project-layer failure handling must use `KmpResult` / `KmpFailure` as the standard contract.

Swift `Error`, `throws`, `NSError`, and `Result<T, Error>` are allowed only as private local Swift implementation details. They must not be used as the project-level failure contract or as the normal Swift project failure-processing model.

Any failure that crosses into shared KMP, SDK, transport, logging, diagnostics, app-facing result handling, or another project layer must be converted to `KmpFailure` and returned as `KmpResult.Failure`, or exposed to Swift through `KmpResultSwift`.

Good:

```swift
func loadUserResult() async -> KmpResult {
    do {
        let user = try await api.loadUser()
        return KmpResultSuccess(value: user)
    } catch is CancellationError {
        throw error
    } catch {
        return KmpResultFailure(
            failure: makeIosKmpFailure(
                from: error,
                source: "IosUserRepository.loadUserResult"
            )
        )
    }
}
```

Bad:

```swift
func loadUser() async throws -> User { try await api.loadUser() }
```

when this function is a project/SDK/shared failure boundary.

Bad:

```swift
func loadUser() async -> User? { try? await api.loadUser() }
```


This document defines iOS/Swift-side patterns using the shared failure library.

Use this for:

```text
Swift adapters
iOS services
iOS repositories
Swift ViewModels
Swift UI state conversion
iOS platform API calls
Swift async tasks
iOS logging/crash reporting
```

The project-level failure contract remains:

```text
KmpResult<T>
KmpFailure
```

Swift `Error`, `throws`, `NSError`, and `Result<T, Error>` are allowed only as local Swift implementation details. They must not become the project-level failure contract. Failures that cross back into the shared system must become `KmpFailure`.

---

## 1. iOS Role

iOS may do three things:

```text
1. Consume KmpResult<T> / KmpFailure from shared KMP.
2. Convert Swift Error / NSError into KmpFailure.
3. Wrap existing KmpFailure with iOS-specific context.
```

iOS must not destroy the existing failure chain.

---

## 2. Consuming KmpResult<T> from Swift

Swift must not consume `KmpResult<T>` by directly casting to generated sealed subclasses.

Kotlin can consume this structure directly:

```kotlin
sealed class KmpResult<out T> {
    data class Success<T>(val value: T) : KmpResult<T>()
    data class Failure(val failure: KmpFailure) : KmpResult<Nothing>()
}
```

Swift should consume it through the global Swift-safe projection:

```kotlin
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
```

### Why Swift needs this

`KmpResult.Failure` is `KmpResult<Nothing>` because a failure has no success value.

In Kotlin this is correct because:

```text
Nothing is a subtype of every type.
KmpResult is covariant: KmpResult<out T>.
Therefore KmpResult<Nothing> can be used as KmpResult<ShoppingCart>.
```

Kotlin/Native exports `Nothing` to Swift/Objective-C as `KotlinNothing`. This makes direct Swift casts to `KmpResultFailure` fragile when the API returns `KmpResult<ConcreteType>`.

### Bad pattern: direct sealed-subclass casting

```swift
let result = try await userApi.loadUser()

switch result {
case let success as KmpResultSuccess<User>:
    render(success.value)

case let failureResult as KmpResultFailure:
    let failure = failureResult.failure
    logger.error(failure.debugString())
    renderError(failure.message)

default:
    renderError("Unknown result")
}
```

Why bad:

```text
Swift is forced to understand Kotlin sealed subclasses.
KmpResult.Failure is exported through Kotlin/Native as a branch based on KotlinNothing.
The API result may be KmpResult<User>, while the failure branch is structurally KmpResult<Nothing>.
Runtime casts become fragile and may break when generic export names differ.
```

### Good pattern: consume KmpResultSwift

```swift
let result = try await userApi.loadUser()
let resultSwift = result.asKmpResultSwift()

switch resultSwift.status {
case .success:
    guard let user = resultSwift.value as? User else {
        renderError("KmpResult success state has no User value")
        return
    }

    render(user)

case .failure:
    guard let failure = resultSwift.failure else {
        renderError("KmpResult failure state has no KmpFailure")
        return
    }

    logger.error(failure.debugString())
    renderError(failure.message)

default:
    renderError("Unsupported KmpResultSwiftStatus")
}
```

Rule:

```text
Swift consumes KmpResult<T> through asKmpResultSwift().
Swift switches on KmpResultSwift.status.
Swift must not directly cast to KmpResultSuccess<T> or KmpResultFailure.
KmpResultSwift must preserve the original KmpFailure object, not flatten it to a string.
```

---

## 3. Swift Native Error to KmpFailure

Swift may use `throws` only inside private/native leaf functions and Apple API wrappers. Project-layer Swift functions must catch native errors and convert them into `KmpFailure` before returning or crossing a boundary.

### Bad pattern

```swift
func readSettings() -> String? {
    return try? file.readSettings()
}
```

Why bad:

```text
The native error is hidden as nil.
The failure reason is lost.
```

### Good pattern

```swift
func readSettingsFailure(_ error: Error) -> KmpFailure {
    return KmpFailure(
        message: error.localizedDescription,
        code: nil,
        retryable: false,
        cause: nil,
        source: "IosSettingsStorage.readSettings",
        origin: KmpFailureOrigin.ios,
        nativeType: String(describing: type(of: error)),
        debugInfo: String(describing: error),
        metadata: [:]
    )
}
```

Then return this as `KmpResult.Failure` through the adapter.

---

## 4. Wrapping Existing KmpFailure on iOS

If iOS receives an existing failure and adds context, wrap it.

### Bad pattern

```swift
let newFailure = KmpFailure(
    message: failure.message,
    code: failure.code,
    retryable: failure.retryable,
    cause: nil,
    source: "IosAdapter",
    origin: .ios,
    nativeType: nil,
    debugInfo: nil,
    metadata: [:]
)
```

Why bad:

```text
The previous cause chain is lost.
```

### Good pattern

Conceptual Swift:

```swift
let wrapped = failure.wrap(
    message: "IosCartAdapter.loadCart failed to return cart to Swift",
    source: "IosCartAdapter.loadCart",
    origin: .ios
)
```

If Kotlin extension methods are awkward from Swift, use a Swift helper that constructs a new `KmpFailure` with:

```text
cause = existingFailure
origin = IOS
source = current Swift function
```

---

## 5. Swift Local Throws Are Still Allowed

Inside pure Swift code, `throws` / `async throws` is still natural.

### Good local Swift pattern

```swift
func loadNativeSettings() async throws -> NativeSettings {
    do {
        return try await settingsClient.load()
    } catch is CancellationError {
        throw error
    } catch {
        throw IosContextError(
            message: "IosSettingsClient.loadNativeSettings failed to load native settings",
            cause: error
        )
    }
}
```

But when this failure crosses into the shared failure system, convert it:

```swift
do {
    let settings = try await loadNativeSettings()
    return success(settings)
} catch is CancellationError {
    throw error
} catch {
    return failure(
        makeIosKmpFailure(
            from: error,
            source: "IosSettingsAdapter.loadSettings"
        )
    )
}
```

---

## 6. Swift Optional Rule

Do not use optional values to hide real failures.

### Bad pattern

```swift
func loadUser() async -> User? {
    return try? await api.loadUser()
}
```

### Good pattern

```swift
func loadUserResult() async -> KmpResult {
    do {
        let user = try await api.loadUser()
        return KmpResultSuccess(value: user)
    } catch is CancellationError {
        // preserve cancellation if this is a cancellable async operation
        throw error
    } catch {
        return KmpResultFailure(
            failure: makeIosKmpFailure(
                from: error,
                source: "IosUserService.loadUserResult"
            )
        )
    }
}
```

Exact generated constructors may differ.

---

## 7. Swift Task / Fire-and-Forget

Fire-and-forget tasks must log or convert failure.

### Bad pattern

```swift
Task {
    try await syncService.sync()
}
```

### Good pattern

```swift
Task {
    do {
        try await syncService.sync()
    } catch is CancellationError {
        return
    } catch {
        let failure = makeIosKmpFailure(
            from: error,
            source: "IosSyncWorker.startSync"
        )

        logger.error(failure.debugString())
    }
}
```

---

## 8. iOS UI State Conversion

Use clean UI state, not raw debug string.

### Good pattern

```swift
struct ErrorViewState {
    let message: String
    let retryable: Bool
}

func toErrorViewState(_ failure: KmpFailure) -> ErrorViewState {
    ErrorViewState(
        message: failure.message,
        retryable: failure.retryable
    )
}
```

### Bad pattern

```swift
ErrorViewState(
    message: failure.debugString(),
    retryable: false
)
```

Why bad:

```text
debugString is for logs/diagnostics, not normal user UI.
```

---

## 9. iOS to Android / KMP Transition

If iOS sends failure back to KMP or Android, preserve the existing chain.

Bad:

```swift
let failure = KmpFailure(message: existingFailure.message)
```

Good:

```swift
let failure = KmpFailure(
    message: "IosBridge.sendResult failed to pass result back to KMP",
    code: existingFailure.code,
    retryable: existingFailure.retryable,
    cause: existingFailure,
    source: "IosBridge.sendResult",
    origin: .ios,
    nativeType: nil,
    debugInfo: nil,
    metadata: [:]
)
```

---

## 10. iOS AI / Developer Rules

1. Swift project-layer failure handling must use `KmpResult` / `KmpFailure` as the standard contract.
2. Swift must convert native `Error` to `KmpFailure` when crossing back into the shared project failure system.
3. Swift must not replace an existing `KmpFailure` with a plain `Error` or string.
4. Swift wraps existing `KmpFailure` with `origin = IOS` only when it adds meaningful context.
5. Swift does not use optional values to hide real failure.
6. Swift logs only at consuming boundaries.
7. Swift UI receives clean UI state, not raw debug chains.
8. Swift cancellation remains cancellation and should not become ordinary app failure.
9. Swift consumes `KmpResult<T>` through `asKmpResultSwift()`, not direct casts to `KmpResultSuccess<T>` or `KmpResultFailure`.
10. Swift switches on `KmpResultSwift.status` and preserves the original `KmpFailure` object.

---

# Supplement: Missing iOS / Swift Routine Patterns

This section fills the iOS-specific gaps from the vanilla Swift pattern set.

Swift may use `throws`, `async throws`, and native `Result` only inside private/local native leaf functions. Swift project-layer code must use `KmpResult<T>` and `KmpFailure` as the standard failure-processing model.

Swift examples below use conceptual names such as `KmpFailure`, `KmpResult`, and `KmpResultSwift`.

Do not use direct casts to generated result subclasses such as:

```swift
KmpResultSuccess<T>
KmpResultFailure
```

Use:

```swift
let resultSwift = result.asKmpResultSwift()
switch resultSwift.status { ... }
```

The exact generated Swift names may differ, but the project pattern is stable: Swift consumes the global `KmpResultSwift` projection.

---

## 11. Logging Ownership Rule

Swift functions that return `KmpResult<T>` must not log failures.

They must wrap and return.

Logging belongs only to consuming boundaries.

### Bad pattern

```swift
func loadUser() async -> KmpResult {
    let result = await userRepository.loadUser()

    if let failure = result.failureOrNull() {
        logger.error(failure.debugString())
    }

    return result
}
```

Why bad:

```text
The same failure may be logged again by the ViewModel or app boundary.
The function returns the failure further, so it is not the final consumer.
```

### Good pattern

```swift
func loadUser() async -> KmpResult {
    let result = await userRepository.loadUser()

    return result.mapFailure { failure in
        wrapIosKmpFailure(
            failure,
            message: "IosUserService.loadUser failed to load user",
            source: "IosUserService.loadUser"
        )
    }
}
```

### Good consuming boundary

```swift
let result = await userService.loadUser()
let resultSwift = result.asKmpResultSwift()

switch resultSwift.status {
case .success:
    guard let user = resultSwift.value as? User else {
        renderError("KmpResult success state has no User value")
        return
    }

    render(user)

case .failure:
    guard let failure = resultSwift.failure else {
        renderError("KmpResult failure state has no KmpFailure")
        return
    }

    logger.error(failure.debugString())
    renderError(failure.message)

default:
    renderError("Unsupported KmpResultSwiftStatus")
}
```

Rule:

```text
If Swift function returns KmpResult<T>, wrap but do not log.
If Swift function consumes KmpResult<T> and does not return it further, log/report/convert.
```

---

## 12. Function Boundary Wrapping Rule

Every meaningful iOS-side function that returns `KmpResult<T>` must wrap lower-level failure with its own context.

### Bad pattern

```swift
func loadDashboard() async -> KmpResult {
    await dashboardRepository.loadDashboard()
}
```

This is acceptable only if the function adds no responsibility.

### Good pattern

```swift
func loadDashboard() async -> KmpResult {
    let result = await dashboardRepository.loadDashboard()

    return result.mapFailure { failure in
        wrapIosKmpFailure(
            failure,
            message: "IosDashboardService.loadDashboard failed to load dashboard for iOS UI",
            source: "IosDashboardService.loadDashboard"
        )
    }
}
```

Rule:

```text
If the iOS function has its own responsibility, it must add its own wrapper.
```

---

## 13. Orchestration of Multiple KMP Results

Use `getOrThrow()`-style orchestration when several `KmpResult` values must be combined.

Preferred implementation is in `iosMain` Kotlin adapter code, because Kotlin understands the sealed `KmpResult<T>` structure directly.

Swift may consume the final `KmpResult<T>` with `asKmpResultSwift()`, but Swift should not orchestrate multiple KMP results through direct casts to `KmpResultSuccess<T>` / `KmpResultFailure`.

### Recommended: orchestration in iosMain Kotlin

```kotlin
suspend fun buildDashboardForIos(): KmpResult<DashboardState> {
    return kmpSuspendRunCatching {
        val user = userRepository.loadUser().getOrThrow()
        val bookings = bookingRepository.loadBookings(user.id).getOrThrow()
        val payments = paymentRepository.loadPayments(user.id).getOrThrow()

        DashboardState.Ready(user, bookings, payments)
    }.mapFailure { failure ->
        failure.wrapIos(
            message = "IosDashboardAdapter.buildDashboardForIos failed to build dashboard state",
            source = "IosDashboardAdapter.buildDashboardForIos",
        )
    }
}
```

### Swift-side consuming pattern

```swift
let result = try await dashboardAdapter.buildDashboardForIos()
let resultSwift = result.asKmpResultSwift()

switch resultSwift.status {
case .success:
    guard let state = resultSwift.value as? DashboardState else {
        renderError("KmpResult success state has no DashboardState value")
        return
    }

    render(state)

case .failure:
    guard let failure = resultSwift.failure else {
        renderError("KmpResult failure state has no KmpFailure")
        return
    }

    let wrapped = failure.wrapIos(
        message: "IosDashboardViewModel.loadDashboard failed to consume dashboard state",
        code: nil,
        retryable: failure.retryable,
        source: "IosDashboardViewModel.loadDashboard",
        nativeType: nil,
        debugInfo: nil,
        metadata: [:]
    )

    logger.error(wrapped.debugString())
    renderError(wrapped.message)

default:
    renderError("Unsupported KmpResultSwiftStatus")
}
```

### Bad nested style

```swift
func buildDashboard() async -> KmpResult {
    let userResult = await userRepository.loadUser()

    switch userResult {
    case let userSuccess as KmpResultSuccess<User>:
        let bookingsResult = await bookingRepository.loadBookings(userId: userSuccess.value.id)

        switch bookingsResult {
        case let bookingsSuccess as KmpResultSuccess<[Booking]>:
            return KmpResultSuccess(
                value: DashboardState.ready(userSuccess.value, bookingsSuccess.value)
            )

        case let failure as KmpResultFailure:
            return failure

        default:
            return KmpResultFailure(failure: unknownFailure())
        }

    case let failure as KmpResultFailure:
        return failure

    default:
        return KmpResultFailure(failure: unknownFailure())
    }
}
```

Why bad:

```text
Nested switch creates pyramids and often loses unified function context.
It also relies on fragile direct Swift casts to Kotlin sealed subclasses.
Swift should not manually branch on KmpResultSuccess<T> / KmpResultFailure.
```

Rule:

```text
Use getOrThrow-style orchestration for multiple KmpResult values in Kotlin.
Swift consumes the final result through asKmpResultSwift().
Wrap once with iOS function context.
```

---

## 14. map / mapCatching / flatMap on KmpResult

Swift-side code should use the same compositional concepts as KMP code.

If direct Kotlin extensions are awkward from Swift, create Swift helper functions with the same meaning.

```text
map          = safe success transformation
mapCatching  = success transformation that may throw
flatMap      = success transformation that returns another KmpResult
mapFailure   = wrap/add failure context
```

### map: safe transformation

```swift
func loadUserName() async -> KmpResult {
    let result = await userRepository.loadUser()

    return result.map { user in
        user.name
    }
}
```

### mapCatching: transformation may throw

```swift
func loadUserViewModel() async -> KmpResult {
    let result = await userRepository.loadUser()

    return result
        .mapCatching { user in
            try user.toViewModel()
        }
        .mapFailure { failure in
            wrapIosKmpFailure(
                failure,
                message: "IosUserMapper.loadUserViewModel failed to map user to view model",
                source: "IosUserMapper.loadUserViewModel"
            )
        }
}
```

### flatMap: next operation returns KmpResult

```swift
func loadUserWithAvatar() async -> KmpResult {
    let result = await userRepository.loadUser()

    return result
        .flatMap { user in
            avatarRepository.loadAvatar(userId: user.id)
                .map { avatar in
                    UserWithAvatar(user: user, avatar: avatar)
                }
        }
        .mapFailure { failure in
            wrapIosKmpFailure(
                failure,
                message: "IosUserService.loadUserWithAvatar failed to load user with avatar",
                source: "IosUserService.loadUserWithAvatar"
            )
        }
}
```

### Bad pattern: switch for every transformation

```swift
func loadUserName() async -> KmpResult {
    let result = await userRepository.loadUser()

    switch result {
    case let success as KmpResultSuccess<User>:
        return KmpResultSuccess(value: success.value.name)
    case let failure as KmpResultFailure:
        return failure
    default:
        return KmpResultFailure(failure: unknownFailure())
    }
}
```

Why bad:

```text
Simple transformations should not require final decision-style switch.
Use map/mapCatching/flatMap helpers instead.
```

---

## 15. Fallback Patterns

Use `recover` or `recoverCatching` only when fallback is a valid success.

### Good pattern: remote then local cache

```swift
func loadConfig() async -> KmpResult {
    let result = await remoteConfigRepository.loadConfig()

    return result
        .recoverCatching { _ in
            try localConfigRepository.loadConfig().getOrThrow()
        }
        .mapFailure { failure in
            wrapIosKmpFailure(
                failure,
                message: "IosConfigRepository.loadConfig failed to load remote config and fallback local config",
                source: "IosConfigRepository.loadConfig"
            )
        }
}
```

### Bad pattern: hiding real failure

```swift
func loadUser() async -> KmpResult {
    let result = await userRepository.loadUser()

    return result.recover { _ in
        User.empty()
    }
}
```

Why bad:

```text
User.empty() hides a real loading failure unless it is a valid business state.
```

Rule:

```text
recover means failure is acceptable and can become a valid success.
recoverCatching means fallback may itself fail.
```

---

## 16. Optional Values

Do not use optional values to hide real failure.

### Bad pattern

```swift
func loadUser() async -> User? {
    try? await api.loadUser()
}
```

### Good pattern

```swift
func findCachedUser() async -> KmpResult {
    do {
        let user: User? = try await cache.findUser()

        return KmpResultSuccess(value: user)
    } catch is CancellationError {
        throw error
    } catch {
        return KmpResultFailure(
            failure: makeIosKmpFailure(
                from: error,
                source: "IosUserCache.findCachedUser"
            )
        )
    }
}
```

Meaning:

```text
KmpResult.Failure = cache read failed.
Success(nil) = cache works, but user is absent.
```

---

## 17. Validation

Validation should return `KmpResult<T>` when caller should handle validation failure as a value.

```swift
func validateEmail(_ email: String) -> KmpResult {
    if email.contains("@") {
        return KmpResultSuccess(value: email)
    } else {
        return KmpResultFailure(
            failure: KmpFailure(
                message: "IosSignupValidator.validateEmail failed: email does not contain @",
                code: "VALIDATION_ERROR",
                retryable: false,
                cause: nil,
                source: "IosSignupValidator.validateEmail",
                origin: .ios,
                nativeType: nil,
                debugInfo: nil,
                metadata: [:]
            )
        )
    }
}
```

---

## 18. Routine That Must Throw

Some Swift APIs require throwing.

Use this only at integration boundaries.

### Good pattern

```swift
func loadUserOrThrow() async throws -> User {
    do {
        return try await userRepository.loadUser().getOrThrow()
    } catch is CancellationError {
        throw error
    } catch let failureError as KmpFailureException {
        throw failureError.failure.wrap(
            message: "IosUserService.loadUserOrThrow failed to load user",
            source: "IosUserService.loadUserOrThrow",
            origin: .ios
        ).toException()
    }
}
```

If Swift cannot use `KmpFailureException` directly, create a small Swift `Error` wrapper that contains `KmpFailure`.

### Bad pattern

```swift
func loadUserOrThrow() async throws -> User {
    try await userRepository.loadUser().getOrThrow()
}
```

Why bad:

```text
The throwing boundary does not add iOS-specific context.
```

---

## 19. iOS Anti-Patterns

### Returning nil to hide failure

Bad:

```swift
func loadUser() async -> User? {
    try? await api.loadUser()
}
```

Good:

```swift
func loadUserResult() async -> KmpResult {
    do {
        let user = try await api.loadUser()
        return KmpResultSuccess(value: user)
    } catch is CancellationError {
        throw error
    } catch {
        return KmpResultFailure(
            failure: makeIosKmpFailure(
                from: error,
                source: "IosUserApi.loadUserResult"
            )
        )
    }
}
```

### Losing cause

Bad:

```swift
let failure = KmpFailure(
    message: existingFailure.message,
    cause: nil
)
```

Good:

```swift
let failure = wrapIosKmpFailure(
    existingFailure,
    message: "IosUserService.loadUser failed to load user",
    source: "IosUserService.loadUser"
)
```

### Vague messages

Bad:

```swift
wrapIosKmpFailure(failure, message: "Failed", source: "IosService")
wrapIosKmpFailure(failure, message: "Error", source: "IosService")
wrapIosKmpFailure(failure, message: "Something went wrong", source: "IosService")
```

Good:

```swift
wrapIosKmpFailure(
    failure,
    message: "IosBookingRepository.createBooking failed to create booking for scheduleId=\(scheduleId)",
    source: "IosBookingRepository.createBooking"
)
```

### Direct KmpResult subclass casts in Swift

Bad:

```swift
switch result {
case let success as KmpResultSuccess<User>:
    render(success.value)

case let failure as KmpResultFailure:
    renderError(failure.failure.message)

default:
    renderError("Unknown result")
}
```

Good:

```swift
let resultSwift = result.asKmpResultSwift()

switch resultSwift.status {
case .success:
    guard let user = resultSwift.value as? User else {
        renderError("KmpResult success state has no User value")
        return
    }

    render(user)

case .failure:
    guard let failure = resultSwift.failure else {
        renderError("KmpResult failure state has no KmpFailure")
        return
    }

    renderError(failure.message)

default:
    renderError("Unsupported KmpResultSwiftStatus")
}
```

Why bad:

```text
Swift should not rely on Kotlin sealed-subclass casts.
KmpResult.Failure is based on KmpResult<Nothing>, which Kotlin/Native exports through KotlinNothing.
Use the global KmpResultSwift projection instead.
```

### Logging before returning

Bad:

```swift
func loadUser() async -> KmpResult {
    let result = await repository.loadUser()

    if let failure = result.failureOrNull() {
        logger.error(failure.debugString())
    }

    return result
}
```

Good:

```swift
func loadUser() async -> KmpResult {
    let result = await repository.loadUser()

    return result.mapFailure { failure in
        wrapIosKmpFailure(
            failure,
            message: "IosUserService.loadUser failed to load user",
            source: "IosUserService.loadUser"
        )
    }
}
```


---

## 20. KmpResultSwift Implementation Rules

`KmpResultSwift` is a projection, not a second result contract.

Good:

```kotlin
is KmpResult.Failure -> KmpResultSwift(
    status = KmpResultSwiftStatus.FAILURE,
    value = null,
    failure = failure,
)
```

Bad:

```kotlin
is KmpResult.Failure -> KmpResultSwift(
    status = KmpResultSwiftStatus.FAILURE,
    value = null,
    failure = KmpFailure(message = failure.message),
)
```

Why bad:

```text
The bad version creates a new failure and loses the original cause chain.
KmpResultSwift must preserve the original KmpFailure object.
```

Rule:

```text
KmpResult<T> remains the shared contract.
KmpResultSwift<T> is only the Swift-safe projection.
Do not flatten KmpFailure into a string or recreate it without cause.
```


---

## 21. iOS failureOrNull and getOrNull Policy

`failureOrNull()` is allowed only when Swift consumes the result and does not return it further.

Good conceptual pattern:

```swift
let result = await syncRepository.sync()

if let failure = result.failureOrNull() {
    logger.error(failure.debugString())
}
```

Bad:

```swift
func loadUser() async -> KmpResult {
    let result = await repository.loadUser()

    if let failure = result.failureOrNull() {
        logger.error(failure.debugString())
    }

    return result
}
```

Why bad:

```text
The function still returns KmpResult.
It should wrap and return, not log.
```

`getOrNull()` should not be used as a normal Swift/KMP pattern.

Bad:

```swift
let user = await userRepository.loadUser().getOrNull()
```

Why bad:

```text
A real failure becomes nil.
The Swift layer loses the KmpFailure chain.
The UI cannot distinguish absence from failure.
```

Good:

```swift
let result = await userRepository.loadUser()
let resultSwift = result.asKmpResultSwift()

switch resultSwift.status {
case .success:
    guard let user = resultSwift.value as? User else {
        renderError("KmpResult success state has no User value")
        return
    }

    render(user)

case .failure:
    guard let failure = resultSwift.failure else {
        renderError("KmpResult failure state has no KmpFailure")
        return
    }

    logger.error(failure.debugString())
    renderError(failure.message)

default:
    renderError("Unsupported KmpResultSwiftStatus")
}
```

Rule:

```text
Use `failureOrNull()` only at consuming boundaries when you are not returning the result further.
For normal Swift consumption of `KmpResult<T>`, prefer `asKmpResultSwift()`.
Do not use `getOrNull()` to hide `KmpFailure`.
```

---

## KmpFailureException Must Not Cross Swift Boundary

KMP must convert expected failures to `KmpResult.Failure` before Swift consumes them.

Swift consumes shared results through:

```swift
let resultSwift = result.asKmpResultSwift()
```

Bad:

```kotlin
throw KmpFailure(...).toException()
```

from Swift-facing KMP code.

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
