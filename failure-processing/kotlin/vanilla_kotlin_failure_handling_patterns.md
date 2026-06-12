# Declarative Failure Handling Patterns in Vanilla Kotlin

These instructions define a disciplined, Kotlin-native approach to failure handling.

The goal is to use only vanilla Kotlin tools while applying declarative failure-handling principles:

- make failures explicit;
- preserve the full cause chain;
- wrap failures at meaningful function boundaries;
- explain exactly what failed in each function;
- avoid nullable values as hidden failures;
- avoid silently losing coroutine and background failures.

No custom `Either`.
No custom `AppResult`.
No custom failure hierarchy is required.

Use vanilla Kotlin tools:

```kotlin
Throwable
Exception
RuntimeException
Result<T>
runCatching
map
mapCatching
recover
recoverCatching
fold
getOrThrow
exceptionOrNull
stackTraceToString
```

---

## 1. Core Idea

If failure is part of the operation contract, return `Result<T>`.

If failure is unrecoverable or truly unexpected, throw.

Prefer this:

```kotlin
fun loadUser(): Result<User> {
    return runCatching {
        api.loadUser()
    }
}
```

Avoid this:

```kotlin
fun loadUser(): User? {
    return try {
        api.loadUser()
    } catch (e: Exception) {
        null
    }
}
```

Nullable means:

```text
Value may be absent.
```

`Result<T>` means:

```text
Operation may fail.
```

Do not mix these meanings.

---

## 2. Use `Result<T>` for Fallible Operations

Use `Result<T>` when the caller should decide how to handle the failure.

Good examples:

```kotlin
fun loadUser(): Result<User>
fun saveBooking(booking: Booking): Result<Unit>
fun fetchRemoteConfig(): Result<AppConfig>
fun parseUserDto(json: String): Result<UserDto>
```

Avoid:

```kotlin
fun loadUser(): User?
fun loadUser(): User
fun loadUser(): Pair<User?, Throwable?>
```

The function signature should communicate:

```text
This operation may fail, and the caller must decide what to do.
```

---

## 3. Logging Ownership Rule

Functions that return `Result<T>` should not log failures.

They should only wrap the lower-level `Throwable` with clear function-level context
and return the new failed `Result`.

Logging belongs to the function that consumes the `Result` and converts it into
a final side effect: UI state, API response, background job result, crash report,
user-visible behavior, or legacy code that returns `void` / `Unit` or a nullable result.

Rule:

```text
If the function returns Result<T>, wrap but do not log.
If the function consumes Result<T> and does not return it further, log/report/convert.
```

---

## 4. Use `runCatching` at Operation Boundaries

Use `runCatching` where external or risky code may throw.

```kotlin
fun fetchUserDto(): Result<UserDto> {
    return runCatching {
        httpClient.get("/users/me")
    }
}
```

Good places for `runCatching`:

```text
HTTP calls
database calls
file operations
JSON parsing
SDK calls
platform APIs
repository operations
sequential orchestration blocks
```

Example:

```kotlin
fun readSettings(): Result<Settings> {
    return runCatching {
        val text = file.readText()
        json.decodeFromString<Settings>(text)
    }
}
```

This is better than returning `null`, because the error is preserved.

---

## 5. Use `try/catch` When Mapping Needs Branches

`runCatching` is compact.

`try/catch` is better when different exception types need different treatment.

```kotlin
fun fetchUser(): Result<UserDto> {
    return try {
        Result.success(api.fetchUser())
    } catch (e: java.net.SocketTimeoutException) {
        Result.failure(
            RuntimeException("User request timed out", e)
        )
    } catch (e: java.io.IOException) {
        Result.failure(
            RuntimeException("Network error while fetching user", e)
        )
    } catch (e: Throwable) {
        Result.failure(
            RuntimeException("Unexpected error while fetching user", e)
        )
    }
}
```

Rule:

```text
runCatching = compact boundary wrapper
try/catch = explicit exception classification
```

---

## 6. Always Preserve `cause`

This is the most important rule.

Bad:

```kotlin
return Result.failure(
    RuntimeException("Repository failed")
)
```

Good:

```kotlin
return Result.failure(
    RuntimeException("Repository failed", throwable)
)
```

The original `Throwable` should almost never be destroyed.

Vanilla Kotlin/JVM already knows how to print cause chains:

```kotlin
val failure = RuntimeException(
    "Repository failed",
    RuntimeException(
        "API failed",
        java.io.IOException("Connection reset")
    )
)

println(failure.stackTraceToString())
```

The output includes a full cause chain:

```text
RuntimeException: Repository failed
Caused by: RuntimeException: API failed
Caused by: IOException: Connection reset
```

---

## 7. Always Wrap Failure at the Function Boundary

Each meaningful fallible function should add its own context before returning failure.

Do **not** simply return a failure received from another function.

Bad:

```kotlin
fun loadCurrentUser(): Result<User> {
    return userApi.fetchUserDto()
        .mapCatching { dto -> dto.toDomain() }
}
```

This loses important information.

If this fails, the caller only sees something like:

```text
UserApi failed to fetch /users/me
```

But the caller does not know that the failure happened while `UserRepository` was trying to load the current user.

Good:

```kotlin
fun loadCurrentUser(): Result<User> {
    return userApi.fetchUserDto()
        .mapCatching { dto ->
            dto.toDomain()
        }
        .mapFailure { throwable ->
            RuntimeException(
                "UserRepository.loadCurrentUser failed to load and map current user",
                throwable
            )
        }
}
```

Now the chain explains the path:

```text
RuntimeException: UserRepository.loadCurrentUser failed to load and map current user
Caused by: RuntimeException: UserApi.fetchUserDto failed to fetch /users/me
Caused by: IOException: Connection reset
```

Principle:

```text
Every function that returns Result<T> is responsible for explaining
what it was trying to do when the lower-level failure happened.
```

The wrapper message should answer:

```text
Which function failed?
What exact operation failed inside this function?
What important parameters/context were involved?
```

Good message pattern:

```text
<ClassName>.<functionName> failed to <specific operation>
```

Examples:

```kotlin
RuntimeException(
    "UserRepository.loadCurrentUser failed to load current user",
    cause
)

RuntimeException(
    "BookingRepository.createBooking failed to create booking for scheduleId=$scheduleId",
    cause
)

RuntimeException(
    "DashboardController.loadDashboard failed to build dashboard state",
    cause
)

RuntimeException(
    "UserApi.fetchUserDto failed to fetch and parse /users/me",
    cause
)
```

Avoid vague messages:

```kotlin
RuntimeException("Failed", cause)
RuntimeException("Something went wrong", cause)
RuntimeException("Repository error", cause)
```

---

## 7.1. Wrapping Rule

The rule is not:

```text
Wrap only sometimes.
```

The rule is:

```text
Wrap at every meaningful fallible function boundary.
```

A function boundary is meaningful when the function has its own responsibility.

Examples:

```kotlin
fun fetchUserDto(): Result<UserDto>
fun loadCurrentUser(): Result<User>
fun loadDashboard(): Result<DashboardState>
```

Each one should wrap because each one adds a different level of meaning:

```text
API function: failed to fetch/parse remote data
Repository function: failed to load domain object
Controller/ViewModel function: failed to build screen state
```

Resulting chain:

```text
DashboardController.loadDashboard failed to build dashboard state
caused by: UserRepository.loadCurrentUser failed to load current user
caused by: UserApi.fetchUserDto failed to fetch and parse /users/me
caused by: IOException: Connection reset
```

---

## 7.2. Do Not Wrap Meaningless Micro-Steps

Do not wrap every tiny internal line.

Bad:

```kotlin
fun loadCurrentUser(): Result<User> {
    return userApi.fetchUserDto()
        .mapFailure {
            RuntimeException("Failed before mapping", it)
        }
        .mapCatching {
            it.toDomain()
        }
        .mapFailure {
            RuntimeException("Failed after mapping", it)
        }
}
```

Good:

```kotlin
fun loadCurrentUser(): Result<User> {
    return userApi.fetchUserDto()
        .mapCatching { dto ->
            dto.toDomain()
        }
        .mapFailure { throwable ->
            RuntimeException(
                "UserRepository.loadCurrentUser failed to fetch DTO and map it to domain user",
                throwable
            )
        }
}
```

Better rule:

```text
One strong wrapper per public/meaningful function.
Not one wrapper per line.
```

---

## 8. Add `mapFailure` as a Small Vanilla Extension

Kotlin has `map`, `mapCatching`, `recover`, and `recoverCatching`, but it does not have a standard `mapFailure`.

For declarative failure handling, this tiny extension is very useful:

```kotlin
inline fun <T> Result<T>.mapFailure(
    transform: (Throwable) -> Throwable,
): Result<T> {
    return fold(
        onSuccess = { value ->
            Result.success(value)
        },
        onFailure = { throwable ->
            Result.failure(transform(throwable))
        }
    )
}
```

This does not create a new failure framework.
It only fills one ergonomic gap in vanilla `Result<T>`.

Usage:

```kotlin
fun loadUser(): Result<User> {
    return api.fetchUserDto()
        .mapCatching { dto ->
            dto.toDomain()
        }
        .mapFailure { throwable ->
            RuntimeException(
                "UserRepository.loadUser failed to load user",
                throwable
            )
        }
}
```

---

## 9. Use `map` for Safe Success Transformation

Use `map` when transforming a successful value and the transform should be simple.

```kotlin
fun loadUserName(): Result<String> {
    return loadUser()
        .map { user ->
            user.name
        }
}
```

Meaning:

```text
If loadUser() succeeds, transform User -> String.
If loadUser() fails, keep the same failure.
```

---

## 10. Use `mapCatching` When Transformation May Throw

Use `mapCatching` when the success transformation can throw and should be converted into `Result.failure`.

```kotlin
fun parseUser(response: HttpResponse): Result<UserDto> {
    return Result.success(response)
        .mapCatching { value ->
            json.decodeFromString<UserDto>(value.body)
        }
}
```

Common use cases:

```text
JSON parsing
domain mapping
date parsing
number parsing
file parsing
DTO validation that throws
```

Example:

```kotlin
fun fetchUserDto(): Result<UserDto> {
    return runCatching {
        httpClient.get("/users/me")
    }.mapCatching { response ->
        json.decodeFromString<UserDto>(response.body)
    }.mapFailure { throwable ->
        RuntimeException(
            "UserApi.fetchUserDto failed to fetch and parse current user",
            throwable
        )
    }
}
```

---

## 11. Use `recover` Only for Valid Fallback Success

`recover` converts a failure into a success value.

Good:

```kotlin
fun loadConfig(): Result<AppConfig> {
    return remoteConfig.load()
        .recover { throwable ->
            AppConfig.default()
        }
}
```

This is correct if default config is a valid fallback.

Bad:

```kotlin
fun loadUser(): Result<User> {
    return repository.loadUser()
        .recover {
            User.empty()
        }
}
```

This is bad if `User.empty()` hides a real data-loading problem.

Rule:

```text
recover means: failure is acceptable and can become a valid success.
```

---

## 12. Use `recoverCatching` When Fallback May Throw

Use `recoverCatching` when fallback logic itself may fail.

```kotlin
fun loadConfig(): Result<AppConfig> {
    return remoteConfig.load()
        .recoverCatching {
            localConfig.loadFromDisk()
        }
}
```

Meaning:

```text
Try remote config.
If remote fails, try local config.
If local also throws, return failure.
```

---

## 13. Use `getOrThrow()` for Sequential Orchestration

`getOrThrow()` returns the value if the result is successful, or throws the encapsulated `Throwable` if it failed.

This is useful inside `runCatching`.

Bad nested style:

```kotlin
fun buildDashboard(): Result<Dashboard> {
    return userRepository.loadUser().fold(
        onSuccess = { user ->
            bookingRepository.loadBookings(user.id).fold(
                onSuccess = { bookings ->
                    Result.success(Dashboard(user, bookings))
                },
                onFailure = { error ->
                    Result.failure(error)
                }
            )
        },
        onFailure = { error ->
            Result.failure(error)
        }
    )
}
```

Good sequential style:

```kotlin
fun buildDashboard(): Result<Dashboard> {
    return runCatching {
        val user = userRepository.loadUser().getOrThrow()
        val bookings = bookingRepository.loadBookings(user.id).getOrThrow()

        Dashboard(user, bookings)
    }.mapFailure { throwable ->
        RuntimeException(
            "DashboardViewModel.buildDashboard failed to build dashboard state",
            throwable
        )
    }
}
```

Pattern:

```text
Use getOrThrow() inside runCatching to short-circuit on the first failure.
Then wrap once at the current function boundary.
```

---

## 14. Use `fold` at Decision Points

Use `fold` when you need to finally branch into success/failure behavior.

Example in UI:

```kotlin
repository.loadUser().fold(
    onSuccess = { user ->
        showUser(user)
    },
    onFailure = { throwable ->
        showError(throwable.message ?: "Something went wrong")
    }
)
```

Example in service:

```kotlin
val response = createBooking(request).fold(
    onSuccess = { booking ->
        BookingResponse.Success(booking.id)
    },
    onFailure = { throwable ->
        BookingResponse.Failure(
            message = throwable.message ?: "Booking failed"
        )
    }
)
```

Rule:

```text
Use fold at the edge where you must make a decision.
Do not overuse fold for internal chaining.
```

---

## 15. Use `exceptionOrNull()` Only at Consuming Boundaries

Use `exceptionOrNull()` when a function consumes a `Result<T>` and does not return it further.

Good:

```kotlin
fun runSyncJob() {
    val result = syncRepository.sync()

    result.exceptionOrNull()?.let { throwable ->
        logger.error("SyncJob.runSyncJob failed", throwable)
    }
}
```

Bad:

This is bad because the same failure may be logged again by the caller.

```kotlin
fun loadUser(): Result<User> {
val result = repository.loadUser()

    result.exceptionOrNull()?.let { throwable ->
        logger.error("Failed to load user", throwable)
    }

    return result
}
```

Do not inspect and log a failure if the same Result<T> is still being returned upward.

---

## 16. Use `onSuccess` and `onFailure` Only at Consuming Boundaries

Use `onSuccess` and `onFailure` for side effects only when the current function consumes the `Result<T>` and does not return it further.

Do not use them for main transformation logic.

Good:

```kotlin
fun runSyncJob() {
    syncRepository.sync()
        .onSuccess {
            logger.info("SyncJob.runSyncJob completed")
        }
        .onFailure { throwable ->
            logger.error("SyncJob.runSyncJob failed", throwable)
        }
}
```

Bad:

```kotlin
fun loadUser(): Result<User> {
    return repository.loadUser()
        .onFailure { throwable ->
            logger.error("Failed to load user", throwable)
        }
}
```

This is bad because loadUser() still returns Result<User>.
It should wrap the failure, not log it.

Prefer `map`, `fold`, or `getOrThrow`.

---

# Direct Failure Handling Patterns by Routine Type

## 17. Simple Synchronous Routine

Use `runCatching`.

```kotlin
fun parseUser(jsonText: String): Result<UserDto> {
    return runCatching {
        json.decodeFromString<UserDto>(jsonText)
    }.mapFailure { throwable ->
        RuntimeException(
            "UserParser.parseUser failed to parse UserDto from JSON",
            throwable
        )
    }
}
```

Use this for:

```text
parsing
formatting
pure transformations that may throw
local calculations that may fail
```

---

## 18. Synchronous Routine Calling Another `Result<T>` Routine

Use `getOrThrow()` inside `runCatching`.

```kotlin
fun loadCurrentUser(): Result<User> {
    return runCatching {
        val dto = userApi.fetchUserDto().getOrThrow()
        dto.toDomain()
    }.mapFailure { throwable ->
        RuntimeException(
            "UserRepository.loadCurrentUser failed to load current user",
            throwable
        )
    }
}
```

This is better than returning the lower-level result directly because this function adds its own context.

---

## 19. Sequential Orchestration Routine

Use `getOrThrow()` for each step.

```kotlin
fun loadDashboard(): Result<DashboardState> {
    return runCatching {
        val user = userRepository.loadCurrentUser().getOrThrow()
        val bookings = bookingRepository.loadBookings(user.id).getOrThrow()
        val payments = paymentRepository.loadPayments(user.id).getOrThrow()

        DashboardState.Ready(
            user = user,
            bookings = bookings,
            payments = payments
        )
    }.mapFailure { throwable ->
        RuntimeException(
            "DashboardController.loadDashboard failed to build dashboard state",
            throwable
        )
    }
}
```

This gives a clean chain and avoids nested `fold`.

---

## 20. Suspend Routine

Do not use plain `runCatching` blindly if coroutine cancellation matters.

Use a helper that rethrows `CancellationException`.

```kotlin
suspend inline fun <T> suspendRunCatching(
    crossinline block: suspend () -> T,
): Result<T> {
    return try {
        Result.success(block())
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
}
```

Then:

```kotlin
suspend fun loadCurrentUser(): Result<User> {
    return suspendRunCatching {
        val dto = userApi.fetchUserDto().getOrThrow()
        dto.toDomain()
    }.mapFailure { throwable ->
        RuntimeException(
            "UserRepository.loadCurrentUser failed to load current user",
            throwable
        )
    }
}
```

Rule:

```text
Cancellation is not an app failure.
Cancellation should normally continue propagating.
```

---

## 21. Fire-and-Forget Coroutine Routine

If the routine is launched and does not return `Result<T>`, catch and log inside the coroutine.

Bad:

```kotlin
scope.launch {
    syncRepository.sync()
}
```

If `sync()` throws, the failure may be lost, crash the scope, or be handled too far away.

Good:

```kotlin
scope.launch {
    syncRepository.sync()
        .onFailure { throwable ->
            logger.error(
                "SyncWorker.startSync failed during background sync",
                throwable
            )
        }
}
```

If the inside code throws directly:

```kotlin
scope.launch {
    try {
        syncRepository.syncUnsafe()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        logger.error(
            "SyncWorker.startSync failed during background sync",
            RuntimeException(
                "SyncWorker.startSync failed during background sync",
                e
            )
        )
    }
}
```

Rule:

```text
Fire-and-forget routines must not silently lose failures.
They must log, report, or convert failure into observable state.
```

---

## 22. Routine That Updates UI State

Convert `Result<T>` into explicit UI state at the edge.

```kotlin
fun loadUserScreen() {
    viewModelScope.launch {
        state.value = UserState.Loading

        val result = userRepository.loadCurrentUser()

        state.value = result.fold(
            onSuccess = { user ->
                UserState.Ready(user)
            },
            onFailure = { throwable ->
                logger.error("UserViewModel.loadUserScreen failed", throwable)

                UserState.Error(
                    message = throwable.message ?: "Something went wrong"
                )
            }
        )
    }
}
```

Do not let UI parse random technical details too deeply.

The ViewModel/controller is the correct place to turn failure into UI state.

---

## 23. Routine with Fallback

Use `recover` or `recoverCatching` only when fallback is a valid success.

```kotlin
fun loadConfig(): Result<AppConfig> {
    return remoteConfigRepository.loadRemoteConfig()
        .recoverCatching {
            localConfigRepository.loadLocalConfig().getOrThrow()
        }
        .mapFailure { throwable ->
            RuntimeException(
                "ConfigRepository.loadConfig failed to load remote config and fallback local config",
                throwable
            )
        }
}
```

This means:

```text
Try remote config.
If remote fails, try local config.
If local also fails, return failure with full context.
```

Do not use fallback to hide serious failure.

Bad:

```kotlin
fun loadUser(): Result<User> {
    return userRepository.loadCurrentUser()
        .recover { User.empty() }
}
```

unless `User.empty()` is a real valid business state.

---

## 24. Routine with Optional Value

Do not use failure for normal absence.

Good:

```kotlin
fun findCachedUser(): Result<User?> {
    return runCatching {
        cache.findUser()
    }.mapFailure { throwable ->
        RuntimeException(
            "UserCache.findCachedUser failed to read cached user",
            throwable
        )
    }
}
```

Meaning:

```text
Result failure = cache read failed
Success null = cache works, but user is absent
```

Do not write:

```kotlin
fun findCachedUser(): Result<User>
```

and return failure just because user is not cached.

---

## 25. Routine with Validation

Validation can be handled as `Result<T>` with `IllegalArgumentException` or another standard exception type.

```kotlin
fun validateEmail(email: String): Result<String> {
    return if ("@" in email) {
        Result.success(email)
    } else {
        Result.failure(
            IllegalArgumentException(
                "validateEmail failed: email does not contain @"
            )
        )
    }
}
```

When used inside a bigger routine:

```kotlin
fun createUser(email: String): Result<User> {
    return runCatching {
        val validEmail = validateEmail(email).getOrThrow()
        User(email = validEmail)
    }.mapFailure { throwable ->
        RuntimeException(
            "UserFactory.createUser failed to create user from email",
            throwable
        )
    }
}
```

Chain:

```text
RuntimeException: UserFactory.createUser failed to create user from email
caused by: IllegalArgumentException: validateEmail failed: email does not contain @
```

---

## 26. Routine That Must Throw Instead of Return `Result<T>`

Some APIs require throwing.

Then throw with cause.

```kotlin
fun loadCurrentUserOrThrow(): User {
    return try {
        userRepository.loadCurrentUser().getOrThrow()
    } catch (e: Throwable) {
        throw RuntimeException(
            "UserService.loadCurrentUserOrThrow failed to load current user",
            e
        )
    }
}
```

If this is a coroutine function, preserve cancellation:

```kotlin
suspend fun loadCurrentUserOrThrow(): User {
    return try {
        userRepository.loadCurrentUser().getOrThrow()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        throw RuntimeException(
            "UserService.loadCurrentUserOrThrow failed to load current user",
            e
        )
    }
}
```

---

# Logging Patterns

## 27. Log the Full Throwable

For normal logging:

```kotlin
logger.error("Failed to load user", throwable)
```

For full string output:

```kotlin
val text = throwable.stackTraceToString()
```

---

## 28. Compact Cause-Chain Logging

For compact cause-chain logs:

```kotlin
fun Throwable.causeChainString(): String {
    return generateSequence(this) { it.cause }
        .joinToString(separator = "\ncaused by: ") { throwable ->
            "${throwable::class.simpleName}: ${throwable.message}"
        }
}
```

Usage:

```kotlin
result.exceptionOrNull()?.let { throwable ->
    logger.error(throwable.causeChainString())
}
```

Output:

```text
RuntimeException: UserViewModel failed to load user screen state
caused by: RuntimeException: UserRepository failed to load current user
caused by: RuntimeException: UserApi failed to fetch /users/me
caused by: IOException: Connection reset
```

---

## 29. Cause Helpers

Find a specific cause type:

```kotlin
inline fun <reified T : Throwable> Throwable.findCause(): T? {
    return generateSequence(this) { it.cause }
        .filterIsInstance<T>()
        .firstOrNull()
}
```

Usage:

```kotlin
val ioException = throwable.findCause<java.io.IOException>()
```

Find root cause:

```kotlin
fun Throwable.rootCause(): Throwable {
    return generateSequence(this) { it.cause }.last()
}
```

Usage:

```kotlin
val root = throwable.rootCause()
```

---

# Layered Failure Wrapping Pattern

## 30. API Layer

```kotlin
class UserApi(
    private val httpClient: HttpClient,
    private val json: Json,
) {
    fun fetchUserDto(): Result<UserDto> {
        return runCatching {
            httpClient.get("/users/me")
        }.mapCatching { response ->
            json.decodeFromString<UserDto>(response.body)
        }.mapFailure { throwable ->
            RuntimeException(
                "UserApi.fetchUserDto failed to fetch and parse /users/me",
                throwable
            )
        }
    }
}
```

---

## 31. Repository Layer

```kotlin
class UserRepository(
    private val userApi: UserApi,
) {
    fun loadCurrentUser(): Result<User> {
        return userApi.fetchUserDto()
            .mapCatching { dto ->
                dto.toDomain()
            }
            .mapFailure { throwable ->
                RuntimeException(
                    "UserRepository.loadCurrentUser failed to load current user",
                    throwable
                )
            }
    }
}
```

---

## 32. ViewModel / Controller Layer

```kotlin
class DashboardController(
    private val userRepository: UserRepository,
    private val bookingRepository: BookingRepository,
) {
    fun loadDashboard(): Result<DashboardState> {
        return runCatching {
            val user = userRepository.loadCurrentUser().getOrThrow()
            val bookings = bookingRepository.loadBookings(user.id).getOrThrow()

            DashboardState.Ready(
                user = user,
                bookings = bookings
            )
        }.mapFailure { throwable ->
            RuntimeException(
                "DashboardController.loadDashboard failed to load dashboard state",
                throwable
            )
        }
    }
}
```

---

## 33. UI Edge

```kotlin
controller.loadDashboard().fold(
    onSuccess = { state ->
        render(state)
    },
    onFailure = { throwable ->
        logger.error("Dashboard loading failed", throwable)

        render(
            DashboardState.Error(
                message = throwable.message ?: "Something went wrong"
            )
        )
    }
)
```

---

# Anti-Patterns

## 34. Returning Nullable to Hide Failure

Bad:

```kotlin
fun loadUser(): User? {
    return try {
        api.loadUser()
    } catch (e: Throwable) {
        null
    }
}
```

Good:

```kotlin
fun loadUser(): Result<User> {
    return runCatching {
        api.loadUser()
    }.mapFailure { throwable ->
        RuntimeException(
            "UserRepository.loadUser failed to load user",
            throwable
        )
    }
}
```

---

## 35. Passing Through Lower-Level Failure Without Context

Bad:

```kotlin
fun loadCurrentUser(): Result<User> {
    return userApi.fetchUserDto()
        .mapCatching { it.toDomain() }
}
```

Good:

```kotlin
fun loadCurrentUser(): Result<User> {
    return userApi.fetchUserDto()
        .mapCatching { it.toDomain() }
        .mapFailure { throwable ->
            RuntimeException(
                "UserRepository.loadCurrentUser failed to load current user",
                throwable
            )
        }
}
```

---

## 36. Losing Original Cause

Bad:

```kotlin
Result.failure(RuntimeException("Repository failed"))
```

Good:

```kotlin
Result.failure(RuntimeException("Repository failed", throwable))
```

---

## 37. Vague Wrapper Messages

Bad:

```kotlin
RuntimeException("Failed", throwable)
RuntimeException("Error", throwable)
RuntimeException("Something went wrong", throwable)
```

Good:

```kotlin
RuntimeException(
    "BookingRepository.createBooking failed to create booking for scheduleId=$scheduleId",
    throwable
)
```

---

## 38. Using `recover` to Hide Real Failures

Bad:

```kotlin
fun loadUser(): Result<User> {
    return repository.loadUser()
        .recover { User.empty() }
}
```

Good:

```kotlin
fun loadUser(): Result<User> {
    return repository.loadUser()
        .mapFailure { throwable ->
            RuntimeException(
                "UserService.loadUser failed to load user",
                throwable
            )
        }
}
```

---

# AI / Developer Rules

Use these rules when generating or reviewing Kotlin code.

## Rule 1

Fallible operations should usually return:

```kotlin
Result<T>
```

unless the failure is truly unrecoverable.

## Rule 2

Use `runCatching` at boundaries where exceptions may be thrown.

```kotlin
return runCatching {
    externalSdk.call()
}
```

## Rule 3

Always preserve cause.

```kotlin
RuntimeException("Meaningful layer message", cause)
```

## Rule 4

Every meaningful `Result<T>` function should wrap lower-level failure with a message describing exactly what failed in this function.

Do not simply pass through a `Throwable` from another function unless this function adds no real responsibility.

## Rule 5

Use one meaningful wrapper per function boundary, not one wrapper per line.

## Rule 6

Use `mapFailure` to add function/layer context.

```kotlin
.mapFailure {
    RuntimeException("Repository failed to load user", it)
}
```

## Rule 7

Use `mapCatching` when success transformation may throw.

```kotlin
.mapCatching { dto -> dto.toDomain() }
```

## Rule 8

Use `recover` or `recoverCatching` only when replacing failure with a valid fallback success.

```kotlin
.recover { DefaultConfig }
```

## Rule 9

Use `getOrThrow()` inside `runCatching` for sequential operations.

```kotlin
return runCatching {
    val user = loadUser().getOrThrow()
    val bookings = loadBookings(user.id).getOrThrow()
    Dashboard(user, bookings)
}.mapFailure {
    RuntimeException("DashboardController.loadDashboard failed", it)
}
```

## Rule 10

Use `fold` at the edge where success/failure becomes UI state, API response, log event, or user-visible behavior.

```kotlin
result.fold(
    onSuccess = { showData(it) },
    onFailure = { showError(it) }
)
```

## Rule 11

Do not return `null` to hide failure.

## Rule 12

Fire-and-forget coroutines must log, report, or convert failure into observable state.

## Rule 13

Coroutine cancellation is not an app failure. Rethrow `CancellationException`.

---

# Summary

Vanilla Kotlin already gives enough tools for a disciplined declarative failure-handling style:

```text
Throwable             = failure object
Throwable.cause       = failure chain
Result<T>             = success or failure as value
runCatching           = try/catch into Result
map                   = transform success
mapCatching           = transform success and catch transform errors
recover               = failure to fallback success
recoverCatching       = failure to fallback success, catching fallback errors
getOrThrow            = short-circuit inside orchestration
fold                  = final success/failure decision
exceptionOrNull       = inspect failure without consuming result
stackTraceToString    = full debug output
```

The central principle:

```text
Do not just say that something failed.
Say what this function was trying to do when it failed.
```

Use `Result<T>` to make failure explicit.
Use `Throwable.cause` to preserve the chain.
Use meaningful `RuntimeException` wrappers to add function-level context.
Use `recover` only for real fallback values.
Use `fold` only at decision boundaries.
