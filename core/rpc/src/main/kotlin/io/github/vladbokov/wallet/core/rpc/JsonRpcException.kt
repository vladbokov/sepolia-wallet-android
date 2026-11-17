package io.github.vladbokov.wallet.core.rpc

import kotlinx.serialization.json.JsonElement

/**
 * Failure of an RPC call, split by who is to blame so callers can react differently:
 * retry [Http] 429/5xx, show a [Node] error to the user, treat [Malformed] as a provider bug.
 *
 * Deliberately not an [java.io.IOException]: transport failures (no connection, timeout) keep propagating
 * as plain `IOException`, so a retry-on-IOException policy never repeats a call the node already rejected.
 */
sealed class JsonRpcException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** The node processed the call and answered with an error (e.g. "insufficient funds", "nonce too low"). */
    class Node(val code: Int, message: String, val data: JsonElement? = null) : JsonRpcException(message)

    /** The endpoint answered with a non-2xx status (rate limit, bad API key, gateway error). */
    class Http(val status: Int, val error: JsonRpcError?, cause: Throwable? = null) :
        JsonRpcException(error?.let { "HTTP $status: ${it.message}" } ?: "HTTP $status from RPC endpoint", cause)

    /** The response arrived but is not a valid answer to our request. */
    class Malformed(message: String, cause: Throwable? = null) : JsonRpcException(message, cause)
}
