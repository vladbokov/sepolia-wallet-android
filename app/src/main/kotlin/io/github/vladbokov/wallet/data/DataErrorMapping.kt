package io.github.vladbokov.wallet.data

import io.github.vladbokov.wallet.core.rpc.JsonRpcException
import io.github.vladbokov.wallet.domain.model.DataError
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException

/**
 * Runs an RPC call and translates the library's failures into [DataError], so nothing from `core:rpc` or OkHttp
 * leaks into the domain. `CancellationException` and programming errors (`IllegalArgumentException` for a malformed
 * argument) pass through untouched.
 */
internal suspend inline fun <T> mapErrors(block: () -> T): T =
    try {
        block()
    } catch (e: JsonRpcException) {
        throw e.toDataError()
    } catch (e: IOException) {
        // OkHttp reports a cancelled call as an IOException; a cancelled coroutine must stay cancelled, not become Network.
        currentCoroutineContext().ensureActive()
        throw DataError.Network(e)
    }

internal fun JsonRpcException.toDataError(): DataError =
    when (this) {
        is JsonRpcException.Malformed -> DataError.MalformedResponse(this)

        // Rate limiting and gateway failures are temporary: the same request may succeed a moment later.
        is JsonRpcException.Http ->
            if (status == 429 || status in 500..599) {
                DataError.Network(this)
            } else {
                DataError.Rpc(error?.code ?: status, error?.message)
            }

        is JsonRpcException.Node -> {
            // Clients word these differently ("nonce too low", "insufficient funds for gas * price + value",
            // "InsufficientFunds"), so only letters are compared. The list grows as new clients are met; an
            // unknown wording degrades to a generic Rpc error.
            val text = message.lettersOnly()
            when {
                "noncetoolow" in text -> DataError.NonceTooLow()
                "insufficientfunds" in text -> DataError.RejectedInsufficientFunds()
                else -> DataError.Rpc(code, message)
            }
        }
    }

/** The node already holds this very transaction ("already known", "AlreadyKnown"): same hash, nothing to do. */
internal fun DataError.Rpc.isAlreadyKnown(): Boolean = "alreadyknown" in rpcMessage.lettersOnly()

private fun String?.lettersOnly(): String = orEmpty().filter(Char::isLetter).lowercase()
