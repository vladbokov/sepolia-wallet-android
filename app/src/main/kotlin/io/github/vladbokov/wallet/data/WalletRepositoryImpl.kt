package io.github.vladbokov.wallet.data

import io.github.vladbokov.wallet.core.rpc.EthereumRpcClient
import io.github.vladbokov.wallet.core.rpc.hexToBigInteger
import io.github.vladbokov.wallet.core.wallet.Erc20
import io.github.vladbokov.wallet.core.wallet.SignedTransaction
import io.github.vladbokov.wallet.domain.model.DataError
import io.github.vladbokov.wallet.domain.model.Token
import io.github.vladbokov.wallet.domain.model.TransferContext
import io.github.vladbokov.wallet.domain.model.TxStatus
import io.github.vladbokov.wallet.domain.repository.WalletRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.math.BigInteger

/** [WalletRepository] over JSON-RPC. Every library failure leaves it as a [DataError]. */
internal class WalletRepositoryImpl(
    private val rpc: EthereumRpcClient,
    private val pollIntervalMillis: Long = 3_000,
    private val maxPolls: Int = 60,
) : WalletRepository {
    override suspend fun balance(address: String): BigInteger = mapErrors { rpc.getBalance(address) }

    override suspend fun tokenBalance(
        token: Token,
        owner: String,
    ): BigInteger {
        val result = mapErrors { rpc.call(token.address, Erc20.balanceOfCallData(owner)) }
        // An address without contract code answers `0x`, which is not a balance.
        return try {
            result.hexToBigInteger()
        } catch (e: IllegalArgumentException) {
            throw DataError.MalformedResponse(e)
        }
    }

    /** Six independent reads issued concurrently; any failure cancels the rest (structured concurrency). */
    override suspend fun transferContext(
        from: String,
        to: String,
        value: BigInteger,
    ): TransferContext =
        mapErrors {
            coroutineScope {
                val chainId = async { rpc.chainId() }
                val balance = async { rpc.getBalance(from) }
                val nonce = async { rpc.getTransactionCount(from) }
                val gas = async { rpc.estimateGas(from, to, value) }
                val baseFee = async { rpc.latestBaseFee() }
                val priorityFee = async { rpc.maxPriorityFeePerGas() }
                TransferContext(
                    chainId = chainId.await(),
                    balance = balance.await(),
                    nonce = nonce.await(),
                    estimatedGas = gas.await(),
                    baseFee = baseFee.await(),
                    priorityFee = priorityFee.await(),
                )
            }
        }

    override suspend fun broadcast(transaction: SignedTransaction) {
        val nodeHash = mapErrors { rpc.sendRawTransaction(transaction.rawHex) }
        // The hash is fixed by the signed bytes. A different answer means the node did not take this transaction.
        if (!nodeHash.equals(transaction.hash, ignoreCase = true)) throw DataError.MalformedResponse()
    }

    /**
     * Cold flow: polls for the receipt until the transaction is mined or the attempts run out.
     * A temporary failure ([DataError.Network]) only skips that poll. If not a single poll got an answer, the status
     * is unknown rather than "not mined yet", so the last network error is thrown instead of [TxStatus.TimedOut].
     */
    override fun observeStatus(transactionHash: String): Flow<TxStatus> =
        flow {
            emit(TxStatus.Pending)
            var lastNetworkError: DataError.Network? = null
            var answered = false
            repeat(maxPolls) {
                delay(pollIntervalMillis)
                val receipt =
                    try {
                        mapErrors { rpc.getTransactionReceipt(transactionHash) }.also { answered = true }
                    } catch (e: DataError.Network) {
                        lastNetworkError = e
                        null
                    }
                if (receipt != null) {
                    emit(if (receipt.success) TxStatus.Confirmed(receipt.blockNumber) else TxStatus.Failed)
                    return@flow
                }
            }
            lastNetworkError?.takeIf { !answered }?.let { throw it }
            emit(TxStatus.TimedOut)
        }
}
