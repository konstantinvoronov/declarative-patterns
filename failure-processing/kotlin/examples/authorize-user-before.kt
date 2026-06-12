class AuthService(
    private val firebaseAuth: FirebaseAuth,
    private val httpClient: HttpClient,
    private val json: Json,
    private val logger: Logger,
) {

    /**
     * BEFORE:
     *
     * This function may work in the happy path, but the failure path is weak.
     *
     * Problems:
     *
     * 1. Failure is hidden as null.
     *    The caller receives null, but cannot know what actually failed.
     *
     * 2. Missing Firebase user, missing token, HTTP failure, and JSON parsing failure
     *    all become the same result: null.
     *
     * 3. The original exception is lost in logs.
     *    logger.error("Failed to authorize user") does not preserve the cause chain.
     *
     * 4. The error message is too vague.
     *    It does not say which function failed or what operation failed.
     *
     * 5. There is no internal Result<AuthResponse> function.
     *    The operation contract is not explicit.
     *
     * 6. The caller cannot inspect, map, recover, or log the failure properly.
     *
     * 7. If this function grows, every new failure will likely be collapsed
     *    into the same null result.
     */
    suspend fun authorizeUser(): AuthResponse? {
        return try {
            val user = firebaseAuth.currentUser

            if (user == null) {
                // Problem:
                // This is a real authorization failure,
                // but the reason is lost after returning null.
                logger.error("Failed to authorize user")
                return null
            }

            val firebaseToken = user.getIdToken(false).token

            if (firebaseToken == null) {
                // Problem:
                // Missing token is different from missing user,
                // but both cases are logged and returned in the same way.
                logger.error("Failed to authorize user")
                return null
            }

            val request = AuthRequest(
                firebaseToken = firebaseToken,
            )

            val responseText = httpClient.post(
                path = "/auth",
                body = json.encodeToString(request),
            )

            json.decodeFromString<AuthResponse>(responseText)
        } catch (e: Exception) {
            // Problem:
            // The original exception exists here, but it is not passed to the logger
            // and not returned to the caller.
            //
            // Missing:
            //
            // RuntimeException(
            //     "AuthService.authorizeUserResult failed to authorize current Firebase user",
            //     e,
            // )
            //
            // Because of this, the full cause chain is lost.
            logger.error("Failed to authorize user")
            null
        }
    }
}