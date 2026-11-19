package io.github.vladbokov.wallet.domain.usecase

import io.github.vladbokov.wallet.core.wallet.SignedTransaction
import io.github.vladbokov.wallet.domain.model.ChainConfig
import io.github.vladbokov.wallet.domain.model.DataError
import io.github.vladbokov.wallet.domain.model.DraftResult
import io.github.vladbokov.wallet.domain.model.Token
import io.github.vladbokov.wallet.domain.model.TransferContext
import io.github.vladbokov.wallet.domain.model.TransferError
import io.github.vladbokov.wallet.domain.model.TxStatus
import io.github.vladbokov.wallet.domain.repository.WalletRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class PrepareTransferUseCaseTest {
    private val from = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266"
    private val to = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8"
    private val oneEth = BigInteger("1000000000000000000")
    private val gwei = BigInteger.valueOf(1_000_000_000)

    private val chain =
        ChainConfig(
            chainId = 11155111,
            name = "Sepolia",
            symbol = "ETH",
            explorerTxUrl = "http://localhost/tx/",
        )

    private fun context(
        chainId: Long = chain.chainId,
        balance: BigInteger = oneEth,
        estimatedGas: BigInteger = BigInteger.valueOf(21_000),
        nonce: BigInteger = BigInteger.valueOf(3),
    ) = TransferContext(
        chainId = chainId,
        balance = balance,
        nonce = nonce,
        estimatedGas = estimatedGas,
        baseFee = gwei * BigInteger.TEN,
        priorityFee = gwei,
    )

    private fun repository(context: TransferContext) = FakeRepository { context }

    private fun useCase(repository: FakeRepository) = PrepareTransferUseCase(repository, chain)

    private fun failure(result: DraftResult) = assertIs<DraftResult.Failure>(result).error

    @Test
    fun `builds an EIP-1559 draft from chain state`() =
        runTest {
            val result = useCase(repository(context()))(from, " $to ", "0.5")

            val draft = assertIs<DraftResult.Success>(result).draft
            val transaction = draft.transaction
            assertEquals(chain.chainId, transaction.chainId)
            assertEquals(to, transaction.to)
            assertEquals(BigInteger.valueOf(3), transaction.nonce)
            assertEquals(BigInteger("500000000000000000"), transaction.value)
            assertEquals(BigInteger.valueOf(25_200), transaction.fee.gasLimit)
            assertEquals(gwei * BigInteger.valueOf(21), transaction.fee.maxFeePerGas)
            assertEquals(gwei, transaction.fee.maxPriorityFeePerGas)
            assertEquals(transaction.fee.gasLimit * transaction.fee.maxFeePerGas, draft.maxFee)
        }

    @Test
    fun `rejects an address with a broken checksum`() =
        runTest {
            val result = useCase(repository(context()))(from, "0x70997970C51812dc3A010C7d01b50e0d17dc79c8", "0.5")

            assertEquals(TransferError.InvalidAddress, failure(result))
        }

    @Test
    fun `rejects zero and malformed amounts`() =
        runTest {
            for (amount in listOf("0", "", "1.2.3", "-1", "1,5", "0.0000000000000000001")) {
                val result = useCase(repository(context()))(from, to, amount)

                assertEquals(TransferError.InvalidAmount, failure(result), "amount '$amount'")
            }
        }

    @Test
    fun `invalid input never reaches the network`() =
        runTest {
            val repository = repository(context())

            useCase(repository)(from, "not an address", "1")
            useCase(repository)(from, to, "0")

            assertEquals(0, repository.contextCalls)
        }

    @Test
    fun `refuses to build a transaction when the endpoint is on another chain`() =
        runTest {
            val result = useCase(repository(context(chainId = 1)))(from, to, "0.5")

            assertEquals(
                TransferError.WrongNetwork(expectedChainId = chain.chainId, actualChainId = 1),
                failure(result),
            )
        }

    @Test
    fun `balance must cover amount plus the maximum fee, to the last wei`() =
        runTest {
            // maxFee = 25 200 gas * 21 gwei = 0.0005292 ETH
            val exactlyEnough = useCase(repository(context()))(from, to, "0.9994708")
            val oneWeiShort = useCase(repository(context()))(from, to, "0.999470800000000001")

            assertIs<DraftResult.Success>(exactlyEnough)
            assertEquals(TransferError.InsufficientFunds, failure(oneWeiShort))
        }

    @Test
    fun `an amount above the balance is insufficient funds, not a crash`() =
        runTest {
            val result = useCase(repository(context()))(from, to, "1" + "0".repeat(60))

            assertEquals(TransferError.InsufficientFunds, failure(result))
        }

    @Test
    fun `implausible gas from the node is a malformed response`() =
        runTest {
            val absurdGas = BigInteger.valueOf(1_000_000_000)

            assertFailsWith<DataError.MalformedResponse> {
                useCase(repository(context(estimatedGas = absurdGas)))(from, to, "0.5")
            }
        }

    @Test
    fun `an implausible nonce from the node is a malformed response`() =
        runTest {
            for (nonce in listOf(BigInteger.ONE.shiftLeft(64), BigInteger.valueOf(-1))) {
                assertFailsWith<DataError.MalformedResponse>("nonce $nonce") {
                    useCase(repository(context(nonce = nonce)))(from, to, "0.5")
                }
            }
        }

    @Test
    fun `an implausible balance from the node is a malformed response`() =
        runTest {
            for (balance in listOf(BigInteger.ONE.shiftLeft(256), BigInteger.valueOf(-1))) {
                assertFailsWith<DataError.MalformedResponse>("balance $balance") {
                    useCase(repository(context(balance = balance)))(from, to, "0.5")
                }
            }
        }

    @Test
    fun `the largest nonce and balance the protocol allows are accepted`() =
        runTest {
            val maxNonce = BigInteger.ONE.shiftLeft(64) - BigInteger.ONE
            val maxBalance = BigInteger.ONE.shiftLeft(256) - BigInteger.ONE

            val result = useCase(repository(context(balance = maxBalance, nonce = maxNonce)))(from, to, "0.5")

            assertIs<DraftResult.Success>(result)
        }

    @Test
    fun `infrastructure failures pass through unchanged`() =
        runTest {
            val failing = FakeRepository { throw DataError.Network(java.io.IOException("offline")) }

            assertFailsWith<DataError.Network> { useCase(failing)(from, to, "0.5") }
        }

    private class FakeRepository(
        private val onContext: () -> TransferContext,
    ) : WalletRepository {
        var contextCalls = 0
            private set

        override suspend fun balance(address: String): BigInteger = BigInteger.ZERO

        override suspend fun tokenBalance(
            token: Token,
            owner: String,
        ): BigInteger = BigInteger.ZERO

        override suspend fun transferContext(
            from: String,
            to: String,
            value: BigInteger,
        ): TransferContext {
            contextCalls++
            return onContext()
        }

        override suspend fun broadcast(transaction: SignedTransaction) = Unit

        override fun observeStatus(transactionHash: String): Flow<TxStatus> = emptyFlow()
    }
}
