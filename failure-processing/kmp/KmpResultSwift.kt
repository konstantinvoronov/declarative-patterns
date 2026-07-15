/*
# KmpResultSwift: Swift-Safe Consumption of KmpResult

## Purpose

`KmpResult<T>` is the main shared result type used by KMP code.

Kotlin can consume `KmpResult<T>` directly because Kotlin understands sealed classes, covariance, and `Nothing`.

Swift should not consume `KmpResult<T>` by directly casting to `KmpResult.Success` or `KmpResult.Failure`, because Kotlin/Native exports `KmpResult.Failure` as a result branch based on `KotlinNothing`. This can make Swift subclass casts fragile.

For Swift-facing code, use the global Swift-safe projection:

```kotlin
KmpResult<T>.asKmpResultSwift(): KmpResultSwift<T>
```

This keeps one global KMP solution instead of creating model-specific bridge helpers such as `ShoppingCartResultBridgeKt.shoppingCartFailureOrNull(...)`.

---

## KMP model

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

---

## Why this exists

This KMP result model is correct:

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

`Failure` is `KmpResult<Nothing>` because a failure has no success value.

In Kotlin this works naturally:

```kotlin
val result: KmpResult<ShoppingCart> = KmpResult.Failure(failure)
```

because:

```text
Nothing is a subtype of every type.
KmpResult is covariant: KmpResult<out T>.
Therefore KmpResult<Nothing> can be used as KmpResult<ShoppingCart>.
```

Swift does not understand this sealed-class structure in the same way. Swift sees exported Kotlin/Native classes and generic Obj-C/Swift bridge types.

So this Swift pattern is fragile:

```swift
switch result {
case let success as KmpResultSuccess<ShoppingCart>:
    ...
case let failure as KmpResultFailure:
    ...
default:
    ...
}
```

Instead, Swift should consume the projected shape:

```swift
let resultSwift = result.asKmpResultSwift()
```

and switch on a simple exported enum:

```swift
switch resultSwift.status {
case .success:
    ...
case .failure:
    ...
default:
    ...
}
```

---

## Good Swift pattern

```swift
let result = try await WrapGrpcShoppingCart.shared.getShoppingCart(
    requestId: requestId,
    store: store,
    pageSize: Int32(pageSize),
    pageNum: Int32(pageNum),
    sort: sort,
    filter: filterStr,
    offerId: request.hasOfferID ? request.offerID : nil
)

let resultSwift = result.asKmpResultSwift()

switch resultSwift.status {
case .success:
    guard let shoppingCart = resultSwift.value as? ShoppingCart else {
        throw GRPCStatus(
            code: .internal,
            message: "KmpResult success state has no ShoppingCart value"
        )
    }

    return GrpcModelBuilders.buildShoppingCart(shoppingCart: shoppingCart)

case .failure:
    guard let failure = resultSwift.failure else {
        throw GRPCStatus(
            code: .internal,
            message: "KmpResult failure state has no KmpFailure"
        )
    }

    let boundaryFailure = failure.wrapIos(
        message: "GrpcShoppingCartServiceImpl.getShoppingCart failed to serve gRPC shopping cart request",
        code: nil,
        retryable: failure.retryable,
        source: "GrpcShoppingCartServiceImpl.getShoppingCart",
        nativeType: nil,
        debugInfo: nil,
        metadata: [:]
    )

    logger.log(msg: "Error in getShoppingCart: \(boundaryFailure.debugString())")

    throw GRPCStatus(
        code: .internal,
        message: boundaryFailure.message
    )

default:
    throw GRPCStatus(
        code: .internal,
        message: "Unsupported KmpResultSwiftStatus"
    )
}
```

---

## Bad Swift pattern: direct sealed-subclass casting

Do not consume `KmpResult<T>` like this in Swift:

```swift
switch result {
case let success as KmpResultSuccess<ShoppingCart>:
    return GrpcModelBuilders.buildShoppingCart(shoppingCart: success.value)

case let failureResult as KmpResultFailure:
    let failure = failureResult.failure
    throw GRPCStatus(code: .internal, message: failure.message)

default:
    throw GRPCStatus(code: .internal, message: "Unsupported result type")
}
```

Why this is bad:

```text
Swift is forced to understand Kotlin sealed subclasses.
KmpResult.Failure is exported through Kotlin/Native as a branch based on KotlinNothing.
The API result may be KmpResult<ShoppingCart>, while the failure branch is structurally KmpResult<Nothing>.
This makes Swift runtime casts fragile.
```

---

## Bad Swift pattern: model-specific bridge helpers

Avoid creating one bridge per model:

```swift
let failure = ShoppingCartResultBridgeKt.shoppingCartFailureOrNull(result: result)
let cart = ShoppingCartResultBridgeKt.shoppingCartSuccessOrNull(result: result)
```

Why this is bad:

```text
It creates one bridge helper per result type.
It spreads result-handling rules across feature modules.
It makes the global failure contract harder to maintain.
It does not scale across many KmpResult<T> APIs.
```

Use the global projection instead:

```swift
let resultSwift = result.asKmpResultSwift()
```

---

## Bad Swift pattern: flattening failure into a string

Do not do this:

```kotlin
data class KmpResultSwift<out T>(
    val status: KmpResultSwiftStatus,
    val value: T? = null,
    val failureMessage: String? = null,
)
```

Why this is bad:

```text
It destroys the KmpFailure chain.
It loses code, retryable, source, origin, nativeType, debugInfo, metadata, and cause.
It prevents Swift from wrapping the original failure at the boundary.
```

The Swift projection must keep the real `KmpFailure`:

```kotlin
data class KmpResultSwift<out T>(
    val status: KmpResultSwiftStatus,
    val value: T? = null,
    val failure: KmpFailure? = null,
)
```

---

## Good pattern: preserve failure chain

`asKmpResultSwift()` must not create a new failure.

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

The bad version destroys the original cause chain.

---

## Boundary wrapping from Swift

Swift may wrap the original `KmpFailure` at a consuming boundary:

```swift
let boundaryFailure = failure.wrapIos(
    message: "GrpcShoppingCartServiceImpl.getShoppingCart failed to serve gRPC shopping cart request",
    code: nil,
    retryable: failure.retryable,
    source: "GrpcShoppingCartServiceImpl.getShoppingCart",
    nativeType: nil,
    debugInfo: nil,
    metadata: [:]
)
```

This produces a proper chain:

```text
GrpcShoppingCartServiceImpl.getShoppingCart failed to serve gRPC shopping cart request
caused by: ShoppingCartRepository failed to load cart
caused by: HTTP request failed
caused by: 401 Unauthorized
```

---

## Good Kotlin pattern

Kotlin code may continue using `fold` or `when`.

Preferred project-wide style:

```kotlin
return result.fold(
    onSuccess = { value ->
        KmpResult.Success(value)
    },
    onFailure = { failure ->
        KmpResult.Failure(
            failure.wrap(
                message = "Use case failed to process shopping cart",
                source = "ShoppingCartUseCase.getShoppingCart",
            )
        )
    },
)
```

Kotlin may also use sealed `when` expressions internally:

```kotlin
return when (result) {
    is KmpResult.Success -> process(result.value)
    is KmpResult.Failure -> KmpResult.Failure(
        result.failure.wrap(
            message = "Failed to process result",
            source = "ShoppingCartUseCase.process",
        )
    )
}
```

---

## Platform rule

### Kotlin / Android / JVM

Kotlin platforms may consume `KmpResult<T>` directly using `fold` or sealed `when`.

Kotlin understands:

```text
KmpResult.Failure = KmpResult<Nothing>
```

### Swift / iOS / macOS

Swift platforms should not directly cast to:

```swift
KmpResultSuccess<T>
KmpResultFailure
```

Swift should consume:

```swift
result.asKmpResultSwift()
```

and switch on:

```swift
resultSwift.status
```

---

## Final rule

```text
KmpResult<T> is the shared result contract.

Kotlin consumes KmpResult<T> structurally through fold or sealed when.

Swift consumes KmpResult<T> through the global Swift-safe projection:
KmpResult<T>.asKmpResultSwift(): KmpResultSwift<T>.

Swift must not rely on direct sealed-subclass casts to KmpResultSuccess or KmpResultFailure.
KmpResultSwift must preserve the original KmpFailure object so the failure chain remains intact.
```

 */
@Serializable
data class KmpResultSwift<out T>(
    val status: KmpResultSwiftStatus,
    val value: T? = null,
    val failure: KmpFailure? = null,
)

@Serializable
enum class KmpResultSwiftStatus {
    SUCCESS,
    FAILURE,
}

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