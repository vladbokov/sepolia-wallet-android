package io.github.vladbokov.wallet.core.wallet

import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MnemonicTest {
    /** A "random" source that always yields the same byte, to reproduce the official BIP-39 vectors. */
    private fun constantRandom(value: Int) =
        object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) {
                bytes.fill(value.toByte())
            }
        }

    @Test
    fun `generated mnemonic has 12 words and a valid checksum`() {
        val mnemonic = Mnemonic.generate().reveal()

        assertEquals(12, mnemonic.split(" ").size)
        assertTrue(Mnemonic.isValid(mnemonic))
    }

    @Test
    fun `generated mnemonics differ`() {
        assertNotEquals(Mnemonic.generate().reveal(), Mnemonic.generate().reveal())
    }

    @Test
    fun `entropy maps to the official BIP-39 test vectors`() {
        assertEquals(
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
            Mnemonic.generate(constantRandom(0x00)).reveal(),
        )
        assertEquals(
            "legal winner thank year wave sausage worth useful legal winner thank yellow",
            Mnemonic.generate(constantRandom(0x7f)).reveal(),
        )
        assertEquals(
            "letter advice cage absurd amount doctor acoustic avoid letter advice cage above",
            Mnemonic.generate(constantRandom(0x80)).reveal(),
        )
        assertEquals(
            "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong",
            Mnemonic.generate(constantRandom(0xff)).reveal(),
        )
    }

    @Test
    fun `validation accepts a correct phrase`() {
        assertTrue(Mnemonic.isValid("test test test test test test test test test test test junk"))
    }

    @Test
    fun `validation accepts every standard length`() {
        assertTrue(Mnemonic.isValid("abandon ".repeat(14) + "address"))
        assertTrue(Mnemonic.isValid("abandon ".repeat(17) + "agent"))
        assertTrue(Mnemonic.isValid("abandon ".repeat(20) + "admit"))
        assertTrue(Mnemonic.isValid("abandon ".repeat(23) + "art"))
    }

    @Test
    fun `validation rejects a bad checksum`() {
        assertFalse(Mnemonic.isValid("test test test test test test test test test test test test"))
    }

    @Test
    fun `validation rejects unknown words and wrong lengths`() {
        assertFalse(Mnemonic.isValid("notaword ".repeat(12).trim()))
        assertFalse(Mnemonic.isValid("hello world"))
        assertFalse(Mnemonic.isValid(""))
        assertFalse(Mnemonic.isValid("abandon ".repeat(10) + "about")) // 11 words
    }

    @Test
    fun `validation normalizes its input`() {
        assertTrue(Mnemonic.isValid("  Abandon  ".repeat(11) + "ABOUT\n"))
    }

    @Test
    fun `parse returns the normalized phrase or null`() {
        val messy = "  Test test  TEST test test\ntest test test test test test junk "

        assertEquals(
            "test test test test test test test test test test test junk",
            Mnemonic.parse(messy)?.reveal(),
        )
        assertNull(Mnemonic.parse("test ".repeat(12)))
        assertNull(Mnemonic.parse(""))
    }

    @Test
    fun `mnemonic does not leak through toString`() {
        val mnemonic = Mnemonic.generate()

        assertEquals("Mnemonic(***)", mnemonic.toString())
        assertEquals("Mnemonic(***)", "$mnemonic")
    }

    @Test
    fun `parse normalizes case and any whitespace between words`() {
        val expected = "test test test test test test test test test test test junk"

        assertEquals(
            expected,
            Mnemonic.parse("  Test \n  TEST\ttest test test test test test test test test junk ")?.reveal(),
        )
        assertEquals(expected, Mnemonic.parse("test\u00A0".repeat(11) + "junk")?.reveal())
    }
}
