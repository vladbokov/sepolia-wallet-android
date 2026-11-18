package io.github.vladbokov.wallet.core.wallet

import org.web3j.crypto.SignedRawTransaction
import org.web3j.crypto.TransactionDecoder
import org.web3j.crypto.transaction.type.Transaction1559
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Signs with the well-known Hardhat/Anvil development mnemonic. Never use that mnemonic for real funds.
 * The reference payload is cross-checked by decoding it and recovering the signer, not just by comparing bytes.
 */
class TransactionSigningTest {
    private val account get() = sharedAccount
    private val recipient = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8"

    private companion object {
        // Derived once: PBKDF2 is slow and JUnit creates a new test instance per method.
        val sharedAccount =
            Account.fromMnemonic(
                checkNotNull(Mnemonic.parse("test test test test test test test test test test test junk")),
            )
    }

    private fun transaction(
        chainId: Long = 11155111,
        data: String = "0x",
    ) = UnsignedTransaction(
        chainId = chainId,
        nonce = BigInteger.valueOf(7),
        to = recipient,
        value = BigInteger("10000000000000000"), // 0.01 ETH
        fee =
            FeeQuote(
                gasLimit = BigInteger.valueOf(21_000),
                maxPriorityFeePerGas = BigInteger.valueOf(1_500_000_000),
                maxFeePerGas = BigInteger.valueOf(30_000_000_000),
            ),
        data = data,
    )

    @Test
    fun `signs an EIP-1559 transfer byte-for-byte like the reference implementation`() {
        val signed = account.sign(transaction())

        assertEquals(
            "0x02f87583aa36a7078459682f008506fc23ac008252089470997970c51812dc3a010c7d01b50e0d17dc79c8" +
                "872386f26fc1000080c001a0532de5ac946d9c3686090d3fa7b47e5346bb9147bba4523e382139b7ccc26ac6" +
                "a06c099a2c7dddb270ed9a195c6cf3458b4956e35b71689ccd64d731c59909d9a4",
            signed.rawHex,
        )
        assertEquals("0xe7b14eaa6852779f4000bd396cfe9dde36acba447e550f5a217d79b4aaa0fb6a", signed.hash)
    }

    @Test
    fun `signing is deterministic`() {
        assertEquals(account.sign(transaction()), account.sign(transaction()))
    }

    @Test
    fun `the signature recovers to the signing account`() {
        val decoded = TransactionDecoder.decode(account.sign(transaction()).rawHex) as SignedRawTransaction

        assertEquals(account.address.lowercase(), decoded.from.lowercase())
    }

    @Test
    fun `the signed payload carries the transaction fields`() {
        val decoded = TransactionDecoder.decode(account.sign(transaction()).rawHex) as SignedRawTransaction

        // For type-2 transactions the chain id lives in the payload, not in the signature's v value.
        assertEquals(11155111L, (decoded.transaction as Transaction1559).chainId)
        assertEquals(BigInteger.valueOf(7), decoded.nonce)
        assertEquals(recipient.lowercase(), decoded.to.lowercase())
        assertEquals(BigInteger("10000000000000000"), decoded.value)
    }

    @Test
    fun `calldata is part of the signed payload`() {
        val data = "0xa9059cbb" + "00".repeat(64)

        val decoded = TransactionDecoder.decode(account.sign(transaction(data = data)).rawHex) as SignedRawTransaction

        assertEquals(data, "0x" + decoded.data.removePrefix("0x"))
    }

    @Test
    fun `an empty calldata and a long calldata both sign`() {
        val empty = TransactionDecoder.decode(account.sign(transaction(data = "0x")).rawHex) as SignedRawTransaction
        val longData = "0x" + "ab".repeat(100_000)
        val long = TransactionDecoder.decode(account.sign(transaction(data = longData)).rawHex) as SignedRawTransaction

        assertEquals("", empty.data.removePrefix("0x"))
        assertEquals(longData, "0x" + long.data.removePrefix("0x"))
    }

    @Test
    fun `values on the uint256 and uint64 limits sign and decode`() {
        val maxValue = BigInteger.TWO.pow(256) - BigInteger.ONE
        val maxNonce = BigInteger.TWO.pow(64) - BigInteger.ONE
        val tx = UnsignedTransaction(11155111, maxNonce, recipient, maxValue, transaction().fee)

        val decoded = TransactionDecoder.decode(account.sign(tx).rawHex) as SignedRawTransaction

        assertEquals(maxValue, decoded.value)
        assertEquals(maxNonce, decoded.nonce)
    }

    @Test
    fun `another chain id gives another signature, so a transaction cannot be replayed`() {
        val sepolia = account.sign(transaction(chainId = 11155111))
        val mainnet = account.sign(transaction(chainId = 1))

        assertNotEquals(sepolia.rawHex, mainnet.rawHex)
        assertNotEquals(sepolia.hash, mainnet.hash)
    }

    @Test
    fun `another account signs differently`() {
        val other = Account.fromMnemonic(checkNotNull(Mnemonic.parse("abandon ".repeat(11) + "about")))

        assertNotEquals(account.sign(transaction()).rawHex, other.sign(transaction()).rawHex)
    }
}
