# 05. Declarative Failure Handling Patterns on Windows / JVM

## Mandatory Windows / JVM Failure Contract

Windows/JVM project-layer failures must use `KmpFailure`.

JVM `Throwable`, Java/Kotlin exceptions, filesystem exceptions, process exceptions, and Windows platform errors are local-only. Before the failure crosses a project, SDK, repository, service, adapter, transport, logging, diagnostics, or shared KMP boundary, convert it to `KmpFailure` and return `KmpResult.Failure`.

Good:

```kotlin
fun readConfig(): KmpResult<AppConfig> {
    return try {
        KmpResult.Success(fileConfigReader.read())
    } catch (e: Throwable) {
        KmpResult.Failure(
            e.toKmpFailure(
                message = "JvmConfigRepository.readConfig failed to read config file",
                code = "CONFIG_READ_FAILED",
                retryable = false,
                source = "JvmConfigRepository.readConfig",
                origin = KmpFailureOrigin.JVM,
            )
        )
    }
}
```

Bad:

```kotlin
fun readConfig(): AppConfig = fileConfigReader.read()
```

Bad:

```kotlin
fun readConfig(): AppConfig? = try { fileConfigReader.read() } catch (e: Throwable) { null }
```


This document defines Windows/JVM-side patterns using the shared failure library.

Use this for:

```text
jvmMain code
future windowsMain code
desktop services
CLI tools
server-style shared adapters
JVM file/storage/network adapters
Windows-specific platform adapters
```

The project-level failure contract remains:

```text
KmpResult<T>
KmpFailure
```

Windows/JVM is conceptually close to Android/Kotlin, but without Android UI, ViewModel, and WorkManager specifics.

---

## 1. Do We Need Windows Rules Now?

If the project does not have a Windows/JVM target yet, you do not need Windows implementation files now.

But if Windows/JVM support is planned, keep this document so the future platform follows the same failure-chain rules.

Rule:

```text
No active Windows/JVM target = no required Windows code.
Future Windows/JVM target = follow the same KmpFailure chain rules from the start.
```

---

## 2. Windows/JVM Role

Windows/JVM may do three things:

```text
1. Consume KmpResult<T> / KmpFailure from shared KMP.
2. Convert JVM Throwable into KmpFailure.
3. Wrap existing KmpFailure with Windows/JVM-specific context.
```

Windows/JVM must not destroy the existing failure chain.

---

## 3. Platform API Boundary

Use `kmpRunCatching` or explicit `try/catch`.

### Good pattern

```kotlin
fun readSettingsFile(): KmpResult<String> {
    return kmpRunCatching {
        settingsFile.readText()
    }.mapFailure { failure ->
        failure.wrapWindows(
            message = "WindowsSettingsStorage.readSettingsFile failed to read settings file",
            source = "WindowsSettingsStorage.readSettingsFile",
        )
    }
}
```

### Bad pattern

```kotlin
fun readSettingsFile(): String? {
    return try {
        settingsFile.readText()
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

## 4. Explicit Classification

Use `try/catch` when different JVM exceptions need different classification.

### Good pattern

```kotlin
fun readSettingsFile(): KmpResult<String> {
    return try {
        KmpResult.Success(settingsFile.readText())
    } catch (e: NoSuchFileException) {
        KmpResult.Failure(
            e.toWindowsKmpFailure(
                message = "WindowsSettingsStorage.readSettingsFile failed because settings file was not found",
                code = "SETTINGS_FILE_NOT_FOUND",
                retryable = false,
                source = "WindowsSettingsStorage.readSettingsFile",
            )
        )
    } catch (e: IOException) {
        KmpResult.Failure(
            e.toWindowsKmpFailure(
                message = "WindowsSettingsStorage.readSettingsFile failed to read settings file",
                code = "SETTINGS_FILE_READ_FAILED",
                retryable = true,
                source = "WindowsSettingsStorage.readSettingsFile",
            )
        )
    }
}
```

Rule:

```text
kmpRunCatching = compact boundary wrapper.
try/catch = explicit platform failure classification.
```

---

## 5. Logging Ownership Rule

Functions that return `KmpResult<T>` must not log.

They wrap and return.

### Bad pattern

```kotlin
fun loadConfig(): KmpResult<AppConfig> {
    val result = configRepository.loadConfig()

    result.failureOrNull()?.let { failure ->
        logger.error(failure.debugString())
    }

    return result
}
```

### Good pattern

```kotlin
fun loadConfig(): KmpResult<AppConfig> {
    return configRepository.loadConfig()
        .mapFailure { failure ->
            failure.wrapWindows(
                message = "WindowsConfigService.loadConfig failed to load config",
                source = "WindowsConfigService.loadConfig",
            )
        }
}
```

### Good consuming boundary

```kotlin
configService.loadConfig().fold(
    onSuccess = { config ->
        println("Config loaded")
    },
    onFailure = { failure ->
        logger.error(failure.debugString())
    }
)
```

---

## 6. Transformation Combinators

Use the same `KmpResult` combinators.

### map

```kotlin
fun loadConfigName(): KmpResult<String> {
    return configRepository.loadConfig()
        .map { config -> config.name }
}
```

### mapCatching

```kotlin
fun loadParsedSettings(): KmpResult<Settings> {
    return settingsRepository.readSettingsText()
        .mapCatching { text ->
            json.decodeFromString<Settings>(text)
        }
        .mapFailure { failure ->
            failure.wrapWindows(
                message = "WindowsSettingsParser.loadParsedSettings failed to parse settings",
                source = "WindowsSettingsParser.loadParsedSettings",
            )
        }
}
```

### flatMap

```kotlin
fun loadUserWithProfile(): KmpResult<UserWithProfile> {
    return userRepository.loadUser()
        .flatMap { user ->
            profileRepository.loadProfile(user.id)
                .map { profile ->
                    UserWithProfile(user, profile)
                }
        }
        .mapFailure { failure ->
            failure.wrapWindows(
                message = "WindowsUserService.loadUserWithProfile failed to load user with profile",
                source = "WindowsUserService.loadUserWithProfile",
            )
        }
}
```

---

## 7. Sequential Orchestration

Use `getOrThrow()` inside `kmpRunCatching` or `kmpSuspendRunCatching`.

### Good pattern

```kotlin
fun buildDesktopDashboard(): KmpResult<DashboardState> {
    return kmpRunCatching {
        val user = userRepository.loadUser().getOrThrow()
        val bookings = bookingRepository.loadBookings(user.id).getOrThrow()
        val payments = paymentRepository.loadPayments(user.id).getOrThrow()

        DashboardState.Ready(user, bookings, payments)
    }.mapFailure { failure ->
        failure.wrapWindows(
            message = "WindowsDashboardService.buildDesktopDashboard failed to build dashboard state",
            source = "WindowsDashboardService.buildDesktopDashboard",
        )
    }
}
```

### Bad nested style

```kotlin
fun buildDesktopDashboard(): KmpResult<DashboardState> {
    return userRepository.loadUser().fold(
        onSuccess = { user ->
            bookingRepository.loadBookings(user.id).map { bookings ->
                DashboardState.Ready(user, bookings)
            }
        },
        onFailure = { failure ->
            KmpResult.Failure(failure)
        }
    )
}
```

Why bad:

```text
Nested fold is verbose and usually misses a unified function-level wrapper.
```

---

## 8. Fallback

Use `recover` / `recoverCatching` only when fallback is valid.

### Good pattern

```kotlin
fun loadConfig(): KmpResult<AppConfig> {
    return remoteConfigRepository.loadConfig()
        .recoverCatching {
            localConfigRepository.loadConfig().getOrThrow()
        }
        .mapFailure { failure ->
            failure.wrapWindows(
                message = "WindowsConfigRepository.loadConfig failed to load remote config and fallback local config",
                source = "WindowsConfigRepository.loadConfig",
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

## 9. Suspend and Cancellation

If using coroutines on JVM/Windows, preserve cancellation.

### Bad pattern

```kotlin
suspend fun loadUser(): KmpResult<User> {
    return try {
        KmpResult.Success(api.loadUser())
    } catch (e: Throwable) {
        KmpResult.Failure(e.toWindowsKmpFailure())
    }
}
```

Why bad:

```text
CancellationException is caught and converted into app failure.
```

### Good pattern

```kotlin
suspend fun loadUser(): KmpResult<User> {
    return kmpSuspendRunCatching {
        api.loadUser()
    }.mapFailure { failure ->
        failure.wrapWindows(
            message = "WindowsUserApi.loadUser failed to load user",
            source = "WindowsUserApi.loadUser",
        )
    }
}
```

---

## 10. Optional Values

Do not use failure for normal absence.

### Good pattern

```kotlin
fun findCachedUser(): KmpResult<User?> {
    return kmpRunCatching {
        cache.findUser()
    }.mapFailure { failure ->
        failure.wrapWindows(
            message = "WindowsUserCache.findCachedUser failed to read cached user",
            source = "WindowsUserCache.findCachedUser",
        )
    }
}
```

Meaning:

```text
KmpResult.Failure = cache read failed.
KmpResult.Success(null) = cache works, but user is absent.
```

---

## 11. Validation

Validation can return `KmpResult<T>`.

### Good pattern

```kotlin
fun validateEmail(email: String): KmpResult<String> {
    return if ("@" in email) {
        KmpResult.Success(email)
    } else {
        KmpResult.Failure(
            KmpFailure(
                message = "WindowsSignupValidator.validateEmail failed: email does not contain @",
                code = "VALIDATION_ERROR",
                source = "WindowsSignupValidator.validateEmail",
                origin = KmpFailureOrigin.WINDOWS,
            )
        )
    }
}
```

---

## 12. Routine That Must Throw

Some JVM APIs require throwing.

Use this only at integration boundaries.

### Good pattern

```kotlin
fun loadUserOrThrow(): User {
    return try {
        userRepository.loadUser().getOrThrow()
    } catch (e: KmpFailureException) {
        throw e.failure.wrapWindows(
            message = "WindowsUserService.loadUserOrThrow failed to load user",
            source = "WindowsUserService.loadUserOrThrow",
        ).toException()
    }
}
```

---

## 13. Windows/JVM Anti-Patterns

Bad:

```kotlin
fun loadUser(): User?
```

when failure is meaningful.

Bad:

```kotlin
KmpFailure(message = "Failed")
```

because it is vague and loses context.

Bad:

```kotlin
KmpFailure(message = existingFailure.message)
```

because it loses the cause chain.

Bad:

```kotlin
val user = userRepository.loadUser().getOrNull()
```

because a real failure becomes null.

Bad:

```kotlin
fun loadUser(): KmpResult<User> {
    val result = repository.loadUser()
    logger.error(result.failureOrNull()?.debugString())
    return result
}
```

because logging happens before the consuming boundary.

---

## 14. Windows/JVM AI / Developer Rules

1. Use `KmpResult<T>` for Windows/JVM fallible project operations.
2. Use `origin = WINDOWS` when Windows-specific code adds a wrapper.
3. Preserve existing `KmpFailure` as `cause`.
4. Convert JVM `Throwable` to `KmpFailure` at platform boundaries.
5. Use `map`, `mapCatching`, `flatMap`, `recover`, and `fold` with the same meaning as KMP.
6. Use `getOrThrow()` inside catching helpers for orchestration.
7. Preserve coroutine cancellation.
8. Log only at consuming boundaries.
9. Do not use `getOrNull()` as a normal project pattern.

---

## JVM / Windows Do Not Use KmpResultSwift

`KmpResultSwift<T>` exists for Swift/iOS/macOS interop only.

JVM and Windows Kotlin code should consume `KmpResult<T>` directly with `fold` or sealed `when`.

Good:

```kotlin
result.fold(
    onSuccess = { value -> handle(value) },
    onFailure = { failure -> handleFailure(failure) },
)
```

Bad:

```kotlin
val resultSwift = result.asKmpResultSwift()
```

Why bad:

```text
Kotlin/JVM understands KmpResult.Failure as KmpResult<Nothing>.
The Swift-safe projection is not needed on JVM or Windows Kotlin code.
```

---

## KmpFailureException Is Not Normal JVM / Windows Flow

JVM/Windows Kotlin code consumes `KmpResult<T>` directly.

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
