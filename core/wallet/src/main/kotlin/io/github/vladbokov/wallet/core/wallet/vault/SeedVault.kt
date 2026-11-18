package io.github.vladbokov.wallet.core.wallet.vault

import android.content.Context
import android.content.SharedPreferences
import io.github.vladbokov.wallet.core.wallet.Addresses
import java.util.Base64
import javax.crypto.Cipher

/**
 * A key created for a new wallet. Pass [cipher] through BiometricPrompt as a CryptoObject, then give this
 * object to [SeedVault.store].
 */
class PendingSeed internal constructor(
    internal val key: NewSeedKey,
) {
    val cipher: Cipher get() = key.cipher
}

/**
 * Stores the recovery phrase encrypted with the biometric-bound key from [SeedKeyStore].
 * Only ciphertext, IV and the public address are persisted, never a key.
 *
 * Every Keystore cipher handed out here must be unlocked through BiometricPrompt before it is used.
 */
class SeedVault internal constructor(
    private val keys: SeedKeyStore,
    private val prefs: SharedPreferences,
) {
    constructor(context: Context) : this(
        SeedKeyStore(),
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
    )

    /** Public data, kept in clear so balances can be shown without a biometric prompt. */
    val address: String? get() = if (hasWallet) prefs.getString(KEY_ADDRESS, null) else null

    /** True if wallet data is present. Does not check it is readable: decrypting reports damaged data. */
    val hasWallet: Boolean get() = REQUIRED_KEYS.all(prefs::contains)

    /**
     * Creates a key for a new wallet. An existing wallet and its key stay untouched until [store] succeeds,
     * so cancelling the biometric prompt never loses the current phrase.
     * @throws VaultUnavailableException if no strong biometric or secure lock screen is set up.
     */
    fun newEncryptCipher(): PendingSeed = PendingSeed(keys.newEncryptCipher())

    /**
     * Returns a cipher to pass through BiometricPrompt before [reveal].
     * @throws IllegalStateException if no wallet is stored.
     * @throws VaultInvalidatedException if the key was destroyed (e.g. by a biometric change) or the
     *   stored data is damaged.
     */
    fun newDecryptCipher(): Cipher {
        val wallet = stored()
        return keys.newDecryptCipher(wallet.alias, wallet.iv)
    }

    /**
     * Encrypts [secret] with the unlocked cipher of [pending] and replaces the stored wallet.
     * The old key is deleted only after the new data is on disk.
     * The caller owns [secret] and must zero it after this call.
     * @throws VaultStorageException if the data could not be written; the previous wallet is kept.
     * @throws java.security.GeneralSecurityException if the cipher was not unlocked through BiometricPrompt.
     */
    fun store(
        pending: PendingSeed,
        secret: ByteArray,
        address: String,
    ) {
        require(secret.isNotEmpty()) { "Nothing to store" }
        require(Addresses.isValid(address)) { "Not a valid address" }
        val ciphertext = pending.cipher.doFinal(secret)
        val encoder = Base64.getEncoder()
        // One synchronous commit of all four values: a killed process can never leave half a wallet behind.
        val saved =
            prefs
                .edit()
                .putString(KEY_ALIAS, pending.key.alias)
                .putString(KEY_CIPHERTEXT, encoder.encodeToString(ciphertext))
                .putString(KEY_IV, encoder.encodeToString(pending.cipher.iv))
                .putString(KEY_ADDRESS, address)
                .commit()
        if (!saved) throw VaultStorageException()
        keys.retainOnly(pending.key.alias)
    }

    /**
     * Decrypts the phrase with a cipher from [newDecryptCipher] that BiometricPrompt has unlocked.
     * The caller owns the returned array and must zero it after use.
     * @throws IllegalStateException if no wallet is stored.
     * @throws VaultInvalidatedException if the stored data is damaged.
     */
    fun reveal(authenticatedCipher: Cipher): ByteArray = authenticatedCipher.doFinal(stored().ciphertext)

    /**
     * Removes the stored wallet and every key.
     * @throws VaultStorageException if the data could not be removed; the key is then kept too.
     */
    fun wipe() {
        // Data first: if the process dies in between, a leftover key is harmless, a leftover wallet without a key is not.
        if (!prefs.edit().clear().commit()) throw VaultStorageException()
        keys.delete()
    }

    private class StoredWallet(
        val alias: String,
        val ciphertext: ByteArray,
        val iv: ByteArray,
        val address: String,
    )

    /** Reads and decodes the wallet. Only decrypting paths call this; the cheap getters never decode. */
    private fun stored(): StoredWallet {
        check(hasWallet) { "No wallet stored" }
        val alias = prefs.getString(KEY_ALIAS, null)
        val ciphertext = prefs.getString(KEY_CIPHERTEXT, null)
        val iv = prefs.getString(KEY_IV, null)
        val address = prefs.getString(KEY_ADDRESS, null)
        if (alias == null || ciphertext == null || iv == null || address == null) throw VaultInvalidatedException()
        val decoder = Base64.getDecoder()
        return try {
            StoredWallet(alias, decoder.decode(ciphertext), decoder.decode(iv), address)
        } catch (e: IllegalArgumentException) {
            throw VaultInvalidatedException(e) // damaged data cannot be decrypted: restore from the phrase
        }
    }

    private companion object {
        const val PREFS = "seed_vault"
        const val KEY_ALIAS = "alias"
        const val KEY_CIPHERTEXT = "ciphertext"
        const val KEY_IV = "iv"
        const val KEY_ADDRESS = "address"
        val REQUIRED_KEYS = listOf(KEY_ALIAS, KEY_CIPHERTEXT, KEY_IV, KEY_ADDRESS)
    }
}
