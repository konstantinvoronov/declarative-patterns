# 02. Declarative Failure Handling Patterns on Android

## Mandatory Android Failure Contract

Android project-layer failures must use `KmpFailure`.

Android `Throwable` is allowed only locally. Before the failure crosses a repository, service, ViewModel, SDK, logging, diagnostics, or shared KMP boundary, convert it to `KmpFailure` and return `KmpResult.Failure`.

Good:

```kotlin
suspend fun loadUser(): KmpResult<User> {
    return try {
        KmpResult.Success(androidApi.loadUser())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        KmpResult.Failure(
            e.toKmpFailure(
                message = "AndroidUserRepository.loadUser failed to load user",
                code = "ANDROID_LOAD_USER_FAILED",
                retryable = false,
                source = "AndroidUserRepository.loadUser",
                origin = KmpFailureOrigin.ANDROID,
            )
        )
    }
}
```

Bad:

```kotlin
suspend fun loadUser(): User = androidApi.loadUser()
```

Bad:

```kotlin
catch (e: Throwable) { return null }
```


This document defines Android-side patterns using the shared failure library.

Use this for:

```text
Android ViewModels
Android presenters
Android repositories that call platform APIs
Android services
Android background workers
Android UI state conversion
Android logging/crash reporting
```

The project-level failure contract remains:

```kotlin
KmpResult<T>
KmpFailure
```

Do not replace `KmpFailure` with Android-only exceptions at the project boundary.

---

## 1. Android Role

Android may do three things:

```text
1. Consume KmpResult<T> from shared KMP code.
2. Convert Android Throwable into KmpFailure.
3. Wrap existing KmpFailure with Android-specific context.
```

Android should not destroy the existing chain.

---

## 2. Consuming Shared KMP Result in ViewModel

### Bad pattern

```kotlin
viewModelScope.launch {
    try {
        val user = repository.loadUser()
        state.value = UserState.Ready(user)
    } catch (e: Throwable) {
        state.value = UserState.Error("Something went wrong")
    }
}
```

Why bad:

```text
The repository should not throw expected failures.
The KmpFailure chain is lost.
The message is vague.
```

### Good pattern

```kotlin
viewModelScope.launch {
    state.value = UserState.Loading

    userRepository.loadUser().fold(
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

---


---

## Android Does Not Use KmpResultSwift

`KmpResultSwift<T>` exists for Swift/iOS/macOS interop only.

Android should consume `KmpResult<T>` directly:

```kotlin
userRepository.loadUser().fold(
    onSuccess = { user ->
        state.value = UserState.Ready(user)
    },
    onFailure = { failure ->
        logger.error(failure.debugString())
        state.value = UserState.Error(failure.message)
    },
)
```

Android may also use sealed `when` when it is clearer:

```kotlin
when (val result = userRepository.loadUser()) {
    is KmpResult.Success -> render(result.value)
    is KmpResult.Failure -> renderError(result.failure.message)
}
```

Do not do this on Android:

```kotlin
val resultSwift = result.asKmpResultSwift()
```

Why bad:

```text
Android/Kotlin understands sealed classes, covariance, and Nothing.
KmpResultSwift is only a Swift-safe projection.
Using it in Android code adds noise without solving a real Android problem.
```

## 3. Android Should Wrap Only When It Adds Context

### Bad pattern

```kotlin
fun loadUserScreen() {
    userRepository.loadUser().fold(
        onSuccess = { render(it) },
        onFailure = { failure ->
            logger.error(failure.debugString())
            renderError(failure.message)
        }
    )
}
```

This is not always bad. It is fine if Android is only consuming.

But if Android is performing its own operation, it should add context.

### Good pattern

```kotlin
fun loadUserScreen() {
    userRepository.loadUser()
        .mapFailure { failure ->
            failure.wrap(
                message = "AndroidUserViewModel.loadUserScreen failed to load user screen",
                source = "AndroidUserViewModel.loadUserScreen",
                origin = KmpFailureOrigin.ANDROID,
            )
        }
        .fold(
            onSuccess = { user ->
                render(user)
            },
            onFailure = { failure ->
                logger.error(failure.debugString())
                renderError(failure.message)
            }
        )
}
```

---

## 4. Android Platform API Boundary

Use `kmpRunCatching` or explicit `try/catch`.

### Bad pattern

```kotlin
fun readSettings(): KmpResult<String> {
    return try {
        KmpResult.Success(file.readText())
    } catch (e: Exception) {
        KmpResult.Failure(
            KmpFailure(message = "Failed")
        )
    }
}
```

### Good pattern

```kotlin
fun readSettings(): KmpResult<String> {
    return kmpRunCatching {
        file.readText()
    }.mapFailure { failure ->
        failure.wrap(
            message = "AndroidSettingsStorage.readSettings failed to read settings file",
            source = "AndroidSettingsStorage.readSettings",
            origin = KmpFailureOrigin.ANDROID,
        )
    }
}
```

### Good pattern with classification

```kotlin
fun readSettings(): KmpResult<String> {
    return try {
        KmpResult.Success(file.readText())
    } catch (e: FileNotFoundException) {
        KmpResult.Failure(
            e.toKmpFailure(
                message = "AndroidSettingsStorage.readSettings failed because settings file was not found",
                code = "SETTINGS_FILE_NOT_FOUND",
                retryable = false,
                source = "AndroidSettingsStorage.readSettings",
                origin = KmpFailureOrigin.ANDROID,
            )
        )
    } catch (e: IOException) {
        KmpResult.Failure(
            e.toKmpFailure(
                message = "AndroidSettingsStorage.readSettings failed to read settings file",
                code = "SETTINGS_FILE_READ_FAILED",
                retryable = true,
                source = "AndroidSettingsStorage.readSettings",
                origin = KmpFailureOrigin.ANDROID,
            )
        )
    }
}
```

---

## 5. Android Background Worker

### Bad pattern

```kotlin
override suspend fun doWork(): Result {
    syncRepository.sync()
    return Result.success()
}
```

Why bad:

```text
Failure may be ignored or thrown without preserving KmpFailure semantics.
```

### Good pattern

```kotlin
override suspend fun doWork(): Result {
    return syncRepository.sync().fold(
        onSuccess = {
            Result.success()
        },
        onFailure = { failure ->
            val wrapped = failure.wrap(
                message = "AndroidSyncWorker.doWork failed during background sync",
                source = "AndroidSyncWorker.doWork",
                origin = KmpFailureOrigin.ANDROID,
            )

            logger.error(wrapped.debugString())

            if (wrapped.retryable) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    )
}
```

---

## 6. Android UI State Conversion

Convert failure into explicit UI state at the edge.

### Good pattern

```kotlin
data class ErrorUiState(
    val message: String,
    val retryable: Boolean,
)

fun KmpFailure.toErrorUiState(): ErrorUiState {
    return ErrorUiState(
        message = message,
        retryable = retryable,
    )
}
```

Use:

```kotlin
state.value = failure.toErrorUiState()
```

Do not let UI parse debug information.

Bad:

```kotlin
state.value = ErrorUiState(
    message = failure.debugString(),
    retryable = false,
)
```

Why bad:

```text
debugString is for logs, not user-facing UI.
```

---

## 7. Android to iOS / KMP Transition

If Android receives a `KmpFailure` from another platform, preserve it.

### Bad pattern

```kotlin
val androidFailure = KmpFailure(
    message = receivedFailure.message
)
```

### Good pattern

```kotlin
val androidFailure = receivedFailure.wrap(
    message = "AndroidBridge.handleIosResult failed to handle iOS result",
    source = "AndroidBridge.handleIosResult",
    origin = KmpFailureOrigin.ANDROID,
)
```

---

## 8. Android AI / Developer Rules

1. Android consumes `KmpResult<T>` with `fold`.
2. Android does not replace `KmpFailure` with `Throwable`.
3. Android may convert `Throwable` to `KmpFailure` at Android platform API boundaries.
4. Android may wrap existing failure with `origin = ANDROID`.
5. Android logs only at consuming boundaries.
6. Android UI receives clean UI state, not raw debug chains.
7. Android workers convert `KmpFailure.retryable` into retry/failure behavior.

---

# Supplement: Missing Android Routine Patterns

This section fills the Android-specific gaps from the vanilla Kotlin pattern set.

The Android guide must not only show final consumption in ViewModel. It must also show how Android-side repositories, services, adapters, and workers return `KmpResult<T>` while preserving the same declarative rules.

---

## 9. Logging Ownership Rule

Functions that return `KmpResult<T>` must not log failures.

They must wrap and return.

Logging belongs only to consuming boundaries.

### Bad pattern

```kotlin
class AndroidUserRepository(
    private val userApi: UserApi,
    private val logger: Logger,
) {
    suspend fun loadUser(): KmpResult<User> {
        val result = userApi.loadUser()

        result.failureOrNull()?.let { failure ->
            logger.error(failure.debugString())
        }

        return result
    }
}
```

Why bad:

```text
The same failure may be logged again by the ViewModel, worker, or app boundary.
This creates duplicate logs and unclear ownership.
The repository returns the failure further, so it is not the final consumer.
```

### Good pattern

```kotlin
class AndroidUserRepository(
    private val userApi: UserApi,
) {
    suspend fun loadUser(): KmpResult<User> {
        return userApi.loadUser()
            .mapFailure { failure ->
                failure.wrapAndroid(
                    message = "AndroidUserRepository.loadUser failed to load user",
                    source = "AndroidUserRepository.loadUser",
                )
            }
    }
}
```

### Good consuming boundary

```kotlin
viewModelScope.launch {
    userRepository.loadUser().fold(
        onSuccess = { user ->
            state.value = UserState.Ready(user)
        },
        onFailure = { failure ->
            logger.error(failure.debugString())
            state.value = UserState.Error(failure.message)
        }
    )
}
```

Rule:

```text
If Android function returns KmpResult<T>, wrap but do not log.
If Android function consumes KmpResult<T> and does not return it further, log/report/convert.
```

---

## 10. Transformation Combinators

Android code should use the same `KmpResult` combinators as shared KMP code.

```text
map          = safe success transformation
mapCatching  = success transformation that may throw
flatMap      = success transformation that returns another KmpResult
mapFailure   = wrap/add failure context
```

### map: safe transformation

```kotlin
fun loadUserName(): KmpResult<String> {
    return userRepository.loadUser()
        .map { user ->
            user.name
        }
}
```

### mapCatching: transformation may throw

```kotlin
fun loadUserUiModel(): KmpResult<UserUiModel> {
    return userRepository.loadUser()
        .mapCatching { user ->
            user.toUiModel()
        }
        .mapFailure { failure ->
            failure.wrapAndroid(
                message = "AndroidUserMapper.loadUserUiModel failed to map user to UI model",
                source = "AndroidUserMapper.loadUserUiModel",
            )
        }
}
```

### flatMap: next step returns KmpResult

```kotlin
fun loadUserWithAvatar(): KmpResult<UserWithAvatar> {
    return userRepository.loadUser()
        .flatMap { user ->
            avatarRepository.loadAvatar(user.id)
                .map { avatar ->
                    UserWithAvatar(user, avatar)
                }
        }
        .mapFailure { failure ->
            failure.wrapAndroid(
                message = "AndroidUserService.loadUserWithAvatar failed to load user with avatar",
                source = "AndroidUserService.loadUserWithAvatar",
            )
        }
}
```

### Bad pattern: nested fold for transformations

```kotlin
fun loadUserWithAvatar(): KmpResult<UserWithAvatar> {
    return userRepository.loadUser().fold(
        onSuccess = { user ->
            avatarRepository.loadAvatar(user.id).fold(
                onSuccess = { avatar ->
                    KmpResult.Success(UserWithAvatar(user, avatar))
                },
                onFailure = { failure ->
                    KmpResult.Failure(failure)
                }
            )
        },
        onFailure = { failure ->
            KmpResult.Failure(failure)
        }
    )
}
```

Why bad:

```text
Nested fold is verbose and easy to get wrong.
fold should be used mostly at consuming boundaries.
```

---

## 11. Fallback Patterns

Use `recover` or `recoverCatching` only when fallback is a valid success.

### Good pattern: remote then local cache

```kotlin
suspend fun loadConfig(): KmpResult<AppConfig> {
    return remoteConfigRepository.loadConfig()
        .recoverCatching {
            localConfigRepository.loadConfig().getOrThrow()
        }
        .mapFailure { failure ->
            failure.wrapAndroid(
                message = "AndroidConfigRepository.loadConfig failed to load remote config and fallback local config",
                source = "AndroidConfigRepository.loadConfig",
            )
        }
}
```

### Bad pattern: hiding real failure

```kotlin
suspend fun loadUser(): KmpResult<User> {
    return userRepository.loadUser()
        .recover {
            User.empty()
        }
}
```

Why bad:

```text
User.empty() hides a real data-loading failure unless it is a valid business state.
```

Rule:

```text
recover means failure is acceptable and can become a valid success.
recoverCatching means fallback may itself fail.
```

---

## 12. Sequential Orchestration

Use `getOrThrow()` inside `kmpRunCatching` or `kmpSuspendRunCatching`.

This is important for Android ViewModels, use cases, and services that aggregate multiple values.

### Bad nested style

```kotlin
suspend fun buildDashboard(): KmpResult<DashboardState> {
    return userRepository.loadUser().fold(
        onSuccess = { user ->
            bookingRepository.loadBookings(user.id).fold(
                onSuccess = { bookings ->
                    paymentRepository.loadPayments(user.id).map { payments ->
                        DashboardState.Ready(user, bookings, payments)
                    }
                },
                onFailure = { failure -> KmpResult.Failure(failure) }
            )
        },
        onFailure = { failure -> KmpResult.Failure(failure) }
    )
}
```

### Good pattern

```kotlin
suspend fun buildDashboard(): KmpResult<DashboardState> {
    return kmpSuspendRunCatching {
        val user = userRepository.loadUser().getOrThrow()
        val bookings = bookingRepository.loadBookings(user.id).getOrThrow()
        val payments = paymentRepository.loadPayments(user.id).getOrThrow()

        DashboardState.Ready(user, bookings, payments)
    }.mapFailure { failure ->
        failure.wrapAndroid(
            message = "AndroidDashboardUseCase.buildDashboard failed to build dashboard state",
            source = "AndroidDashboardUseCase.buildDashboard",
        )
    }
}
```

Rule:

```text
Use getOrThrow() inside catching helpers for sequential orchestration.
Wrap once at the Android function boundary.
```

---

## 13. Wrapping Rule for Android Functions

Every meaningful Android-side function that returns `KmpResult<T>` must wrap lower-level failure with its own context.

### Weak pattern

```kotlin
suspend fun loadDashboard(): KmpResult<DashboardState> {
    return dashboardRepository.loadDashboard()
}
```

This is acceptable only if this function adds no responsibility.

### Good pattern

```kotlin
suspend fun loadDashboard(): KmpResult<DashboardState> {
    return dashboardRepository.loadDashboard()
        .mapFailure { failure ->
            failure.wrapAndroid(
                message = "AndroidDashboardUseCase.loadDashboard failed to load dashboard for Android UI",
                source = "AndroidDashboardUseCase.loadDashboard",
            )
        }
}
```

Rule:

```text
If the Android function has its own responsibility, it must add its own wrapper.
```

---

## 14. Android Suspend Function and Cancellation

Android suspend functions should preserve cancellation.

### Bad pattern

```kotlin
suspend fun readUserFromDisk(): KmpResult<User> {
    return try {
        KmpResult.Success(storage.readUser())
    } catch (e: Throwable) {
        KmpResult.Failure(e.toAndroidKmpFailure())
    }
}
```

Why bad:

```text
CancellationException is caught and converted into app failure.
```

### Good pattern

```kotlin
suspend fun readUserFromDisk(): KmpResult<User> {
    return kmpSuspendRunCatching {
        storage.readUser()
    }.mapFailure { failure ->
        failure.wrapAndroid(
            message = "AndroidUserStorage.readUserFromDisk failed to read user from disk",
            source = "AndroidUserStorage.readUserFromDisk",
        )
    }
}
```

### Good pattern with explicit catch

```kotlin
suspend fun readUserFromDisk(): KmpResult<User> {
    return try {
        KmpResult.Success(storage.readUser())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        KmpResult.Failure(
            e.toAndroidKmpFailure(
                message = "AndroidUserStorage.readUserFromDisk failed to read user from disk",
                source = "AndroidUserStorage.readUserFromDisk",
            )
        )
    }
}
```

---

## 15. Validation

Validation should return `KmpResult<T>` when caller should handle validation failure as a value.

```kotlin
fun validateEmail(email: String): KmpResult<String> {
    return if ("@" in email) {
        KmpResult.Success(email)
    } else {
        KmpResult.Failure(
            KmpFailure(
                message = "AndroidSignupValidator.validateEmail failed: email does not contain @",
                code = "VALIDATION_ERROR",
                source = "AndroidSignupValidator.validateEmail",
                origin = KmpFailureOrigin.ANDROID,
            )
        )
    }
}
```

Usage inside a bigger routine:

```kotlin
fun buildSignupRequest(email: String): KmpResult<SignupRequest> {
    return kmpRunCatching {
        val validEmail = validateEmail(email).getOrThrow()
        SignupRequest(email = validEmail)
    }.mapFailure { failure ->
        failure.wrapAndroid(
            message = "AndroidSignupForm.buildSignupRequest failed to build signup request",
            source = "AndroidSignupForm.buildSignupRequest",
        )
    }
}
```

---

## 16. Optional Values

Do not use failure for normal absence.

### Good pattern

```kotlin
fun findCachedUser(): KmpResult<User?> {
    return kmpRunCatching {
        cache.findUser()
    }.mapFailure { failure ->
        failure.wrapAndroid(
            message = "AndroidUserCache.findCachedUser failed to read cached user",
            source = "AndroidUserCache.findCachedUser",
        )
    }
}
```

Meaning:

```text
KmpResult.Failure = cache read failed.
KmpResult.Success(null) = cache works, but user is absent.
```

### Bad pattern

```kotlin
fun findCachedUser(): KmpResult<User> {
    val user = cache.findUser()

    return if (user == null) {
        KmpResult.Failure(KmpFailure("User not cached"))
    } else {
        KmpResult.Success(user)
    }
}
```

Why bad:

```text
Normal absence is treated as operation failure.
```

---

## 17. Routine That Must Throw

Some Android APIs require throwing.

Use this only at integration boundaries.

### Good pattern

```kotlin
suspend fun loadUserOrThrow(): User {
    return try {
        userRepository.loadUser().getOrThrow()
    } catch (e: CancellationException) {
        throw e
    } catch (e: KmpFailureException) {
        throw e.failure.wrapAndroid(
            message = "AndroidUserService.loadUserOrThrow failed to load user",
            source = "AndroidUserService.loadUserOrThrow",
        ).toException()
    }
}
```

### Bad pattern

```kotlin
suspend fun loadUserOrThrow(): User {
    return userRepository.loadUser().getOrThrow()
}
```

Why bad:

```text
The throwing boundary does not add Android-specific context.
```

---

## 18. Android Anti-Patterns

### Returning null to hide failure

Bad:

```kotlin
suspend fun loadUser(): User? {
    return try {
        api.loadUser()
    } catch (e: Throwable) {
        null
    }
}
```

Good:

```kotlin
suspend fun loadUser(): KmpResult<User> {
    return kmpSuspendRunCatching {
        api.loadUser()
    }.mapFailure { failure ->
        failure.wrapAndroid(
            message = "AndroidUserApi.loadUser failed to load user",
            source = "AndroidUserApi.loadUser",
        )
    }
}
```

### Losing cause

Bad:

```kotlin
KmpFailure(message = "Repository failed")
```

Good:

```kotlin
failure.wrapAndroid(
    message = "AndroidUserRepository.loadUser failed to load user",
    source = "AndroidUserRepository.loadUser",
)
```

### Vague messages

Bad:

```kotlin
failure.wrapAndroid("Failed")
failure.wrapAndroid("Error")
failure.wrapAndroid("Something went wrong")
```

Good:

```kotlin
failure.wrapAndroid(
    message = "AndroidBookingRepository.createBooking failed to create booking for scheduleId=$scheduleId",
    source = "AndroidBookingRepository.createBooking",
)
```

### Logging before returning

Bad:

```kotlin
fun loadUser(): KmpResult<User> {
    return repository.loadUser()
        .mapFailure {
            logger.error(it.debugString())
            it.wrapAndroid("AndroidUserService.loadUser failed")
        }
}
```

Good:

```kotlin
fun loadUser(): KmpResult<User> {
    return repository.loadUser()
        .mapFailure { failure ->
            failure.wrapAndroid(
                message = "AndroidUserService.loadUser failed to load user",
                source = "AndroidUserService.loadUser",
            )
        }
}
```


---

## 19. Android failureOrNull and getOrNull Policy

`failureOrNull()` is allowed only when Android consumes the result and does not return it further.

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

`getOrNull()` should not be used as a normal Android pattern.

Bad:

```kotlin
val user: User? = userRepository.loadUser().getOrNull()
```

Why bad:

```text
A real failure becomes null.
The Android layer loses the KmpFailure chain.
UI cannot distinguish absence from failure.
```

Good:

```kotlin
userRepository.loadUser().fold(
    onSuccess = { user ->
        state.value = UserState.Ready(user)
    },
    onFailure = { failure ->
        logger.error(failure.debugString())
        state.value = UserState.Error(failure.message)
    }
)
```

Rule:

```text
Use fold for Android UI/worker consuming boundaries.
Do not use getOrNull() to hide KmpFailure.
```

---

## KmpFailureException Is Not Normal Android Flow

Android/Kotlin consumes `KmpResult<T>` directly with `fold`, `flatMap`, `map`, or sealed `when`.

Expected failures must return:

```kotlin
KmpResult.Failure(failure)
```

Do not use `KmpFailureException` as normal result delivery.

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
