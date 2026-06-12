import kotlinx.coroutines.CancellationException

class AuthService(
    private val firebaseAuth: FirebaseAuth,
    private val httpClient: HttpClient,
    private val json: Json,
    private val logger: Logger,
) {

    /**
     * Public compatibility boundary.
     *
     * The public API may still return null if existing code expects it.
     *
     * But the real failure-aware logic now lives in authorizeUserResult().
     */
    suspend fun authorizeUser(): AuthResponse? {
        return authorizeUserResult()
            .onFailure { throwable ->
                // Vanilla Kotlin/JVM logging:
                //
                // Because every wrapper preserves `cause`,
                // the logger can print the full failure chain naturally.
                logger.error(
                    "AuthService.authorizeUser failed to authorize user",
                    throwable,
                )
            }
            .getOrNull()
    }

    /**
     * Main declarative failure-processing function.
     *
     * This function describes the operation contract:
     *
     * Success = current Firebase user was authorized and AuthResponse was received.
     * Failure = authorization failed and caller can inspect the Throwable chain.
     */
    suspend fun authorizeUserResult(): Result<AuthResponse> {
        return suspendRunCatching {
            val firebaseToken = readFirebaseTokenResult(forceRefresh = false)
                .getOrThrow()

            val request = buildAuthRequestResult(firebaseToken)
                .getOrThrow()

            val responseText = postAuthorizeRequestResult(request)
                .getOrThrow()

            parseAuthResponseResult(responseText)
                .getOrThrow()
        }.mapFailure { throwable ->
            RuntimeException(
                "AuthService.authorizeUserResult failed to authorize current Firebase user",
                throwable,
            )
        }
    }

    /**
     * Meaningful function boundary:
     *
     * Firebase user/token reading can fail independently from request building,
     * HTTP authorization, or response parsing.
     */
    private suspend fun readFirebaseTokenResult(
        forceRefresh: Boolean,
    ): Result<String> {
        return suspendRunCatching {
            val user = requireNotNull(firebaseAuth.currentUser) {
                "Firebase currentUser was null"
            }

            requireNotNull(user.getIdToken(forceRefresh).token) {
                "Firebase ID token was null"
            }
        }.mapFailure { throwable ->
            RuntimeException(
                "AuthService.readFirebaseTokenResult failed to read Firebase ID token with forceRefresh=$forceRefresh",
                throwable,
            )
        }
    }

    /**
     * Meaningful function boundary:
     *
     * Request creation may fail because of invalid input, missing data,
     * or future request-building validation.
     */
    private fun buildAuthRequestResult(
        firebaseToken: String,
    ): Result<AuthRequest> {
        return runCatching {
            require(firebaseToken.isNotBlank()) {
                "Firebase token was blank"
            }

            AuthRequest(
                firebaseToken = firebaseToken,
            )
        }.mapFailure { throwable ->
            RuntimeException(
                "AuthService.buildAuthRequestResult failed to build auth request from Firebase token",
                throwable,
            )
        }
    }

    /**
     * Meaningful function boundary:
     *
     * Remote authorization call can fail because of network errors,
     * backend errors, timeouts, or SDK/client failures.
     */
    private suspend fun postAuthorizeRequestResult(
        request: AuthRequest,
    ): Result<String> {
        return suspendRunCatching {
            httpClient.post(
                path = "/auth",
                body = json.encodeToString(request),
            )
        }.mapFailure { throwable ->
            RuntimeException(
                "AuthService.postAuthorizeRequestResult failed to send auth request to /auth",
                throwable,
            )
        }
    }

    /**
     * Meaningful function boundary:
     *
     * Response parsing can fail independently from the HTTP request itself.
     */
    private fun parseAuthResponseResult(
        responseText: String,
    ): Result<AuthResponse> {
        return Result.success(responseText)
            .mapCatching { text ->
                json.decodeFromString<AuthResponse>(text)
            }
            .mapFailure { throwable ->
                RuntimeException(
                    "AuthService.parseAuthResponseResult failed to parse AuthResponse from /auth response",
                    throwable,
                )
            }
    }
}

/**
 * Kotlin has map/mapCatching/recover/fold, but no standard mapFailure.
 *
 * This tiny extension fills that ergonomic gap without introducing
 * a custom Result type, Either type, or failure framework.
 */
inline fun <T> Result<T>.mapFailure(
    transform: (Throwable) -> Throwable,
): Result<T> {
    return fold(
        onSuccess = { value ->
            Result.success(value)
        },
        onFailure = { throwable ->
            Result.failure(transform(throwable))
        },
    )
}

/**
 * Suspend-safe version of runCatching.
 *
 * Plain runCatching catches Throwable, including CancellationException.
 * In coroutine code, cancellation should normally keep propagating.
 */
suspend inline fun <T> suspendRunCatching(
    crossinline block: suspend () -> T,
): Result<T> {
    return try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
}