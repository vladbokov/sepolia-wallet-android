package io.github.vladbokov.wallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Reference addresses for the well-known Hardhat/Anvil development mnemonic and the BIP-39 "abandon" test phrase.
 * Never use these phrases for real funds.
 */
class AccountTest {
    private val mnemonic = phrase("test test test test test test test test test test test junk")

    private fun phrase(words: String) = checkNotNull(Mnemonic.parse(words))

    @Test
    fun `derives the standard BIP-44 Ethereum addresses`() {
        assertEquals(
            "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
            Account.fromMnemonic(mnemonic, index = 0).address,
        )
        assertEquals(
            "0x70997970C51812dc3A010C7d01b50e0d17dc79C8",
            Account.fromMnemonic(mnemonic, index = 1).address,
        )
    }

    @Test
    fun `index selects the next address of the same phrase`() {
        assertEquals(
            "0x3C44CdDdB6a900fa2b585dd299e03d12FA4293BC",
            Account.fromMnemonic(mnemonic, index = 2).address,
        )
        assertEquals(
            "0x90F79bf6EB2c4f870365E785982E1f101E93b906",
            Account.fromMnemonic(mnemonic, index = 3).address,
        )
    }

    @Test
    fun `index defaults to zero`() {
        assertEquals(
            Account.fromMnemonic(mnemonic, index = 0).address,
            Account.fromMnemonic(mnemonic).address,
        )
    }

    @Test
    fun `another phrase gives another wallet`() {
        val abandon = phrase("abandon ".repeat(11) + "about")

        assertEquals("0x9858EfFD232B4033E47d90003D41EC34EcaEda94", Account.fromMnemonic(abandon).address)
    }

    @Test
    fun `several accounts are derived in one pass`() {
        val accounts = Account.listFromMnemonic(mnemonic, count = 4)

        assertEquals(
            listOf(
                "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
                "0x70997970C51812dc3A010C7d01b50e0d17dc79C8",
                "0x3C44CdDdB6a900fa2b585dd299e03d12FA4293BC",
                "0x90F79bf6EB2c4f870365E785982E1f101E93b906",
            ),
            accounts.map { it.address },
        )
        assertEquals(
            Account.fromMnemonic(mnemonic, index = 5).address,
            Account.listFromMnemonic(mnemonic, count = 2, firstIndex = 4)[1].address,
        )
        assertEquals(emptyList(), Account.listFromMnemonic(mnemonic, count = 0))
    }

    @Test
    fun `invalid counts and ranges are rejected`() {
        assertFailsWith<IllegalArgumentException> { Account.listFromMnemonic(mnemonic, count = -1) }
        assertFailsWith<IllegalArgumentException> {
            Account.listFromMnemonic(mnemonic, count = 2, firstIndex = Int.MAX_VALUE)
        }
    }

    @Test
    fun `a negative index is rejected`() {
        assertFailsWith<IllegalArgumentException> { Account.fromMnemonic(mnemonic, index = -1) }
    }
}
