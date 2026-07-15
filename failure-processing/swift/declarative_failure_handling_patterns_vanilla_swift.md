# Declarative Failure Handling Patterns in Vanilla Swift

These instructions define a disciplined, Swift-native approach to failure handling.

The goal is to use only vanilla Swift tools while applying declarative failure-handling principles:

- make failures explicit;
- preserve the full cause chain;
- wrap failures at meaningful function boundaries;
- explain exactly what failed in each function;
- avoid optional values as hidden failures;
- avoid silently losing async and background failures.

No custom `Either`.
No custom `AppResult`.
No custom failure hierarchy is required.

Use vanilla Swift tools:

```swift
Error
LocalizedError
Result<Success, Failure>
Result { try ... }
map
flatMap
mapError
get()
do / catch
try
try await
async throws
Task
CancellationError
```

Swift does not have a built-in equivalent of JVM `Throwable.cause`.
To preserve a cause chain, use one small contextual error wrapper.

---

## 1. Core Idea

If failure is part of the operation contract, either return `Result<T, Error>` or use `throws`.

For synchronous APIs where the caller should receive failure as a value, use `Result<T, Error>`.

```swift
func loadUser() -> Result<User, Error> {
    Result {
        try api.loadUser()
    }
}
```

For native Swift flow, especially async code, prefer `throws` / `async throws`.

```swift
func loadUser() async throws -> User {
    try await api.loadUser()
}
```

Avoid this:

```swift
func loadUser() -> User? {
    do {
        return try api.loadUser()
    } catch {
        return nil
    }
}
```

Optional means:

```text
Value may be absent.
```

`Result<T, Error>` or `throws` means:

```text
Operation may fail.
```

Do not mix these meanings.

---

## 2. Use `Result<T, Error>` for Fallible Operations When Failure Is a Value

Use `Result<T, Error>` when the caller should decide how to handle the failure and you want to pass success/failure as data.

Good examples:

```swift
func loadUser() -> Result<User, Error>
func saveBooking(_ booking: Booking) -> Result<Void, Error>
func fetchRemoteConfig() -> Result<AppConfig, Error>
func parseUserDto(_ json: String) -> Result<UserDto, Error>
```

Avoid:

```swift
func loadUser() -> User?
func loadUser() -> User
func loadUser() -> (User?, Error?)
```

The function signature should communicate:

```text
This operation may fail, and the caller must decide what to do.
```

---

## 3. Prefer `throws` / `async throws` for Native Swift Flow

Swift code often reads best when fallible operations throw directly.

```swift
func loadCurrentUser() throws -> User {
    do {
        let dto = try userApi.fetchUserDto()
        return try dto.toDomain()
    } catch {
        throw AppContextError(
            "UserRepository.loadCurrentUser failed to load current user",
            cause: error
        )
    }
}
```

Async version:

```swift
func loadCurrentUser() async throws -> User {
    do {
        let dto = try await userApi.fetchUserDto()
        return try dto.toDomain()
    } catch is CancellationError {
        throw error
    } catch {
        throw AppContextError(
            "UserRepository.loadCurrentUser failed to load current user",
            cause: error
        )
    }
}
```

Rule:

```text
Use throws for normal Swift control flow.
Use Result when success/failure must be stored, passed, combined, or returned as a value.
```

---

## 4. Logging Ownership Rule

Functions that return `Result<T, Error>` or throw should usually not log failures.

They should only wrap the lower-level `Error` with clear function-level context and return or throw the new error.

Logging belongs to the function that consumes the failure and converts it into a final side effect:

```text
UI state
API response
background job result
crash report
user-visible behavior
legacy callback
Void-returning side effect
```

Rule:

```text
If the function returns Result<T, Error>, wrap but do not log.
If the function throws, wrap but do not log unless this is the final boundary.
If the function consumes the failure and does not return or throw it further, log/report/convert.
```

---

## 5. Add a Small Cause-Preserving Error Wrapper

Swift `Error` does not have a standard `cause` property.

For this pattern, use a small wrapper:

```swift
struct AppContextError: Error, CustomStringConvertible, LocalizedError {
    let message: String
    let cause: Error?

    init(_ message: String, cause: Error? = nil) {
        self.message = message
        self.cause = cause
    }

    var errorDescription: String? {
        message
    }

    var description: String {
        if let cause {
            return "\(message)\ncaused by: \(cause)"
        } else {
            return message
        }
    }
}
```

This is not a failure framework.
It only fills one important ergonomic gap in vanilla Swift.

---

## 6. Always Preserve the Cause

This is the most important rule.

Bad:

```swift
throw AppContextError("Repository failed")
```

Good:

```swift
throw AppContextError("Repository failed", cause: error)
```

Bad:

```swift
return .failure(AppContextError("Repository failed"))
```

Good:

```swift
return .failure(AppContextError("Repository failed", cause: error))
```

The original `Error` should almost never be destroyed.

---

## 7. Use `Result { try ... }` at Operation Boundaries

Use `Result { try ... }` where external or risky code may throw.

```swift
func fetchUserDto() -> Result<UserDto, Error> {
    Result {
        try httpClient.get("/users/me")
    }
}
```

Good places for `Result { try ... }`:

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

```swift
func readSettings() -> Result<Settings, Error> {
    Result {
        let text = try file.readText()
        return try json.decode(Settings.self, from: text)
    }
}
```

This is better than returning `nil`, because the error is preserved.

---

## 8. Use `do/catch` When Mapping Needs Branches

`Result { try ... }` is compact.

`do/catch` is better when different error types need different treatment.

```swift
func fetchUser() -> Result<UserDto, Error> {
    do {
        let user = try api.fetchUser()
        return .success(user)
    } catch let error as URLError where error.code == .timedOut {
        return .failure(
            AppContextError("User request timed out", cause: error)
        )
    } catch let error as URLError {
        return .failure(
            AppContextError("Network error while fetching user", cause: error)
        )
    } catch {
        return .failure(
            AppContextError("Unexpected error while fetching user", cause: error)
        )
    }
}
```

Rule:

```text
Result { try ... } = compact boundary wrapper.
do/catch = explicit error classification.
```

---

## 9. Always Wrap Failure at the Function Boundary

Each meaningful fallible function should add its own context before returning or throwing failure.

Do not simply pass through a failure received from another function.

Bad:

```swift
func loadCurrentUser() -> Result<User, Error> {
    userApi.fetchUserDto()
        .map { dto in
            dto.toDomain()
        }
}
```

This loses important information.

If this fails, the caller only sees something like:

```text
UserApi failed to fetch /users/me
```

But the caller does not know that the failure happened while `UserRepository` was trying to load the current user.

Good:

```swift
func loadCurrentUser() -> Result<User, Error> {
    userApi.fetchUserDto()
        .flatMap { dto in
            Result {
                try dto.toDomain()
            }
        }
        .mapError { error in
            AppContextError(
                "UserRepository.loadCurrentUser failed to load and map current user",
                cause: error
            )
        }
}
```

Now the chain explains the path:

```text
AppContextError: UserRepository.loadCurrentUser failed to load and map current user
caused by: AppContextError: UserApi.fetchUserDto failed to fetch /users/me
caused by: URLError: connection lost
```

Principle:

```text
Every meaningful fallible function is responsible for explaining
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

```swift
AppContextError(
    "UserRepository.loadCurrentUser failed to load current user",
    cause: error
)

AppContextError(
    "BookingRepository.createBooking failed to create booking for scheduleId=\(scheduleId)",
    cause: error
)

AppContextError(
    "DashboardController.loadDashboard failed to build dashboard state",
    cause: error
)

AppContextError(
    "UserApi.fetchUserDto failed to fetch and parse /users/me",
    cause: error
)
```

Avoid vague messages:

```swift
AppContextError("Failed", cause: error)
AppContextError("Something went wrong", cause: error)
AppContextError("Repository error", cause: error)
```

---

## 9.1. Wrapping Rule

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

```swift
func fetchUserDto() -> Result<UserDto, Error>
func loadCurrentUser() -> Result<User, Error>
func loadDashboard() -> Result<DashboardState, Error>
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
caused by: URLError: connection lost
```

---

## 9.2. Do Not Wrap Meaningless Micro-Steps

Do not wrap every tiny internal line.

Bad:

```swift
func loadCurrentUser() -> Result<User, Error> {
    userApi.fetchUserDto()
        .mapError {
            AppContextError("Failed before mapping", cause: $0)
        }
        .flatMap { dto in
            Result { try dto.toDomain() }
        }
        .mapError {
            AppContextError("Failed after mapping", cause: $0)
        }
}
```

Good:

```swift
func loadCurrentUser() -> Result<User, Error> {
    userApi.fetchUserDto()
        .flatMap { dto in
            Result { try dto.toDomain() }
        }
        .mapError { error in
            AppContextError(
                "UserRepository.loadCurrentUser failed to fetch DTO and map it to domain user",
                cause: error
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

## 10. Use `map` for Safe Success Transformation

Use `map` when transforming a successful value and the transform does not throw.

```swift
func loadUserName() -> Result<String, Error> {
    loadUser()
        .map { user in
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

## 11. Add `mapCatching` as a Small Vanilla Extension

Swift has `map`, `flatMap`, and `mapError`, but it does not have a standard `mapCatching`.

For declarative failure handling, this tiny extension is useful:

```swift
extension Result where Failure == Error {
    func mapCatching<NewSuccess>(
        _ transform: (Success) throws -> NewSuccess
    ) -> Result<NewSuccess, Error> {
        flatMap { value in
            Result {
                try transform(value)
            }
        }
    }
}
```

Usage:

```swift
func loadUser() -> Result<User, Error> {
    api.fetchUserDto()
        .mapCatching { dto in
            try dto.toDomain()
        }
        .mapError { error in
            AppContextError(
                "UserRepository.loadUser failed to load user",
                cause: error
            )
        }
}
```

---

## 12. Use `mapCatching` When Transformation May Throw

Use `mapCatching` when the success transformation can throw and should be converted into `Result.failure`.

```swift
func parseUser(response: HttpResponse) -> Result<UserDto, Error> {
    Result.success(response)
        .mapCatching { value in
            try json.decode(UserDto.self, from: value.body)
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

```swift
func fetchUserDto() -> Result<UserDto, Error> {
    Result {
        try httpClient.get("/users/me")
    }
    .mapCatching { response in
        try json.decode(UserDto.self, from: response.body)
    }
    .mapError { error in
        AppContextError(
            "UserApi.fetchUserDto failed to fetch and parse current user",
            cause: error
        )
    }
}
```

---

## 13. Use `recover` Only for Valid Fallback Success

Swift does not have a standard `recover`, but you can add a tiny helper.

```swift
extension Result {
    func recover(_ fallback: (Failure) -> Success) -> Result<Success, Failure> {
        switch self {
        case .success(let value):
            return .success(value)
        case .failure(let error):
            return .success(fallback(error))
        }
    }
}
```

Good:

```swift
func loadConfig() -> Result<AppConfig, Error> {
    remoteConfig.load()
        .recover { _ in
            AppConfig.default
        }
}
```

This is correct if default config is a valid fallback.

Bad:

```swift
func loadUser() -> Result<User, Error> {
    repository.loadUser()
        .recover { _ in
            User.empty
        }
}
```

This is bad if `User.empty` hides a real data-loading problem.

Rule:

```text
recover means: failure is acceptable and can become a valid success.
```

---

## 14. Use `recoverCatching` When Fallback May Throw

Use `recoverCatching` when fallback logic itself may fail.

```swift
extension Result where Failure == Error {
    func recoverCatching(
        _ fallback: (Error) throws -> Success
    ) -> Result<Success, Error> {
        switch self {
        case .success(let value):
            return .success(value)
        case .failure(let error):
            return Result {
                try fallback(error)
            }
        }
    }
}
```

Example:

```swift
func loadConfig() -> Result<AppConfig, Error> {
    remoteConfig.load()
        .recoverCatching { _ in
            try localConfig.loadFromDisk().get()
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

## 15. Use `get()` for Sequential Orchestration

`get()` returns the value if the result is successful, or throws the stored `Error` if it failed.

This is useful inside `Result { try ... }`.

Bad nested style:

```swift
func buildDashboard() -> Result<Dashboard, Error> {
    userRepository.loadUser().flatMap { user in
        bookingRepository.loadBookings(userId: user.id).map { bookings in
            Dashboard(user: user, bookings: bookings)
        }
    }
}
```

Good sequential style:

```swift
func buildDashboard() -> Result<Dashboard, Error> {
    Result {
        let user = try userRepository.loadUser().get()
        let bookings = try bookingRepository.loadBookings(userId: user.id).get()

        return Dashboard(user: user, bookings: bookings)
    }
    .mapError { error in
        AppContextError(
            "DashboardViewModel.buildDashboard failed to build dashboard state",
            cause: error
        )
    }
}
```

Pattern:

```text
Use get() inside Result { try ... } to short-circuit on the first failure.
Then wrap once at the current function boundary.
```

---

## 16. Use `switch` or `fold` at Decision Points

Swift usually uses `switch` for final success/failure decisions.

```swift
switch repository.loadUser() {
case .success(let user):
    showUser(user)
case .failure(let error):
    showError(error.localizedDescription)
}
```

You can also add a tiny `fold` helper if you want Kotlin-like ergonomics:

```swift
extension Result {
    func fold<T>(
        onSuccess: (Success) -> T,
        onFailure: (Failure) -> T
    ) -> T {
        switch self {
        case .success(let value):
            return onSuccess(value)
        case .failure(let error):
            return onFailure(error)
        }
    }
}
```

Example in service:

```swift
let response = createBooking(request).fold(
    onSuccess: { booking in
        BookingResponse.success(booking.id)
    },
    onFailure: { error in
        BookingResponse.failure(
            message: error.localizedDescription
        )
    }
)
```

Rule:

```text
Use switch/fold at the edge where you must make a decision.
Do not overuse switch/fold for internal chaining.
```

---

## 17. Inspect Failure Only at Consuming Boundaries

Use `if case .failure` only when a function consumes a `Result` and does not return it further.

Good:

```swift
func runSyncJob() {
    let result = syncRepository.sync()

    if case .failure(let error) = result {
        logger.error("SyncJob.runSyncJob failed", error)
    }
}
```

Bad:

```swift
func loadUser() -> Result<User, Error> {
    let result = repository.loadUser()

    if case .failure(let error) = result {
        logger.error("Failed to load user", error)
    }

    return result
}
```

This is bad because the same failure may be logged again by the caller.

Do not inspect and log a failure if the same `Result<T, Error>` is still being returned upward.

---

## 18. Do Not Use `try?` to Hide Real Failure

`try?` converts any thrown error into `nil`.

Bad:

```swift
func loadUser() -> User? {
    try? api.loadUser()
}
```

This destroys the error.

Good:

```swift
func loadUser() -> Result<User, Error> {
    Result {
        try api.loadUser()
    }
    .mapError { error in
        AppContextError(
            "UserRepository.loadUser failed to load user",
            cause: error
        )
    }
}
```

Use `try?` only when failure is genuinely equivalent to absence and you do not need the reason.

---

# Direct Failure Handling Patterns by Routine Type

## 19. Simple Synchronous Routine

Use `Result { try ... }` or `do/catch`.

```swift
func parseUser(jsonText: Data) -> Result<UserDto, Error> {
    Result {
        try json.decode(UserDto.self, from: jsonText)
    }
    .mapError { error in
        AppContextError(
            "UserParser.parseUser failed to parse UserDto from JSON",
            cause: error
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

## 20. Synchronous Routine Calling Another `Result<T, Error>` Routine

Use `get()` inside `Result { try ... }`.

```swift
func loadCurrentUser() -> Result<User, Error> {
    Result {
        let dto = try userApi.fetchUserDto().get()
        return try dto.toDomain()
    }
    .mapError { error in
        AppContextError(
            "UserRepository.loadCurrentUser failed to load current user",
            cause: error
        )
    }
}
```

This is better than returning the lower-level result directly because this function adds its own context.

---

## 21. Sequential Orchestration Routine

Use `get()` for each step.

```swift
func loadDashboard() -> Result<DashboardState, Error> {
    Result {
        let user = try userRepository.loadCurrentUser().get()
        let bookings = try bookingRepository.loadBookings(userId: user.id).get()
        let payments = try paymentRepository.loadPayments(userId: user.id).get()

        return DashboardState.ready(
            user: user,
            bookings: bookings,
            payments: payments
        )
    }
    .mapError { error in
        AppContextError(
            "DashboardController.loadDashboard failed to build dashboard state",
            cause: error
        )
    }
}
```

This gives a clean chain and avoids nested `switch` or `flatMap` pyramids.

---

## 22. Async Throwing Routine

Prefer `async throws` in native Swift async code.

```swift
func loadCurrentUser() async throws -> User {
    do {
        let dto = try await userApi.fetchUserDto()
        return try dto.toDomain()
    } catch is CancellationError {
        throw error
    } catch {
        throw AppContextError(
            "UserRepository.loadCurrentUser failed to load current user",
            cause: error
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

## 23. Async Routine Returning `Result<T, Error>`

Use this only when you intentionally want failure as a value.

```swift
func loadCurrentUserResult() async -> Result<User, Error> {
    do {
        let user = try await loadCurrentUser()
        return .success(user)
    } catch is CancellationError {
        return .failure(error)
    } catch {
        return .failure(
            AppContextError(
                "UserRepository.loadCurrentUserResult failed to load current user",
                cause: error
            )
        )
    }
}
```

Important:

```text
If cancellation should cancel the task, rethrow it in async throws functions.
If the function returns Result, decide explicitly whether cancellation is represented as failure or handled by the caller.
```

---

## 24. Fire-and-Forget Task Routine

If the routine is launched and does not return `Result` or throw to a caller, catch and log inside the task.

Bad:

```swift
Task {
    try await syncRepository.sync()
}
```

The failure may be ignored if nobody awaits the task value.

Good:

```swift
Task {
    do {
        try await syncRepository.sync()
    } catch is CancellationError {
        return
    } catch {
        logger.error(
            "SyncWorker.startSync failed during background sync",
            AppContextError(
                "SyncWorker.startSync failed during background sync",
                cause: error
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

## 25. Routine That Updates UI State

Convert failure into explicit UI state at the edge.

```swift
func loadUserScreen() {
    Task { @MainActor in
        state = .loading

        do {
            let user = try await userRepository.loadCurrentUser()
            state = .ready(user)
        } catch is CancellationError {
            return
        } catch {
            logger.error("UserViewModel.loadUserScreen failed", error)

            state = .error(
                message: error.localizedDescription
            )
        }
    }
}
```

Do not let UI parse random technical details too deeply.

The ViewModel/controller is the correct place to turn failure into UI state.

---

## 26. Routine with Fallback

Use `recover` or `do/catch` only when fallback is a valid success.

Result style:

```swift
func loadConfig() -> Result<AppConfig, Error> {
    remoteConfigRepository.loadRemoteConfig()
        .recoverCatching { _ in
            try localConfigRepository.loadLocalConfig().get()
        }
        .mapError { error in
            AppContextError(
                "ConfigRepository.loadConfig failed to load remote config and fallback local config",
                cause: error
            )
        }
}
```

Throwing style:

```swift
func loadConfig() throws -> AppConfig {
    do {
        return try remoteConfigRepository.loadRemoteConfig()
    } catch {
        do {
            return try localConfigRepository.loadLocalConfig()
        } catch {
            throw AppContextError(
                "ConfigRepository.loadConfig failed to load remote config and fallback local config",
                cause: error
            )
        }
    }
}
```

This means:

```text
Try remote config.
If remote fails, try local config.
If local also fails, return/throw failure with full context.
```

Do not use fallback to hide serious failure.

Bad:

```swift
func loadUser() -> Result<User, Error> {
    userRepository.loadCurrentUser()
        .recover { _ in User.empty }
}
```

unless `User.empty` is a real valid business state.

---

## 27. Routine with Optional Value

Do not use failure for normal absence.

Good:

```swift
func findCachedUser() -> Result<User?, Error> {
    Result {
        try cache.findUser()
    }
    .mapError { error in
        AppContextError(
            "UserCache.findCachedUser failed to read cached user",
            cause: error
        )
    }
}
```

Meaning:

```text
Result failure = cache read failed.
Success nil = cache works, but user is absent.
```

Do not write:

```swift
func findCachedUser() -> Result<User, Error>
```

and return failure just because user is not cached.

---

## 28. Routine with Validation

Validation can be handled as `Result<T, Error>` with a standard or small validation error.

```swift
struct ValidationError: Error, LocalizedError {
    let message: String

    var errorDescription: String? {
        message
    }
}

func validateEmail(_ email: String) -> Result<String, Error> {
    if email.contains("@") {
        return .success(email)
    } else {
        return .failure(
            ValidationError(message: "validateEmail failed: email does not contain @")
        )
    }
}
```

When used inside a bigger routine:

```swift
func createUser(email: String) -> Result<User, Error> {
    Result {
        let validEmail = try validateEmail(email).get()
        return User(email: validEmail)
    }
    .mapError { error in
        AppContextError(
            "UserFactory.createUser failed to create user from email",
            cause: error
        )
    }
}
```

Chain:

```text
AppContextError: UserFactory.createUser failed to create user from email
caused by: ValidationError: validateEmail failed: email does not contain @
```

---

## 29. Routine That Must Throw Instead of Return `Result<T, Error>`

Some APIs require throwing.

Then throw with cause.

```swift
func loadCurrentUserOrThrow() throws -> User {
    do {
        return try userRepository.loadCurrentUser().get()
    } catch {
        throw AppContextError(
            "UserService.loadCurrentUserOrThrow failed to load current user",
            cause: error
        )
    }
}
```

If this is an async function, preserve cancellation:

```swift
func loadCurrentUserOrThrow() async throws -> User {
    do {
        return try await userRepository.loadCurrentUser()
    } catch is CancellationError {
        throw error
    } catch {
        throw AppContextError(
            "UserService.loadCurrentUserOrThrow failed to load current user",
            cause: error
        )
    }
}
```

---

# Logging Patterns

## 30. Log the Full Error

For normal logging:

```swift
logger.error("Failed to load user", error)
```

If the error conforms to `CustomStringConvertible`, this can include the cause chain:

```swift
logger.error(String(describing: error))
```

---

## 31. Compact Cause-Chain Logging

For compact cause-chain logs, add a helper around `AppContextError`.

```swift
extension Error {
    func causeChainString() -> String {
        var lines: [String] = []
        var current: Error? = self

        while let error = current {
            lines.append(String(describing: error))

            if let contextError = error as? AppContextError {
                current = contextError.cause
            } else {
                current = nil
            }
        }

        return lines.joined(separator: "\ncaused by: ")
    }
}
```

Usage:

```swift
if case .failure(let error) = result {
    logger.error(error.causeChainString())
}
```

Output:

```text
UserViewModel.loadUserScreen failed
caused by: UserRepository.loadCurrentUser failed to load current user
caused by: UserApi.fetchUserDto failed to fetch /users/me
caused by: URLError: connection lost
```

---

## 32. Root Cause Helper

Find root cause:

```swift
extension Error {
    func rootCause() -> Error {
        var current: Error = self

        while let contextError = current as? AppContextError,
              let cause = contextError.cause {
            current = cause
        }

        return current
    }
}
```

Usage:

```swift
let root = error.rootCause()
```

---

# Layered Failure Wrapping Pattern

## 33. API Layer

```swift
final class UserApi {
    private let httpClient: HttpClient
    private let json: JSONDecoder

    init(httpClient: HttpClient, json: JSONDecoder) {
        self.httpClient = httpClient
        self.json = json
    }

    func fetchUserDto() -> Result<UserDto, Error> {
        Result {
            try httpClient.get("/users/me")
        }
        .mapCatching { response in
            try json.decode(UserDto.self, from: response.body)
        }
        .mapError { error in
            AppContextError(
                "UserApi.fetchUserDto failed to fetch and parse /users/me",
                cause: error
            )
        }
    }
}
```

---

## 34. Repository Layer

```swift
final class UserRepository {
    private let userApi: UserApi

    init(userApi: UserApi) {
        self.userApi = userApi
    }

    func loadCurrentUser() -> Result<User, Error> {
        userApi.fetchUserDto()
            .mapCatching { dto in
                try dto.toDomain()
            }
            .mapError { error in
                AppContextError(
                    "UserRepository.loadCurrentUser failed to load current user",
                    cause: error
                )
            }
    }
}
```

---

## 35. ViewModel / Controller Layer

```swift
final class DashboardController {
    private let userRepository: UserRepository
    private let bookingRepository: BookingRepository

    init(
        userRepository: UserRepository,
        bookingRepository: BookingRepository
    ) {
        self.userRepository = userRepository
        self.bookingRepository = bookingRepository
    }

    func loadDashboard() -> Result<DashboardState, Error> {
        Result {
            let user = try userRepository.loadCurrentUser().get()
            let bookings = try bookingRepository.loadBookings(userId: user.id).get()

            return DashboardState.ready(
                user: user,
                bookings: bookings
            )
        }
        .mapError { error in
            AppContextError(
                "DashboardController.loadDashboard failed to load dashboard state",
                cause: error
            )
        }
    }
}
```

---

## 36. UI Edge

```swift
switch controller.loadDashboard() {
case .success(let state):
    render(state)

case .failure(let error):
    logger.error("Dashboard loading failed", error)

    render(
        DashboardState.error(
            message: error.localizedDescription
        )
    )
}
```

---

# Anti-Patterns

## 37. Returning Optional to Hide Failure

Bad:

```swift
func loadUser() -> User? {
    try? api.loadUser()
}
```

Good:

```swift
func loadUser() -> Result<User, Error> {
    Result {
        try api.loadUser()
    }
    .mapError { error in
        AppContextError(
            "UserRepository.loadUser failed to load user",
            cause: error
        )
    }
}
```

---

## 38. Passing Through Lower-Level Failure Without Context

Bad:

```swift
func loadCurrentUser() -> Result<User, Error> {
    userApi.fetchUserDto()
        .mapCatching { dto in
            try dto.toDomain()
        }
}
```

Good:

```swift
func loadCurrentUser() -> Result<User, Error> {
    userApi.fetchUserDto()
        .mapCatching { dto in
            try dto.toDomain()
        }
        .mapError { error in
            AppContextError(
                "UserRepository.loadCurrentUser failed to load current user",
                cause: error
            )
        }
}
```

---

## 39. Losing Original Cause

Bad:

```swift
.failure(AppContextError("Repository failed"))
```

Good:

```swift
.failure(AppContextError("Repository failed", cause: error))
```

Bad:

```swift
throw AppContextError("Repository failed")
```

Good:

```swift
throw AppContextError("Repository failed", cause: error)
```

---

## 40. Vague Wrapper Messages

Bad:

```swift
AppContextError("Failed", cause: error)
AppContextError("Error", cause: error)
AppContextError("Something went wrong", cause: error)
```

Good:

```swift
AppContextError(
    "BookingRepository.createBooking failed to create booking for scheduleId=\(scheduleId)",
    cause: error
)
```

---

## 41. Using Fallback to Hide Real Failures

Bad:

```swift
func loadUser() -> Result<User, Error> {
    repository.loadUser()
        .recover { _ in
            User.empty
        }
}
```

Good:

```swift
func loadUser() -> Result<User, Error> {
    repository.loadUser()
        .mapError { error in
            AppContextError(
                "UserService.loadUser failed to load user",
                cause: error
            )
        }
}
```

---

# AI / Developer Rules

Use these rules when generating or reviewing Swift code.

## Rule 1

Fallible operations should usually use one of these:

```swift
func operation() throws -> T
func operation() async throws -> T
func operation() -> Result<T, Error>
```

Do not hide real failure with optional values.

---

## Rule 2

Use `Result { try ... }` at boundaries where exceptions may be thrown and failure should be returned as a value.

```swift
return Result {
    try externalSdk.call()
}
```

---

## Rule 3

Use `do/catch` when different error types need different treatment.

```swift
do {
    return try api.call()
} catch let error as URLError where error.code == .timedOut {
    throw AppContextError("Request timed out", cause: error)
} catch {
    throw AppContextError("Request failed", cause: error)
}
```

---

## Rule 4

Always preserve cause.

```swift
AppContextError("Meaningful layer message", cause: error)
```

---

## Rule 5

Every meaningful fallible function should wrap lower-level failure with a message describing exactly what failed in this function.

Do not simply pass through an `Error` from another function unless this function adds no real responsibility.

---

## Rule 6

Use one meaningful wrapper per function boundary, not one wrapper per line.

---

## Rule 7

Use `mapError` to add function/layer context to `Result` failures.

```swift
.mapError {
    AppContextError("Repository failed to load user", cause: $0)
}
```

---

## Rule 8

Use `mapCatching` when success transformation may throw.

```swift
.mapCatching { dto in
    try dto.toDomain()
}
```

---

## Rule 9

Use fallback only when replacing failure with a valid success.

```swift
.recover { _ in DefaultConfig.default }
```

---

## Rule 10

Use `get()` inside `Result { try ... }` for sequential operations.

```swift
return Result {
    let user = try loadUser().get()
    let bookings = try loadBookings(userId: user.id).get()
    return Dashboard(user: user, bookings: bookings)
}
.mapError {
    AppContextError("DashboardController.loadDashboard failed", cause: $0)
}
```

---

## Rule 11

Use `switch` or `fold` at the edge where success/failure becomes UI state, API response, log event, or user-visible behavior.

```swift
switch result {
case .success(let value):
    showData(value)
case .failure(let error):
    showError(error)
}
```

---

## Rule 12

Do not return `nil` to hide failure.

---

## Rule 13

Fire-and-forget tasks must log, report, or convert failure into observable state.

---

## Rule 14

Swift task cancellation is not an app failure.
Rethrow or explicitly handle `CancellationError`.

---

# Summary

Vanilla Swift already gives enough tools for a disciplined declarative failure-handling style:

```text
Error                 = failure object
AppContextError.cause = failure chain
Result<T, Error>     = success or failure as value
Result { try ... }   = throwing operation into Result
throws               = native Swift failure propagation
async throws         = native Swift async failure propagation
map                  = transform success
flatMap              = chain Result operations
mapError             = transform failure
get()                = short-circuit inside orchestration
switch               = final success/failure decision
try?                 = optional conversion; avoid for real failures
```

The central principle:

```text
Do not just say that something failed.
Say what this function was trying to do when it failed.
```

Use `Result<T, Error>` when failure must be explicit as a value.
Use `throws` / `async throws` for native Swift flow.
Use `AppContextError.cause` to preserve the chain.
Use meaningful wrapper messages to add function-level context.
Use fallback only for real fallback values.
Use `switch` or `fold` only at decision boundaries.
