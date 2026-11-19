package io.github.vladbokov.wallet.domain.usecase

import io.github.vladbokov.wallet.core.wallet.Account
import io.github.vladbokov.wallet.core.wallet.FeeQuote
import io.github.vladbokov.wallet.core.wallet.Mnemonic
import io.github.vladbokov.wallet.core.wallet.SignedTransaction
import io.github.vladbokov.wallet.core.wallet.UnsignedTransaction
import io.github.vladbokov.wallet.domain.model.DataError
import io.github.vladbokov.wallet.domain.model.SendResult
import io.github.vladbokov.wallet.domain.model.Token
import io.github.vladbokov.wallet.domain.model.TransferContext
import io.github.vladbokov.wallet.domain.model.TransferDraft
import io.github.vladbokov.wallet.domain.model.TxStatus
import io.github.vladbokov.wallet.domain.repository.WalletRepository
import io.github.vladbokov.wallet.domain.repository.WalletSecrets
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.math.BigInteger
import javax.crypto.Cipher
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SendTransferUseCaseTest {
    // The well-known Hardhat/Anvil test phrase; its first account is 0xf39F...2266.
    private val phrase = "test test test test test test test test test test test junk"
    private val walletAddress = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266"
    private val recipient = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8"

    private val draft =
        TransferDraft(
            UnsignedTransaction(
                chainId = 11155111,
                nonce = BigInteger.ZERO,
                to = recipient,
                value = BigInteger.ONE,
                fee =
                    FeeQuote(
                        gasLimit = BigInteger.valueOf(21_000),
                        maxPriorityFeePerGas = BigInteger.valueOf(1_000_000_000),
                        maxFeePerGas = BigInteger.valueOf(30_000_000_000),
                    ),
            ),
        )

    // The fake ignores the cipher; an uninitialised one is enough to pass it along.
    private val cipher = Cipher.getInstance("AES/GCM/NoPadding")

    private fun expectedHash() = Account.fromMnemonic(Mnemonic.parse(phrase)!!).sign(draft.transaction).hash

    @Test
    fun `signs with the stored phrase, broadcasts and returns the transaction hash`() =
        runTest {
            val repository = FakeRepository()

            val result = SendTransferUseCase(repository, FakeSecrets(phrase, walletAddress))(draft, cipher)

            assertEquals(SendResult.Sent(expectedHash()), result)
            assertEquals(expectedHash(), repository.broadcast.single().hash)
        }

    @Test
    fun `the decrypted phrase is zeroed after a successful send`() =
        runTest {
            val secrets = FakeSecrets(phrase, walletAddress)

            SendTransferUseCase(FakeRepository(), secrets)(draft, cipher)

            assertZeroed(secrets.revealed.single())
        }

    @Test
    fun `the phrase is zeroed and nothing is broadcast when it does not match the wallet address`() =
        runTest {
            val secrets = FakeSecrets(phrase, "0x" + "11".repeat(20))
            val repository = FakeRepository()

            assertFailsWith<IllegalStateException> { SendTransferUseCase(repository, secrets)(draft, cipher) }

            assertZeroed(secrets.revealed.single())
            assertTrue(repository.broadcast.isEmpty())
        }

    @Test
    fun `a missing wallet address is refused like a mismatching one`() =
        runTest {
            val secrets = FakeSecrets(phrase, address = null)
            val repository = FakeRepository()

            assertFailsWith<IllegalStateException> { SendTransferUseCase(repository, secrets)(draft, cipher) }

            assertZeroed(secrets.revealed.single())
            assertTrue(repository.broadcast.isEmpty())
        }

    @Test
    fun `a damaged phrase is zeroed, refused and never leaked into the error`() =
        runTest {
            val damaged = "not a recovery phrase"
            val secrets = FakeSecrets(damaged, walletAddress)
            val repository = FakeRepository()

            val e = assertFailsWith<IllegalStateException> { SendTransferUseCase(repository, secrets)(draft, cipher) }

            assertZeroed(secrets.revealed.single())
            assertTrue(repository.broadcast.isEmpty())
            assertNull(e.message?.takeIf { damaged in it })
        }

    @Test
    fun `a lost connection during broadcast still returns the hash to track`() =
        runTest {
            val repository = FakeRepository(error = DataError.Network(IOException("timeout")))

            val result = SendTransferUseCase(repository, FakeSecrets(phrase, walletAddress))(draft, cipher)

            assertEquals(SendResult.Unconfirmed(expectedHash()), result)
        }

    @Test
    fun `a rejection by the node is thrown and the phrase is still zeroed`() =
        runTest {
            val secrets = FakeSecrets(phrase, walletAddress)
            val repository = FakeRepository(error = DataError.NonceTooLow())

            assertFailsWith<DataError.NonceTooLow> { SendTransferUseCase(repository, secrets)(draft, cipher) }

            assertZeroed(secrets.revealed.single())
        }

    @Test
    fun `a vault failure is passed through and nothing is broadcast`() =
        runTest {
            val secrets = FakeSecrets(phrase, walletAddress, failure = IllegalArgumentException("vault"))
            val repository = FakeRepository()

            assertFailsWith<IllegalArgumentException> { SendTransferUseCase(repository, secrets)(draft, cipher) }

            assertTrue(repository.broadcast.isEmpty())
        }

    private fun assertZeroed(bytes: ByteArray) = assertContentEquals(ByteArray(bytes.size), bytes)

    /** Hands out a fresh copy per call and keeps it, so a test can look at what the use case did to it. */
    private class FakeSecrets(
        phrase: String,
        override val address: String?,
        private val failure: RuntimeException? = null,
    ) : WalletSecrets {
        private val bytes = phrase.toByteArray(Charsets.UTF_8)
        val revealed = mutableListOf<ByteArray>()

        override fun reveal(authenticatedCipher: Cipher): ByteArray {
            failure?.let { throw it }
            return bytes.copyOf().also { revealed += it }
        }
    }

    private class FakeRepository(
        private val error: DataError? = null,
    ) : WalletRepository {
        val broadcast = mutableListOf<SignedTransaction>()

        override suspend fun broadcast(transaction: SignedTransaction) {
            broadcast += transaction
            error?.let { throw it }
        }

        override suspend fun balance(address: String): BigInteger = BigInteger.ZERO

        override suspend fun tokenBalance(
            token: Token,
            owner: String,
        ): BigInteger = BigInteger.ZERO

        override suspend fun transferContext(
            from: String,
            to: String,
            value: BigInteger,
        ): TransferContext = error("not used")

        override fun observeStatus(transactionHash: String): Flow<TxStatus> = emptyFlow()
    }
}
