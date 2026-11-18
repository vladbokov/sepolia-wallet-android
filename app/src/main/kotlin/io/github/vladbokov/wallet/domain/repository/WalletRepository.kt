package io.github.vladbokov.wallet.domain.repository

import io.github.vladbokov.wallet.core.wallet.SignedTransaction
import io.github.vladbokov.wallet.domain.model.DataError
import io.github.vladbokov.wallet.domain.model.Token
import io.github.vladbokov.wallet.domain.model.TransferContext
import io.github.vladbokov.wallet.domain.model.TransferError
import io.github.vladbokov.wallet.domain.model.TxStatus
import kotlinx.coroutines.flow.Flow
import java.math.BigInteger

/**
 * The domain's view of the blockchain. Implemented over JSON-RPC in the data layer.
 *
 * Error contract: every failure is thrown as a [DataError]. Nothing else is thrown except `CancellationException`,
 * which must propagate untouched. The same holds for [observeStatus]: the flow fails with a [DataError] rather than
 * emitting it as a status. Callers (use cases) catch [DataError] and turn it into a domain result.
 * Validating user input (address, amount, balance vs fee, chain id) is not the repository's job; that yields a
 * [TransferError].
 */
interface WalletRepository {
    suspend fun balance(address: String): BigInteger

    suspend fun tokenBalance(
        token: Token,
        owner: String,
    ): BigInteger

    /** Reads the chain state needed to build a native ETH transfer; gas is estimated for a plain transfer. */
    suspend fun transferContext(
        from: String,
        to: String,
        value: BigInteger,
    ): TransferContext

    /**
     * Sends the signed transaction. Its hash is `transaction.hash`, known before the node answers, so nothing is
     * returned. If the node reports a different hash, the call fails with [DataError.MalformedResponse].
     */
    suspend fun broadcast(transaction: SignedTransaction)

    /**
     * Tracks a transaction until it is settled. The flow emits [TxStatus.Pending] first and completes right after
     * [TxStatus.Confirmed], [TxStatus.Failed] or [TxStatus.TimedOut]. Being unable to learn the status is not a
     * status: it fails the flow with a [DataError].
     */
    fun observeStatus(transactionHash: String): Flow<TxStatus>
}
