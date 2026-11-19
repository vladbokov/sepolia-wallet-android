package io.github.vladbokov.wallet.domain.repository

import javax.crypto.Cipher

/**
 * The domain's view of the stored recovery phrase. Implemented over the Android Keystore in the data layer, so
 * the code that handles the phrase can be tested without Android.
 */
interface WalletSecrets {
    /** Address of the stored wallet, or null if there is none. */
    val address: String?

    /**
     * Decrypts the stored phrase with a [authenticatedCipher] that is already unlocked by the user.
     * The caller owns the returned bytes and must zero them as soon as it is done.
     */
    fun reveal(authenticatedCipher: Cipher): ByteArray
}
