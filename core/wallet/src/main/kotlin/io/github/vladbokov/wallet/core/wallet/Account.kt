package io.github.vladbokov.wallet.core.wallet

import org.web3j.crypto.Bip32ECKeyPair
import org.web3j.crypto.Bip32ECKeyPair.HARDENED_BIT
import org.web3j.crypto.Credentials
import org.web3j.crypto.Keys
import org.web3j.crypto.MnemonicUtils

/**
 * An externally owned account (EOA): a secp256k1 key pair plus the address derived from it.
 * The private key never leaves this module.
 */
class Account private constructor(
    credentials: Credentials,
) {
    /** EIP-55 checksummed address: last 20 bytes of keccak256(public key). */
    val address: String = Keys.toChecksumAddress(credentials.address)

    companion object {
        /**
         * mnemonic --PBKDF2--> seed --BIP-32--> master key --BIP-44 path--> account key.
         * Path m/44'/60'/0'/0/index is what MetaMask and most Ethereum wallets use,
         * so the same phrase restores the same addresses there.
         *
         * Taking a [Mnemonic] instead of a String means the phrase is already validated: PBKDF2 accepts any string,
         * so a typo would otherwise silently derive a different, empty wallet.
         * The optional BIP-39 passphrase ("25th word") is not supported: the seed is always derived with an empty one,
         * so a phrase protected by a passphrase in another wallet restores a different account here.
         *
         * Deriving is CPU-heavy (PBKDF2, 2048 rounds): call it off the main thread, and use [listFromMnemonic]
         * for several accounts so the seed is computed only once.
         */
        fun fromMnemonic(
            mnemonic: Mnemonic,
            index: Int = 0,
        ): Account = listFromMnemonic(mnemonic, count = 1, firstIndex = index).single()

        /** Derives [count] consecutive accounts starting at [firstIndex], computing the seed once. */
        fun listFromMnemonic(
            mnemonic: Mnemonic,
            count: Int,
            firstIndex: Int = 0,
        ): List<Account> {
            require(count >= 0) { "Account count must be non-negative" }
            require(firstIndex >= 0 && (count == 0 || firstIndex.toLong() + count - 1 <= Int.MAX_VALUE)) {
                "Account index must be non-negative and fit in a non-hardened BIP-32 index"
            }
            val seed = MnemonicUtils.generateSeed(mnemonic.reveal(), "")
            try {
                val master = Bip32ECKeyPair.generateKeyPair(seed)
                return (firstIndex until firstIndex + count).map { index ->
                    val path =
                        intArrayOf(
                            44 or HARDENED_BIT, // purpose: BIP-44
                            60 or HARDENED_BIT, // coin type: Ethereum
                            0 or HARDENED_BIT, // account
                            0, // external chain
                            index, // address index
                        )
                    Account(Credentials.create(Bip32ECKeyPair.deriveKeyPair(master, path)))
                }
            } finally {
                seed.fill(0)
            }
        }
    }
}
