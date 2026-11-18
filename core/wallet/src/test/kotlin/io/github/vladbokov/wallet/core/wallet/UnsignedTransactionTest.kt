package io.github.vladbokov.wallet.core.wallet

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UnsignedTransactionTest {
    private val recipient = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8"
    private val fee =
        FeeQuote(
            gasLimit = BigInteger.valueOf(21_000),
            maxPriorityFeePerGas = BigInteger.valueOf(1_500_000_000),
            maxFeePerGas = BigInteger.valueOf(30_000_000_000),
        )

    private fun transaction(
        chainId: Long = 11155111,
        nonce: BigInteger = BigInteger.ZERO,
        to: String = recipient,
        value: BigInteger = BigInteger.ONE,
        data: String = "0x",
    ) = UnsignedTransaction(chainId, nonce, to, value, fee, data)

    @Test
    fun `a plain transfer is accepted`() {
        assertEquals("0x", transaction().data)
    }

    @Test
    fun `zero value and calldata are accepted`() {
        transaction(value = BigInteger.ZERO, data = "0xa9059cbb")
    }

    @Test
    fun `a lowercase recipient is accepted`() {
        transaction(to = recipient.lowercase())
    }

    @Test
    fun `an invalid recipient is rejected`() {
        assertFailsWith<IllegalArgumentException> { transaction(to = "0x1234") }
        assertFailsWith<IllegalArgumentException> { transaction(to = "") }
        // one letter's case flipped: breaks the EIP-55 checksum
        assertFailsWith<IllegalArgumentException> { transaction(to = "0x70997970c51812dc3A010C7d01b50e0d17dc79C8") }
    }

    @Test
    fun `invalid numbers are rejected`() {
        assertFailsWith<IllegalArgumentException> { transaction(chainId = 0) }
        assertFailsWith<IllegalArgumentException> { transaction(chainId = -1) }
        assertFailsWith<IllegalArgumentException> { transaction(nonce = BigInteger.valueOf(-1)) }
        assertFailsWith<IllegalArgumentException> { transaction(value = BigInteger.valueOf(-1)) }
    }

    @Test
    fun `limits of uint64 nonce and uint256 value are enforced`() {
        val maxValue = BigInteger.TWO.pow(256) - BigInteger.ONE
        val maxNonce = BigInteger.TWO.pow(64) - BigInteger.ONE

        transaction(nonce = maxNonce, value = maxValue)
        assertFailsWith<IllegalArgumentException> { transaction(nonce = maxNonce + BigInteger.ONE) }
        assertFailsWith<IllegalArgumentException> { transaction(value = maxValue + BigInteger.ONE) }
    }

    @Test
    fun `very long calldata is validated without overflowing the stack`() {
        transaction(data = "0x" + "ab".repeat(1_000_000))
        assertFailsWith<IllegalArgumentException> { transaction(data = "0x" + "ab".repeat(1_000_000) + "zz") }
    }

    @Test
    fun `malformed calldata is rejected`() {
        assertFailsWith<IllegalArgumentException> { transaction(data = "") }
        assertFailsWith<IllegalArgumentException> { transaction(data = "a9059cbb") }
        assertFailsWith<IllegalArgumentException> { transaction(data = "0xa9059cb") } // odd number of digits
        assertFailsWith<IllegalArgumentException> { transaction(data = "0xzz") }
        assertFailsWith<IllegalArgumentException> { transaction(data = "0X00") }
        assertFailsWith<IllegalArgumentException> { transaction(data = "x0") }
    }
}
