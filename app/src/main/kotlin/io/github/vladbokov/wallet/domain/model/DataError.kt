package io.github.vladbokov.wallet.domain.model

/**
 * Why a repository call failed. The repository is the error boundary: it catches transport exceptions
 * (`IOException`, HTTP and JSON-RPC errors) and rethrows them as one of these, so no library type leaks upward.
 */
sealed class DataError(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** No connection, timeout or an unreachable endpoint. Worth retrying. */
    class Network(
        cause: Throwable,
    ) : DataError("Network error", cause)

    /** The node answered with a JSON-RPC error that has no more specific case below. */
    class Rpc(
        val code: Int,
        val rpcMessage: String?,
    ) : DataError("RPC error $code: $rpcMessage")

    /** The transaction nonce is behind the account's on-chain nonce: another transaction got in first. */
    class NonceTooLow : DataError("Nonce too low")

    /** The node refused the transaction because the account cannot pay for value plus gas. */
    class RejectedInsufficientFunds : DataError("Node rejected the transaction: insufficient funds")

    /** The response could not be parsed or had an unexpected shape. */
    class MalformedResponse(
        cause: Throwable? = null,
    ) : DataError("Malformed response", cause)
}
