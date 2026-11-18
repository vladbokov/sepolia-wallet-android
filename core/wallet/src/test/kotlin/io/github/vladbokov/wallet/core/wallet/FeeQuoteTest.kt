package io.github.vladbokov.wallet.core.wallet

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FeeQuoteTest {
    private fun wei(value: Long) = BigInteger.valueOf(value)

    @Test
    fun `fee quote doubles the base fee and buffers the gas limit`() {
        val quote = FeeQuote.estimate(estimatedGas = wei(21_000), baseFee = wei(10), priorityFee = wei(2))

        assertEquals(wei(25_200), quote.gasLimit)
        assertEquals(wei(22), quote.maxFeePerGas)
        assertEquals(wei(2), quote.maxPriorityFeePerGas)
        assertEquals(wei(554_400), quote.maxTotalFee)
    }

    @Test
    fun `gas buffer is rounded up, never down`() {
        assertEquals(wei(25_202), FeeQuote.estimate(wei(21_001), wei(1), wei(1)).gasLimit) // 25201.2
        assertEquals(wei(2), FeeQuote.estimate(wei(1), wei(1), wei(1)).gasLimit) // 1.2
    }

    @Test
    fun `realistic gwei values`() {
        val gwei = BigInteger.TEN.pow(9)

        val quote = FeeQuote.estimate(wei(21_000), baseFee = gwei * wei(15), priorityFee = gwei * wei(3) / wei(2))

        assertEquals(gwei * wei(63) / wei(2), quote.maxFeePerGas) // 2 * 15 + 1.5 = 31.5 gwei
        assertEquals(gwei * wei(3) / wei(2), quote.maxPriorityFeePerGas)
    }

    @Test
    fun `zero base fee leaves only the tip`() {
        val quote = FeeQuote.estimate(wei(21_000), baseFee = BigInteger.ZERO, priorityFee = wei(5))

        assertEquals(wei(5), quote.maxFeePerGas)
    }

    @Test
    fun `all-zero fees give a zero price cap`() {
        val quote = FeeQuote.estimate(wei(21_000), baseFee = BigInteger.ZERO, priorityFee = BigInteger.ZERO)

        assertEquals(BigInteger.ZERO, quote.maxFeePerGas)
        assertEquals(BigInteger.ZERO, quote.maxTotalFee)
    }

    @Test
    fun `fee totals beyond Long are supported`() {
        val quote = FeeQuote.estimate(FeeQuote.MAX_ESTIMATED_GAS, FeeQuote.MAX_FEE_PER_GAS, FeeQuote.MAX_FEE_PER_GAS)

        assertEquals(FeeQuote.MAX_FEE_PER_GAS * wei(3), quote.maxFeePerGas)
        assertEquals(quote.gasLimit * quote.maxFeePerGas, quote.maxTotalFee)
        assertTrue(quote.maxTotalFee > BigInteger.valueOf(Long.MAX_VALUE))
    }

    @Test
    fun `invalid input is rejected`() {
        assertFailsWith<IllegalArgumentException> { FeeQuote.estimate(BigInteger.ZERO, wei(1), wei(1)) }
        assertFailsWith<IllegalArgumentException> { FeeQuote.estimate(wei(-1), wei(1), wei(1)) }
        assertFailsWith<IllegalArgumentException> { FeeQuote.estimate(wei(21_000), wei(-1), wei(1)) }
        assertFailsWith<IllegalArgumentException> { FeeQuote.estimate(wei(21_000), wei(1), wei(-1)) }
    }

    @Test
    fun `absurd values from a faulty node are rejected`() {
        val aboveGas = FeeQuote.MAX_ESTIMATED_GAS + BigInteger.ONE
        val aboveFee = FeeQuote.MAX_FEE_PER_GAS + BigInteger.ONE

        assertFailsWith<IllegalArgumentException> { FeeQuote.estimate(aboveGas, wei(1), wei(1)) }
        assertFailsWith<IllegalArgumentException> { FeeQuote.estimate(wei(21_000), aboveFee, wei(1)) }
        assertFailsWith<IllegalArgumentException> { FeeQuote.estimate(wei(21_000), wei(1), aboveFee) }
        // the bounds themselves are accepted
        FeeQuote.estimate(FeeQuote.MAX_ESTIMATED_GAS, FeeQuote.MAX_FEE_PER_GAS, FeeQuote.MAX_FEE_PER_GAS)
    }

    @Test
    fun `a price cap equal to the tip cap is allowed`() {
        val quote = FeeQuote(gasLimit = wei(21_000), maxPriorityFeePerGas = wei(5), maxFeePerGas = wei(5))

        assertEquals(wei(105_000), quote.maxTotalFee)
    }

    @Test
    fun `a quote whose tip cap exceeds its price cap is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            FeeQuote(gasLimit = wei(21_000), maxPriorityFeePerGas = wei(5), maxFeePerGas = wei(4))
        }
    }

    @Test
    fun `a quote with a non-positive gas limit or a negative tip is rejected`() {
        assertFailsWith<IllegalArgumentException> { FeeQuote(BigInteger.ZERO, wei(1), wei(1)) }
        assertFailsWith<IllegalArgumentException> { FeeQuote(wei(-1), wei(1), wei(1)) }
        assertFailsWith<IllegalArgumentException> { FeeQuote(wei(21_000), wei(-1), wei(1)) }
    }
}
