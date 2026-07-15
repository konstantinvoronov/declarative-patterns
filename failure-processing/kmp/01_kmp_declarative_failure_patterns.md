# 01. Declarative Failure Handling Patterns in KMP

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

Native platform errors are allowed only as temporary local implementation details.

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


This document defines code-generation patterns for shared Kotlin Multiplatform failure handling.

Use this for:

```text
commonMain
shared repositories
shared services
shared use cases
shared event processors
shared parsing/mapping code
shared business logic
shared platform-neutral adapters
```

Project-level contract:

```kotlin
KmpResult<T>
KmpFailure
```

Do not use Kotlin `Result<T>` as the project-level contract.

---

## 1. Core Rule

If failure is part of the operation contract, return:

```kotlin
KmpResult<T>
```

Default failure delivery:

```kotlin
return KmpResult.Failure(failure)
```

Not:

```kotlin
throw failure.toException()
```

### Good

```kotlin
fun loadUser(): KmpResult<User> {
    return kmpRunCatching(
        source = "UserRepository.loadUser",
    ) {
        api.loadUser()
    }
}
```

### Bad

```kotlin
fun loadUser(): User? {
    return try {
        api.loadUser()
    } catch (e: Throwable) {
        null
    }
}
```

### Bad

```kotlin
fun loadUser(): User {
    return api.loadUser()
}
```

### Bad

```kotlin
fun loadUser(): Result<User> {
    return runCatching {
        api.loadUser()
    }
}
```

---

## 2. Function Signature Decides Failure Style

### KmpResult function

If a function returns `KmpResult<T>`, return failures as `KmpResult.Failure`.

Good:

```kotlin
fun parseUserDto(json: String): KmpResult<UserDto> {
    return kmpRunCatching(
        source = "UserMapper.parseUserDto",
    ) {
        jsonFormat.decodeFromString<UserDto>(json)
    }
}
```

Bad:

```kotlin
fun parseUserDto(json: String): KmpResult<UserDto> {
    return try {
        KmpResult.Success(jsonFormat.decodeFromString<UserDto>(json))
    } catch (e: Throwable) {
        throw e.toKmpFailure(
            message = "Failed to parse user",
            source = "UserMapper.parseUserDto",
        ).toException()
    }
}
```

### Nullable function

If a function returns `T?`, it must not throw `KmpFailureException`.

Bad:

```kotlin
private suspend fun mapStartResponse(
    startResponse: StartResponse?,
): Wrapped_SDKStartConfig? {
    val user = startResponse?.user ?: throw KmpFailure(
        message = "User config is missing",
        code = "START_CONFIG_USER_MISSING",
        source = "UserConfig.mapStartResponse",
    ).toException()

    return user.toWrappedConfig()
}
```

Good:

```kotlin
private suspend fun mapStartResponse(
    startResponse: StartResponse?,
): KmpResult<Wrapped_SDKStartConfig> {
    val user = startResponse?.user ?: return KmpResult.Failure(
        KmpFailure(
            message = "UserConfig.mapStartResponse failed because user config is missing",
            code = "START_CONFIG_USER_MISSING",
            retryable = true,
            source = "UserConfig.mapStartResponse",
        )
    )

    return KmpResult.Success(user.toWrappedConfig())
}
```

### OrThrow function

Only functions named `*OrThrow` may throw `KmpFailureException`.

Allowed only when the caller immediately converts it back to `KmpResult<T>`.

Good:

```kotlin
private suspend fun mapStartResponseOrThrow(
    startResponse: StartResponse?,
): Wrapped_SDKStartConfig {
    val user = startResponse?.user ?: throw KmpFailure(
        message = "UserConfig.mapStartResponseOrThrow failed because user config is missing",
        code = "START_CONFIG_USER_MISSING",
        retryable = true,
        source = "UserConfig.mapStartResponseOrThrow",
    ).toException()

    return user.toWrappedConfig()
}
```

Good caller:

```kotlin
suspend fun getStartConfig(...): KmpResult<Wrapped_SDKStartConfig> {
    return kmpSuspendRunCatching(
        source = "UserConfig.getStartConfig",
    ) {
        mapStartResponseOrThrow(startResponse)
    }.mapFailure { failure ->
        failure.wrap(
            message = "UserConfig.getStartConfig failed to map start config",
            source = "UserConfig.getStartConfig",
        )
    }
}
```

Bad:

```kotlin
suspend fun getStartConfig(...): Wrapped_SDKStartConfig {
    return mapStartResponseOrThrow(startResponse)
}
```

---

## 3. KmpFailureException Policy

`KmpFailureException` is not normal failure delivery.

### Good

```kotlin
return KmpResult.Failure(failure)
```

### Bad

```kotlin
throw failure.toException()
```

### Allowed

```text
private/internal *OrThrow function
immediate caller uses kmpRunCatching/kmpSuspendRunCatching
caller returns KmpResult<T>
exception does not cross SDK/platform/Swift/Flutter/public boundary
```

### Forbidden

```kotlin
throw KmpFailure(...).toException()
```

inside:

```text
normal mapper
normal repository
normal service
normal SDK API
nullable-returning helper
Swift-facing KMP API
Flutter-facing KMP API
public KMP function
```

---

## 4. Use KmpResult for Fallible Operations

Good:

```kotlin
fun loadUser(): KmpResult<User>
fun saveBooking(booking: Booking): KmpResult<Unit>
fun fetchRemoteConfig(): KmpResult<AppConfig>
fun parseUserDto(json: String): KmpResult<UserDto>
suspend fun getShoppingCart(...): KmpResult<ShoppingCart>
```

Bad:

```kotlin
fun loadUser(): User?
fun loadUser(): User
fun loadUser(): Pair<User?, KmpFailure?>
fun loadUser(): Result<User>
suspend fun getShoppingCart(...): ShoppingCart
```

---

## 5. Operation Boundary Pattern

Use `kmpRunCatching` or `kmpSuspendRunCatching` around risky code.

Good places:

```text
HTTP calls
database calls
file operations
JSON parsing
SDK calls
platform API calls through expect/actual wrappers
repository operations
sequential orchestration blocks
```

### Good

```kotlin
fun fetchUserDto(): KmpResult<UserDto> {
    return kmpRunCatching(
        source = "UserApi.fetchUserDto",
    ) {
        httpClient.get("/users/me")
    }.mapFailure { failure ->
        failure.wrap(
            message = "UserApi.fetchUserDto failed to fetch /users/me",
            source = "UserApi.fetchUserDto",
        )
    }
}
```

### Bad

```kotlin
fun fetchUserDto(): KmpResult<UserDto> {
    return try {
        KmpResult.Success(httpClient.get("/users/me"))
    } catch (e: Throwable) {
        KmpResult.Failure(
            KmpFailure(message = "Failed")
        )
    }
}
```

---

## 6. Explicit Classification Pattern

Use `try/catch` when different throwable types need different failure metadata.

### Good

```kotlin
fun fetchUserDto(): KmpResult<UserDto> {
    return try {
        KmpResult.Success(api.fetchUser())
    } catch (e: TimeoutException) {
        KmpResult.Failure(
            e.toKmpFailure(
                message = "UserApi.fetchUserDto timed out while fetching current user",
                code = "TIMEOUT",
                retryable = true,
                source = "UserApi.fetchUserDto",
            )
        )
    } catch (e: Throwable) {
        KmpResult.Failure(
            e.toKmpFailure(
                message = "UserApi.fetchUserDto failed to fetch current user",
                code = "FETCH_USER_FAILED",
                retryable = false,
                source = "UserApi.fetchUserDto",
            )
        )
    }
}
```

### Bad

```kotlin
fun fetchUserDto(): KmpResult<UserDto> {
    return try {
        KmpResult.Success(api.fetchUser())
    } catch (e: Throwable) {
        KmpResult.Failure(
            KmpFailure(
                message = "Fetch failed",
                source = "UserApi.fetchUserDto",
            )
        )
    }
}
```

---

## 7. Preserve Throwable Cause Chain

Throwable chains must become `KmpFailure.cause` chains.

### Good

```kotlin
catch (e: Throwable) {
    return KmpResult.Failure(
        e.toKmpFailure(
            message = "UserApi.fetchUserDto failed to fetch current user",
            code = "FETCH_USER_FAILED",
            source = "UserApi.fetchUserDto",
        )
    )
}
```

### Bad

```kotlin
catch (e: Throwable) {
    return KmpResult.Failure(
        KmpFailure(
            message = e.message ?: "Unknown failure",
            source = "UserApi.fetchUserDto",
        )
    )
}
```

---

## 8. Preserve KmpFailure Chain

Existing `KmpFailure` must be wrapped, not replaced.

### Good

```kotlin
return repository.loadUser()
    .mapFailure { failure ->
        failure.wrap(
            message = "UserService.loadUser failed to load current user",
            source = "UserService.loadUser",
        )
    }
```

### Bad

```kotlin
return repository.loadUser()
    .mapFailure {
        KmpFailure(
            message = "UserService.loadUser failed",
            source = "UserService.loadUser",
        )
    }
```

---

## 9. Wrap at Meaningful Function Boundaries

Each meaningful fallible function should add its own context.

### Good

```kotlin
fun loadCurrentUser(): KmpResult<User> {
    return userApi.fetchUserDto()
        .mapCatching { dto -> dto.toDomain() }
        .mapFailure { failure ->
            failure.wrap(
                message = "UserRepository.loadCurrentUser failed to fetch DTO and map it to domain user",
                source = "UserRepository.loadCurrentUser",
            )
        }
}
```

### Bad

```kotlin
fun loadCurrentUser(): KmpResult<User> {
    return userApi.fetchUserDto()
        .mapCatching { dto -> dto.toDomain() }
}
```

---

## 10. Do Not Wrap Micro-Steps

### Good

```kotlin
fun loadCurrentUser(): KmpResult<User> {
    return userApi.fetchUserDto()
        .mapCatching { dto -> dto.toDomain() }
        .mapFailure { failure ->
            failure.wrap(
                message = "UserRepository.loadCurrentUser failed to fetch DTO and map current user",
                source = "UserRepository.loadCurrentUser",
            )
        }
}
```

### Bad

```kotlin
fun loadCurrentUser(): KmpResult<User> {
    return userApi.fetchUserDto()
        .mapFailure {
            it.wrap(
                message = "Failed before mapping",
                source = "UserRepository.loadCurrentUser.beforeMapping",
            )
        }
        .mapCatching {
            it.toDomain()
        }
        .mapFailure {
            it.wrap(
                message = "Failed after mapping",
                source = "UserRepository.loadCurrentUser.afterMapping",
            )
        }
}
```

---

## 11. Safe Success Transformation

Use `map` when transformation is safe and should not throw.

### Good

```kotlin
fun loadUserName(): KmpResult<String> {
    return loadUser()
        .map { user -> user.name }
}
```

### Bad

```kotlin
fun loadUserName(): KmpResult<String> {
    return loadUser()
        .mapCatching { user -> user.name }
}
```

---

## 12. Throwing Success Transformation

Use `mapCatching` when the transformation may throw.

### Good

```kotlin
fun fetchUserDto(): KmpResult<UserDto> {
    return kmpRunCatching(
        source = "UserApi.fetchUserDto",
    ) {
        httpClient.get("/users/me")
    }.mapCatching { response ->
        json.decodeFromString<UserDto>(response.body)
    }.mapFailure { failure ->
        failure.wrap(
            message = "UserApi.fetchUserDto failed to fetch and parse /users/me",
            source = "UserApi.fetchUserDto",
        )
    }
}
```

### Bad

```kotlin
fun fetchUserDto(): KmpResult<UserDto> {
    val response = httpClient.get("/users/me")
    val dto = json.decodeFromString<UserDto>(response.body)
    return KmpResult.Success(dto)
}
```

---

## 13. Chaining KmpResult Functions

Use `flatMap` when the success transformation returns `KmpResult<R>`.

### Good

```kotlin
fun loadUserProfile(): KmpResult<UserProfile> {
    return loadUser()
        .flatMap { user ->
            loadProfile(user.id)
        }
        .mapFailure { failure ->
            failure.wrap(
                message = "UserService.loadUserProfile failed to load user profile",
                source = "UserService.loadUserProfile",
            )
        }
}
```

### Bad

```kotlin
fun loadUserProfile(): KmpResult<UserProfile> {
    return loadUser().fold(
        onSuccess = { user ->
            loadProfile(user.id)
        },
        onFailure = { failure ->
            KmpResult.Failure(failure)
        }
    )
}
```

---

## 14. Sequential Orchestration Without Throwing

Prefer `flatMap`, `map`, and `mapCatching`.

### Good

```kotlin
fun buildDashboard(): KmpResult<Dashboard> {
    return userRepository.loadUser()
        .flatMap { user ->
            bookingRepository.loadBookings(user.id)
                .map { bookings ->
                    Dashboard(user, bookings)
                }
        }
        .mapFailure { failure ->
            failure.wrap(
                message = "DashboardController.buildDashboard failed to build dashboard state",
                source = "DashboardController.buildDashboard",
            )
        }
}
```

### Bad

```kotlin
fun buildDashboard(): KmpResult<Dashboard> {
    return kmpRunCatching(
        source = "DashboardController.buildDashboard",
    ) {
        val user = userRepository.loadUser().getOrThrow()
        val bookings = bookingRepository.loadBookings(user.id).getOrThrow()
        Dashboard(user, bookings)
    }
}
```

---

## 15. Protected OrThrow Orchestration

Use this only for dense sequential logic.

### Good

```kotlin
private fun buildDashboardOrThrow(): Dashboard {
    val user = userRepository.loadUser().getOrThrow()
    val bookings = bookingRepository.loadBookings(user.id).getOrThrow()
    val payments = paymentRepository.loadPayments(user.id).getOrThrow()

    return Dashboard(user, bookings, payments)
}

fun buildDashboard(): KmpResult<Dashboard> {
    return kmpRunCatching(
        source = "DashboardController.buildDashboard",
    ) {
        buildDashboardOrThrow()
    }.mapFailure { failure ->
        failure.wrap(
            message = "DashboardController.buildDashboard failed to build dashboard state",
            source = "DashboardController.buildDashboard",
        )
    }
}
```

### Bad

```kotlin
fun buildDashboard(): Dashboard {
    val user = userRepository.loadUser().getOrThrow()
    val bookings = bookingRepository.loadBookings(user.id).getOrThrow()

    return Dashboard(user, bookings)
}
```

### Bad

```kotlin
private fun buildDashboard(): Dashboard {
    val user = userRepository.loadUser().getOrThrow()
    val bookings = bookingRepository.loadBookings(user.id).getOrThrow()

    return Dashboard(user, bookings)
}
```

---

## 16. Suspend Routines and Cancellation

Use `kmpSuspendRunCatching`.

It must rethrow `CancellationException`.

### Good

```kotlin
suspend fun loadCurrentUser(): KmpResult<User> {
    return kmpSuspendRunCatching(
        source = "UserRepository.loadCurrentUser",
    ) {
        userApi.fetchUserDto()
    }.mapCatching { dto ->
        dto.toDomain()
    }.mapFailure { failure ->
        failure.wrap(
            message = "UserRepository.loadCurrentUser failed to load current user",
            source = "UserRepository.loadCurrentUser",
        )
    }
}
```

### Bad

```kotlin
suspend fun loadCurrentUser(): KmpResult<User> {
    return try {
        KmpResult.Success(api.loadUser())
    } catch (e: Throwable) {
        KmpResult.Failure(e.toKmpFailure())
    }
}
```

---

## 17. Expected Validation Failure

Missing or invalid expected data should return `KmpResult.Failure`.

### Good

```kotlin
private suspend fun mapStartResponse(
    startResponse: StartResponse?,
): KmpResult<Wrapped_SDKStartConfig> {
    val response = startResponse ?: return KmpResult.Failure(
        KmpFailure(
            message = "UserConfig.mapStartResponse failed because start response is missing",
            code = "START_RESPONSE_MISSING",
            retryable = true,
            source = "UserConfig.mapStartResponse",
        )
    )

    val user = response.user ?: return KmpResult.Failure(
        KmpFailure(
            message = "UserConfig.mapStartResponse failed because user config is missing",
            code = "START_CONFIG_USER_MISSING",
            retryable = true,
            source = "UserConfig.mapStartResponse",
        )
    )

    val selectedFilters = user.filters ?: return KmpResult.Failure(
        KmpFailure(
            message = "UserConfig.mapStartResponse failed because user filters are missing",
            code = "START_CONFIG_FILTERS_MISSING",
            retryable = true,
            source = "UserConfig.mapStartResponse",
        )
    )

    val location = user.location ?: return KmpResult.Failure(
        KmpFailure(
            message = "UserConfig.mapStartResponse failed because user location is missing",
            code = "START_CONFIG_LOCATION_MISSING",
            retryable = true,
            source = "UserConfig.mapStartResponse",
        )
    )

    return KmpResult.Success(
        Wrapped_SDKStartConfig(
            sdkConnfig = response.global.toWrappedSDKConfig(),
            location = location,
            responseFilters = emptyList(),
            filters = Wrapped_SelectedFilterItems(filters = selectedFilters),
        )
    )
}
```

### Bad

```kotlin
private suspend fun mapStartResponse(
    startResponse: StartResponse?,
): Wrapped_SDKStartConfig? {
    return startResponse?.run {
        user ?: throw KmpFailure(
            message = "User config is missing",
            code = "START_CONFIG_USER_MISSING",
            retryable = true,
            source = "UserConfig.mapStartResponse",
        ).toException()

        Wrapped_SDKStartConfig(
            sdkConnfig = global.toWrappedSDKConfig(),
            location = user.location,
            responseFilters = emptyList(),
            filters = Wrapped_SelectedFilterItems(filters = user.filters),
        )
    }
}
```

---

## 18. Fallback

Use `recover` or `recoverCatching` only when fallback is a valid success.

### Good

```kotlin
fun loadConfig(): KmpResult<AppConfig> {
    return remoteConfig.load()
        .recoverCatching {
            localConfig.loadFromDisk().getOrThrow()
        }
        .mapFailure { failure ->
            failure.wrap(
                message = "ConfigRepository.loadConfig failed to load remote config and fallback local config",
                source = "ConfigRepository.loadConfig",
            )
        }
}
```

### Bad

```kotlin
fun loadUser(): KmpResult<User> {
    return userRepository.loadCurrentUser()
        .recover { User.empty() }
}
```

---

## 19. Optional Value

Do not use failure for normal absence.

### Good

```kotlin
fun findCachedUser(): KmpResult<User?> {
    return kmpRunCatching(
        source = "UserCache.findCachedUser",
    ) {
        cache.findUser()
    }.mapFailure { failure ->
        failure.wrap(
            message = "UserCache.findCachedUser failed to read cached user",
            source = "UserCache.findCachedUser",
        )
    }
}
```

Meaning:

```text
KmpResult.Failure = cache read failed.
Success(null) = cache works, but user is absent.
```

### Bad

```kotlin
fun findCachedUser(): User? {
    return try {
        cache.findUser()
    } catch (e: Throwable) {
        null
    }
}
```

---

## 20. Logging Ownership

Functions that return `KmpResult<T>` should not log.

### Good

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

### Bad

```kotlin
fun loadUser(): KmpResult<User> {
    val result = repository.loadUser()

    result.failureOrNull()?.let {
        logger.error(it.debugString())
    }

    return result
}
```

---

## 21. Consuming Boundary

Use `fold` at decision points.

### Good

```kotlin
controller.loadDashboard().fold(
    onSuccess = { state ->
        render(state)
    },
    onFailure = { failure ->
        logger.error(failure.debugString())

        render(
            DashboardState.Error(
                message = failure.message,
                retryable = failure.retryable,
            )
        )
    }
)
```

### Bad

```kotlin
val dashboard = controller.loadDashboard().getOrThrow()
render(dashboard)
```

---

## 22. Fire-and-Forget Coroutine

Fire-and-forget routines must not silently lose failures.

### Good

```kotlin
scope.launch {
    syncRepository.sync().fold(
        onSuccess = {
            logger.info("SyncWorker.startSync completed")
        },
        onFailure = { failure ->
            logger.error(failure.debugString())
        }
    )
}
```

### Bad

```kotlin
scope.launch {
    syncRepository.sync()
}
```

### Good for unsafe throwing code

```kotlin
scope.launch {
    try {
        syncRepository.syncUnsafe()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        logger.error(
            e.toKmpFailure(
                message = "SyncWorker.startSync failed during background sync",
                source = "SyncWorker.startSync",
            ).debugString()
        )
    }
}
```

---

## 23. KmpResultSwift Is Only an Interop Projection

Shared Kotlin code should continue to use `KmpResult<T>` as the real contract.

Swift-facing projection:

```kotlin
fun <T> KmpResult<T>.asKmpResultSwift(): KmpResultSwift<T>
```

### Good

```kotlin
fun loadUser(): KmpResult<User>
```

### Bad

```kotlin
fun loadUser(): KmpResultSwift<User>
```

### Good implementation

```kotlin
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

### Bad implementation

```kotlin
is KmpResult.Failure -> KmpResultSwift(
    status = KmpResultSwiftStatus.FAILURE,
    value = null,
    failure = KmpFailure(message = failure.message),
)
```

---

## 24. Routine That Must Throw

This is not a default KMP pattern.

Use only when an external integration API requires throwing and cannot accept `KmpResult<T>`.

### Good

```kotlin
fun loadCurrentUserOrThrow(): User {
    return try {
        userRepository.loadCurrentUser().getOrThrow()
    } catch (e: KmpFailureException) {
        throw e.failure.wrap(
            message = "UserService.loadCurrentUserOrThrow failed to load current user",
            source = "UserService.loadCurrentUserOrThrow",
        ).toException()
    }
}
```

### Good suspend version

```kotlin
suspend fun loadCurrentUserOrThrow(): User {
    return try {
        userRepository.loadCurrentUser().getOrThrow()
    } catch (e: CancellationException) {
        throw e
    } catch (e: KmpFailureException) {
        throw e.failure.wrap(
            message = "UserService.loadCurrentUserOrThrow failed to load current user",
            source = "UserService.loadCurrentUserOrThrow",
        ).toException()
    } catch (e: Throwable) {
        throw e.toKmpFailure(
            message = "UserService.loadCurrentUserOrThrow failed with unexpected throwable",
            source = "UserService.loadCurrentUserOrThrow",
        ).toException()
    }
}
```

### Bad

```kotlin
fun loadCurrentUserOrThrow(): User {
    return userRepository.loadCurrentUser().getOrThrow()
}
```

### Bad

```kotlin
fun loadCurrentUser(): User {
    return userRepository.loadCurrentUser().getOrThrow()
}
```

### Bad

```kotlin
fun loadCurrentUser(): KmpResult<User> {
    return try {
        KmpResult.Success(userRepository.loadCurrentUser().getOrThrow())
    } catch (e: KmpFailureException) {
        throw e
    }
}
```

---

## 25. failureOrNull and getOrNull Policy

`failureOrNull()` is valid only at consuming boundaries.

### Good

```kotlin
fun runSyncJob() {
    val result = syncRepository.sync()

    result.failureOrNull()?.let { failure ->
        logger.error(failure.debugString())
    }
}
```

### Bad

```kotlin
fun loadUser(): KmpResult<User> {
    val result = repository.loadUser()

    result.failureOrNull()?.let { failure ->
        logger.error(failure.debugString())
    }

    return result
}
```

`getOrNull()` is not a recommended KMP failure pattern.

### Bad

```kotlin
val user: User? = userRepository.loadUser().getOrNull()
```

### Good

```kotlin
userRepository.loadUser().fold(
    onSuccess = { user ->
        render(user)
    },
    onFailure = { failure ->
        logger.error(failure.debugString())
        renderError(failure.message)
    }
)
```

---

## 26. AI / Developer Rules

1. Use `KmpResult<T>` for fallible shared KMP operations.
2. Use `KmpFailure` as the only project-level failure object.
3. Return `KmpResult.Failure(failure)` for expected project failures.
4. Do not generate `throw KmpFailure(...).toException()` in normal KMP code.
5. Use `toException()` only inside `private/internal *OrThrow` functions or rare external adapters that must throw.
6. A `*OrThrow` function must be immediately protected by `kmpRunCatching` or `kmpSuspendRunCatching` when used by normal project code.
7. Functions returning `T?` must not throw `KmpFailureException`.
8. Public SDK, Swift-facing, Flutter-facing, repository, service, and mapper functions must not leak `KmpFailureException`.
9. Use `kmpRunCatching` at risky synchronous boundaries.
10. Use `kmpSuspendRunCatching` at risky suspend boundaries.
11. Preserve cancellation.
12. Convert Throwable chains into `KmpFailure.cause` chains.
13. Wrap existing `KmpFailure`, do not replace it.
14. Wrap once per meaningful function boundary.
15. Do not wrap tiny micro-steps.
16. Use `map` for safe transformations.
17. Use `mapCatching` for transformations that may throw.
18. Use `flatMap` when the transformation returns another `KmpResult`.
19. Prefer non-throwing `flatMap/map/mapCatching` orchestration.
20. Use `getOrThrow()` only in protected `*OrThrow` orchestration or rare fallback blocks.
21. Use `recover` only for real fallback values.
22. Use `fold` at consuming boundaries.
23. Do not log inside functions that return `KmpResult<T>`.
24. `KmpResultSwift<T>` is only a Swift interop projection, not an internal KMP result type.
25. `getOrNull()` is not part of the default project pattern.

---

## 27. Code Review Search Rules

Search:

```text
throw .*toException()
throw KmpFailure
getOrThrow()
getOrNull()
```

Allowed:

```text
throw .*toException() -> only inside *OrThrow or rare external throwing adapter
getOrThrow() -> only inside protected *OrThrow orchestration or rare fallback block
getOrNull() -> should usually be removed
```

Forbidden:

```text
throw .*toException() inside normal mapper/service/repository/API function
throw .*toException() inside function returning T?
throw .*toException() inside public KMP API
throw .*toException() crossing Swift/Flutter boundary
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

---

## Existing Code Refactor Rule

When editing existing code, do not preserve an old `T` or `T?` signature by throwing `KmpFailureException`.

If a private/internal function becomes fallible, change it to return `KmpResult<T>` and update its callers.

Good:

```kotlin
private suspend fun mapStartResponse(
    startResponse: StartResponse?,
): KmpResult<Wrapped_SDKStartConfig>
```

Bad:

```kotlin
private suspend fun mapStartResponse(
    startResponse: StartResponse?,
): Wrapped_SDKStartConfig? {
    throw KmpFailure(...).toException()
}
```

If a public/override/interface signature cannot be changed, create a private/internal `KmpResult<T>` helper and adapt at the boundary.

Rule:

```text
Private/internal fallible helper -> change to KmpResult<T>.
Public/interface/override boundary -> preserve only if required, but delegate to KmpResult<T> helper.
Never use throw failure.toException() to keep an old T/T? signature.
```
