package io.github.vladbokov.wallet.domain.model

import io.github.vladbokov.wallet.core.wallet.UnsignedTransaction
import java.math.BigInteger

/** Everything the node has to tell us before a transfer can be built. */
data class TransferContext(
    val chainId: Long,
    val balance: BigInteger,
    val nonce: BigInteger,
    val estimatedGas: BigInteger,
    val baseFee: BigInteger,
    val priorityFee: BigInteger,
)

/**
 * A fully specified native ETH transfer shown to the user for confirmation before signing.
 *
 * For ETH, `transaction.to` and `transaction.value` are exactly what the user sees and signs. An ERC-20 transfer
 * would not fit: the transaction goes to the token contract with zero value, and the real recipient and amount
 * sit in the calldata. Supporting tokens needs explicit recipient, amount and token fields here, checked against
 * the calldata when the draft is built, so the confirmation can never differ from what is signed.
 */
data class TransferDraft(
    val transaction: UnsignedTransaction,
) {
    init {
        // Calldata would make `to`/`value` differ from what is really executed, so the confirmation would lie.
        require(transaction.data == "0x") { "Only plain ETH transfers can be drafted: calldata must be empty" }
    }

    val maxFee: BigInteger get() = transaction.fee.maxTotalFee
}

/**
 * Validation failures of a transfer, found by the domain before anything is signed. These are expected outcomes,
 * not exceptions: they are returned to the caller, which maps them to user-facing text.
 *
 * Network and RPC failures are a different matter and surface from the repository as [DataError].
 */
sealed interface TransferError {
    data object InvalidAddress : TransferError

    /** Amount is not positive or has more than 18 decimals. */
    data object InvalidAmount : TransferError

    /** The node reported a chain id other than the one the wallet is configured for. */
    data class WrongNetwork(
        val expectedChainId: Long,
        val actualChainId: Long,
    ) : TransferError

    /** Balance does not cover amount plus the maximum network fee. */
    data object InsufficientFunds : TransferError
}

/**
 * Outcome of building a [TransferDraft]. `kotlin.Result` cannot carry [TransferError] because it is not a
 * `Throwable`, so validation failures are values here. Infrastructure failures are not part of it: they stay
 * [DataError] exceptions and are handled separately.
 */
sealed interface DraftResult {
    data class Success(
        val draft: TransferDraft,
    ) : DraftResult

    data class Failure(
        val error: TransferError,
    ) : DraftResult
}

/** Outcome of sending a signed transfer. The [hash] is known as soon as the transaction is signed. */
sealed interface SendResult {
    val hash: String

    /** The node accepted the transaction. */
    data class Sent(
        override val hash: String,
    ) : SendResult

    /**
     * The connection failed during the broadcast: the node may have taken the transaction, so it must not be sent
     * again blindly. Track it by [hash] to find out.
     */
    data class Unconfirmed(
        override val hash: String,
    ) : SendResult
}
