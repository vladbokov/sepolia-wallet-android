package io.github.vladbokov.wallet.core.wallet.vault

import android.os.Build
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

/**
 * Runs on a device or emulator with a secure lock screen and an enrolled strong biometric; the tests are
 * skipped (not failed) when the device cannot create the key.
 * It cannot drive BiometricPrompt, so it checks the protection the key was created with, not the unlock itself.
 */
@RunWith(AndroidJUnit4::class)
class SeedKeyStoreTest {
    private val store = SeedKeyStore()
    private val isEmulator = Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")

    @Before
    fun setUp() {
        store.delete()
        assumeVaultKeysAvailable(store)
    }

    @After
    fun tearDown() {
        store.delete()
    }

    private fun keyInfo(alias: String): KeyInfo {
        val keyStore = KeyStore.getInstance(SeedKeyStore.ANDROID_KEYSTORE).apply { load(null) }
        val key = keyStore.getKey(alias, null) as SecretKey
        val factory = SecretKeyFactory.getInstance(key.algorithm, SeedKeyStore.ANDROID_KEYSTORE)
        return factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
    }

    @Test
    fun newEncryptCipherCreatesAKeyAndReturnsAnEncryptCipher() {
        assertFalse(store.hasKey)

        val new = store.newEncryptCipher()

        assertTrue(store.hasKey)
        assertEquals(listOf(new.alias), store.aliases)
        assertEquals("AES/GCM/NoPadding", new.cipher.algorithm)
    }

    @Test
    fun keyIsAes256GcmAndKeptInTheKeystore() {
        val info = keyInfo(store.newEncryptCipher().alias)

        assertEquals(256, info.keySize)
        assertEquals(setOf(KeyProperties.BLOCK_MODE_GCM), info.blockModes.toSet())
        assertEquals(KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT, info.purposes)
    }

    @Test
    fun keyNeedsAStrongBiometricForEveryUse() {
        val info = keyInfo(store.newEncryptCipher().alias)

        assertTrue(info.isUserAuthenticationRequired)
        assertEquals(0, info.userAuthenticationValidityDurationSeconds)
        assertEquals(KeyProperties.AUTH_BIOMETRIC_STRONG, info.userAuthenticationType)
    }

    @Test
    @Suppress("DEPRECATION") // isInsideSecureHardware is replaced by securityLevel only on API 31+
    fun keyAndItsAuthenticationRequirementLiveInSecureHardware() {
        assumeFalse("Emulators have no secure hardware", isEmulator)

        val info = keyInfo(store.newEncryptCipher().alias)

        assertTrue(info.isInsideSecureHardware)
        assertTrue(info.isUserAuthenticationRequirementEnforcedBySecureHardware)
    }

    @Test
    fun keyIsInvalidatedWhenBiometricsChange() {
        assertTrue(keyInfo(store.newEncryptCipher().alias).isInvalidatedByBiometricEnrollment)
    }

    @Test
    fun cipherCannotEncryptWithoutAuthentication() {
        val cipher = store.newEncryptCipher().cipher

        // The exact subtype depends on the Android version, all of them are GeneralSecurityException.
        assertThrows(GeneralSecurityException::class.java) { cipher.doFinal(ByteArray(16)) }
    }

    @Test
    fun newKeyDoesNotTouchThePreviousOneUntilCommitted() {
        val first = store.newEncryptCipher()

        val second = store.newEncryptCipher()

        assertTrue(first.alias != second.alias)
        assertEquals(setOf(first.alias, second.alias), store.aliases.toSet())
    }

    @Test
    fun retainOnlyRemovesEveryOtherKey() {
        val first = store.newEncryptCipher()
        val second = store.newEncryptCipher()

        store.retainOnly(second.alias)

        assertEquals(listOf(second.alias), store.aliases)
        assertThrows(VaultInvalidatedException::class.java) { store.newDecryptCipher(first.alias, ByteArray(12)) }
    }

    @Test
    fun decryptingWithAnUnknownAliasIsReportedAsInvalidated() {
        assertFalse(store.hasKey)

        assertThrows(VaultInvalidatedException::class.java) {
            store.newDecryptCipher("${SeedKeyStore.ALIAS_PREFIX}missing", ByteArray(12))
        }
    }

    @Test
    fun deleteRemovesEveryKey() {
        store.newEncryptCipher()
        store.newEncryptCipher()

        store.delete()

        assertFalse(store.hasKey)
    }
}
