package io.github.vladbokov.wallet.core.rpc

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HexTest {

    @Test
    fun `parses quantities with and without prefix`() {
        assertEquals(BigInteger.valueOf(21_000), "0x5208".hexToBigInteger())
        assertEquals(BigInteger.valueOf(21_000), "5208".hexToBigInteger())
        assertEquals(BigInteger.valueOf(255), "0XFF".hexToBigInteger())
    }

    @Test
    fun `parses zero`() {
        assertEquals(BigInteger.ZERO, "0x0".hexToBigInteger())
    }

    @Test
    fun `empty quantity is rejected`() {
        assertFailsWith<IllegalArgumentException> { "0x".hexToBigInteger() }
        assertFailsWith<IllegalArgumentException> { "".hexToBigInteger() }
    }

    @Test
    fun `signed quantities are rejected`() {
        assertFailsWith<IllegalArgumentException> { "-1".hexToBigInteger() }
        assertFailsWith<IllegalArgumentException> { "0x-1".hexToBigInteger() }
        assertFailsWith<IllegalArgumentException> { "+1".hexToBigInteger() }
    }

    @Test
    fun `non-hex digits are rejected`() {
        assertFailsWith<IllegalArgumentException> { "0xZZ".hexToBigInteger() }
        assertFailsWith<IllegalArgumentException> { "0x12 ".hexToBigInteger() }
    }

    @Test
    fun `encodes without leading zeros`() {
        assertEquals("0x0", BigInteger.ZERO.toHexQuantity())
        assertEquals("0x5208", BigInteger.valueOf(21_000).toHexQuantity())
    }

    @Test
    fun `one ether in wei does not overflow`() {
        val oneEther = BigInteger.TEN.pow(18)
        assertEquals("0xde0b6b3a7640000", oneEther.toHexQuantity())
        assertEquals(oneEther, "0xde0b6b3a7640000".hexToBigInteger())
    }

    @Test
    fun `negative quantities are rejected`() {
        assertFailsWith<IllegalArgumentException> { BigInteger.valueOf(-1).toHexQuantity() }
    }
}
