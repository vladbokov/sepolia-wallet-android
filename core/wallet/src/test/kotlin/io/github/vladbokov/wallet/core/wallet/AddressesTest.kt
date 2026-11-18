package io.github.vladbokov.wallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Checksummed vectors come from the EIP-55 specification. */
class AddressesTest {
    private val checksummed =
        listOf(
            "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
            "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
            "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB",
            "0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb",
        )

    @Test
    fun `address validation enforces the EIP-55 checksum`() {
        assertTrue(Addresses.isValid("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266"))
        assertTrue(Addresses.isValid("0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266"))
        // same address with one letter's case flipped
        assertFalse(Addresses.isValid("0xF39Fd6e51aad88F6F4ce6aB8827279cffFb92266"))
        assertFalse(Addresses.isValid("0x1234"))
        assertFalse(Addresses.isValid("f39Fd6e51aad88F6F4ce6aB8827279cffFb92266"))
    }

    @Test
    fun `specification vectors are valid`() {
        checksummed.forEach { assertTrue(Addresses.isValid(it), it) }
    }

    @Test
    fun `flipping the case of any letter breaks the checksum`() {
        val address = checksummed.first()
        val letterPositions = address.indices.drop(2).filter { address[it].isLetter() }

        letterPositions.forEach { i ->
            val flipped = address[i].let { if (it.isUpperCase()) it.lowercaseChar() else it.uppercaseChar() }
            val broken = address.substring(0, i) + flipped + address.substring(i + 1)
            assertFalse(Addresses.isValid(broken), broken)
        }
    }

    @Test
    fun `all-lowercase and all-uppercase addresses carry no checksum`() {
        assertTrue(Addresses.isValid("0xde709f2102306220921060314715629080e2fb77"))
        assertTrue(Addresses.isValid("0x52908400098527886E0F7030069857D2E4169EE7"))
    }

    @Test
    fun `wrong shapes are rejected`() {
        assertFalse(Addresses.isValid(""))
        assertFalse(Addresses.isValid("0x"))
        assertFalse(Addresses.isValid("0x" + "a".repeat(39)))
        assertFalse(Addresses.isValid("0x" + "a".repeat(41)))
        assertFalse(Addresses.isValid("0x" + "g".repeat(40)))
        assertFalse(Addresses.isValid("0X" + "a".repeat(40)))
        assertFalse(Addresses.isValid(" 0x" + "a".repeat(40)))
        assertFalse(Addresses.isValid("0x" + "a".repeat(40) + "\n"))
    }

    @Test
    fun `addresses derived from a phrase are valid checksummed addresses`() {
        val mnemonic = checkNotNull(Mnemonic.parse("test test test test test test test test test test test junk"))

        (0..3).forEach { index ->
            val address = Account.fromMnemonic(mnemonic, index).address
            assertTrue(Addresses.isValid(address), address)
            assertEquals(address, Addresses.toChecksum(address.lowercase()))
        }
    }

    @Test
    fun `toChecksum produces the EIP-55 form`() {
        checksummed.forEach { assertEquals(it, Addresses.toChecksum(it.lowercase())) }
    }

    @Test
    fun `toChecksum rejects a malformed address`() {
        assertFailsWith<IllegalArgumentException> { Addresses.toChecksum("0x1234") }
    }

    @Test
    fun `toChecksum rejects a mixed-case address with a wrong checksum`() {
        assertFailsWith<IllegalArgumentException> {
            Addresses.toChecksum("0xF39Fd6e51aad88F6F4ce6aB8827279cffFb92266")
        }
    }
}
