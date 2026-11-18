package io.github.vladbokov.wallet.core.wallet.vault

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.InvalidAlgorithmParameterException
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A freshly generated key: store [alias] next to the ciphertext, then call [SeedKeyStore.retainOnly]. */
internal class NewSeedKey(
    val alias: String,
    val cipher: Cipher,
)

/**
 * The AES-256-GCM key that protects the recovery phrase. It lives in Android Keystore (StrongBox when
 * available) and its bytes never enter app memory: the app only ever holds a [Cipher] bound to it.
 *
 * The key requires a Class 3 (strong) biometric for EVERY use. A cipher obtained here is useless until it has
 * been unlocked through BiometricPrompt as a CryptoObject, so even code inside the app process cannot
 * encrypt or decrypt without the user physically confirming.
 *
 * Replacing a key is a two-step commit so the old phrase is never lost half-way: [newEncryptCipher] adds a
 * new key under a new alias and leaves the old one untouched; only [retainOnly], called after the new
 * ciphertext is safely persisted, removes the old key.
 *
 * TODO: errors from the Keystore itself (KeyStoreException, UnrecoverableKeyException, ProviderException)
 *  are not mapped to [VaultException] yet; decide where the error boundary is.
 * TODO: after release, decide whether to expose where the key actually lives (StrongBox or TEE, see
 *  KeyInfo.securityLevel). generateKey silently falls back to TEE when StrongBox is unavailable; it does not
 *  affect functionality, only the hardware protection level.
 */
internal class SeedKeyStore(
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) },
) {
    /** Aliases of all seed keys currently in the Keystore (more than one only between encrypt and commit). */
    val aliases: List<String> get() = keyStore.aliases().toList().filter { it.startsWith(ALIAS_PREFIX) }

    val hasKey: Boolean get() = aliases.isNotEmpty()

    /**
     * Creates a new key under a fresh alias, WITHOUT touching existing keys, and returns a cipher to pass
     * through BiometricPrompt.
     * @throws VaultUnavailableException if no key with per-use biometric authentication can be created.
     */
    fun newEncryptCipher(): NewSeedKey {
        val alias = ALIAS_PREFIX + UUID.randomUUID()
        val key = generateKey(alias)
        return NewSeedKey(alias, Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) })
    }

    /**
     * Deletes every seed key except [alias]. Call it once the ciphertext made with [alias] is persisted.
     * Also cleans up keys left behind by an encryption that was cancelled or failed.
     */
    fun retainOnly(alias: String) {
        aliases.filter { it != alias }.forEach(keyStore::deleteEntry)
    }

    /**
     * Returns a cipher for [iv] to pass through BiometricPrompt before decrypting.
     * @throws VaultInvalidatedException if the key is gone or was invalidated by a biometric change.
     */
    fun newDecryptCipher(
        alias: String,
        iv: ByteArray,
    ): Cipher {
        val key = keyStore.getKey(alias, null) as? SecretKey ?: throw VaultInvalidatedException()
        return try {
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw VaultInvalidatedException(e)
        }
    }

    /** Deletes every seed key. */
    fun delete() {
        aliases.forEach(keyStore::deleteEntry)
    }

    private fun generateKey(alias: String): SecretKey =
        try {
            generateKey(alias, strongBox = true)
        } catch (e: StrongBoxUnavailableException) {
            generateKey(alias, strongBox = false) // falls back to the TEE-backed Keystore
        }

    private fun generateKey(
        alias: String,
        strongBox: Boolean,
    ): SecretKey {
        val spec =
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .setUserAuthenticationRequired(true)
                // timeout 0 = authorization is valid for exactly one cryptographic operation
                .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                .setInvalidatedByBiometricEnrollment(true)
                .setIsStrongBoxBacked(strongBox)
                .build()
        return try {
            KeyGenerator
                .getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                .apply { init(spec) }
                .generateKey()
        } catch (e: InvalidAlgorithmParameterException) {
            throw VaultUnavailableException(e)
        }
    }

    internal companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS_PREFIX = "wallet_seed_key_"
        const val KEY_SIZE_BITS = 256
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}
