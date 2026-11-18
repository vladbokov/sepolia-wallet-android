package io.github.vladbokov.wallet.core.wallet

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class UnitsTest {
    @Test
    fun `amounts are parsed to wei exactly`() {
        assertEquals(BigInteger("10000000000000000"), Units.parse("0.01"))
        assertEquals(BigInteger("1500000000000000000"), Units.parse(" 1.5 "))
        assertEquals(BigInteger("1000000000000000000"), Units.parse("1"))
        assertEquals(BigInteger.ONE, Units.parse("0.000000000000000001"))
    }

    @Test
    fun `partial notations are accepted`() {
        assertEquals(BigInteger("500000000000000000"), Units.parse(".5"))
        assertEquals(BigInteger("5000000000000000000"), Units.parse("5."))
    }

    @Test
    fun `amounts larger than Long are parsed`() {
        assertEquals(BigInteger.TEN.pow(20), Units.parse("100"))
    }

    @Test
    fun `malformed input is rejected`() {
        assertNull(Units.parse("abc"))
        assertNull(Units.parse(""))
        assertNull(Units.parse("."))
        assertNull(Units.parse("1.2.3"))
        assertNull(Units.parse("1 000"))
    }

    @Test
    fun `comma separator is rejected as ambiguous`() {
        assertNull(Units.parse("1,5"))
        assertNull(Units.parse("1,000"))
        assertNull(Units.parse("1,000.5"))
        assertNull(Units.parse(",5"))
    }

    @Test
    fun `zero is parsed and left for the caller to reject`() {
        assertEquals(BigInteger.ZERO, Units.parse("0"))
        assertEquals(BigInteger.ZERO, Units.parse("0.0"))
        assertEquals(BigInteger.ZERO, Units.parse(".0"))
    }

    @Test
    fun `overlong input is rejected`() {
        assertNull(Units.parse("1".repeat(Units.MAX_INPUT_LENGTH + 1)))
        assertEquals(
            BigInteger("1".repeat(Units.MAX_INPUT_LENGTH)).multiply(BigInteger.TEN.pow(18)),
            Units.parse("1".repeat(Units.MAX_INPUT_LENGTH)),
        )
    }

    @Test
    fun `negative amounts, signs and exponents are rejected`() {
        assertNull(Units.parse("-1"))
        assertNull(Units.parse("+1"))
        assertNull(Units.parse("1e5"))
        assertNull(Units.parse("1E-3"))
    }

    @Test
    fun `non-ASCII digits are rejected`() {
        assertNull(Units.parse("١٢٣")) // Arabic-Indic digits
        assertNull(Units.parse("１")) // full-width digit
    }

    @Test
    fun `fractions of the smallest unit are rejected`() {
        assertNull(Units.parse("0.0000000000000000001")) // 19 fraction digits: below 1 wei
        assertNull(Units.parse("1.0000001", decimals = 6))
    }

    @Test
    fun `other decimals are supported`() {
        assertEquals(BigInteger("1500000"), Units.parse("1.5", decimals = 6))
        assertEquals(BigInteger("12"), Units.parse("12", decimals = 0))
    }

    @Test
    fun `amounts are formatted without trailing zeros and rounded down`() {
        assertEquals("0.01", Units.format(BigInteger("10000000000000000")))
        assertEquals("0", Units.format(BigInteger.ZERO))
        assertEquals("1.999999", Units.format(BigInteger("1999999999999999999")))
        assertEquals("12", Units.format(BigInteger("12000000"), decimals = 6))
    }

    @Test
    fun `dust below the display precision shows as zero`() {
        assertEquals("0", Units.format(BigInteger.ONE))
    }

    @Test
    fun `fraction digits can be limited`() {
        assertEquals("1.5", Units.format(BigInteger("1500000000000000000"), maxFractionDigits = 2))
        assertEquals("1", Units.format(BigInteger("1999999999999999999"), maxFractionDigits = 0))
    }

    @Test
    fun `format and parse round-trip within the display precision`() {
        val wei = BigInteger("123456000000000000")
        assertEquals(wei, Units.parse(Units.format(wei)))
    }

    @Test
    fun `negative decimals are rejected`() {
        assertFailsWith<IllegalArgumentException> { Units.parse("1", decimals = -1) }
        assertFailsWith<IllegalArgumentException> { Units.format(BigInteger.ONE, decimals = -1) }
    }

    @Test
    fun `decimals above the maximum are rejected`() {
        assertFailsWith<IllegalArgumentException> { Units.parse("1", decimals = Units.MAX_DECIMALS + 1) }
        assertFailsWith<IllegalArgumentException> { Units.format(BigInteger.ONE, decimals = Units.MAX_DECIMALS + 1) }
    }

    @Test
    fun `negative amounts are formatted truncated towards zero`() {
        assertEquals("-1.5", Units.format(BigInteger("-1500000000000000000")))
        assertEquals("-1.999999", Units.format(BigInteger("-1999999999999999999")))
        assertEquals("0", Units.format(BigInteger("-1")))
    }
}
