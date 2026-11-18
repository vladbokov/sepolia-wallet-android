package io.github.vladbokov.wallet.core.wallet.vault

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.GeneralSecurityException
import java.util.Base64
import javax.crypto.Cipher

/**
 * BiometricPrompt cannot be driven from here, so a successful encrypt/decrypt round trip is not covered:
 * it is checked by hand through the app. These tests cover everything around it: state, validation and,
 * above all, that a failed or cancelled store never destroys the wallet that is already there.
 */
@RunWith(AndroidJUnit4::class)
class SeedVaultTest {
    private val keys = SeedKeyStore()
    private lateinit var prefs: SharedPreferences
    private lateinit var vault: SeedVault

    private val address = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266"
    private val otherAddress = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = context.getSharedPreferences("seed_vault_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        keys.delete()
        assumeVaultKeysAvailable(keys)
        vault = SeedVault(keys, prefs)
    }

    @After
    fun tearDown() {
        vault.wipe()
    }

    /** Simulates a wallet that an earlier, successful store left behind (the ciphertext itself is a dummy). */
    private fun givenStoredWallet(alias: String = keys.newEncryptCipher().alias) {
        val encoder = Base64.getEncoder()
        prefs
            .edit()
            .putString("alias", alias)
            .putString("ciphertext", encoder.encodeToString(ByteArray(32)))
            .putString("iv", encoder.encodeToString(ByteArray(12)))
            .putString("address", address)
            .commit()
    }

    @Test
    fun aNewVaultHasNoWallet() {
        assertFalse(vault.hasWallet)
        assertNull(vault.address)
    }

    @Test
    fun aStoredWalletIsReportedWithItsPublicAddress() {
        givenStoredWallet()

        assertTrue(vault.hasWallet)
        assertEquals(address, vault.address)
    }

    @Test
    fun incompleteDataIsNotAWallet() {
        prefs.edit().putString("address", address).commit()

        assertFalse(vault.hasWallet)
        assertNull(vault.address)
    }

    @Test
    fun withoutAWalletThereIsNothingToDecrypt() {
        assertThrows(IllegalStateException::class.java) { vault.newDecryptCipher() }
        assertThrows(IllegalStateException::class.java) {
            vault.reveal(Cipher.getInstance("AES/GCM/NoPadding"))
        }
    }

    @Test
    fun aStoredWalletGivesADecryptCipher() {
        givenStoredWallet()

        assertNotNull(vault.newDecryptCipher())
    }

    @Test
    fun aMissingKeyIsReportedAsInvalidated() {
        givenStoredWallet(alias = SeedKeyStore.ALIAS_PREFIX + "gone")

        assertThrows(VaultInvalidatedException::class.java) { vault.newDecryptCipher() }
    }

    @Test
    fun aFailedStoreKeepsTheExistingWalletAndItsKey() {
        val existing = keys.newEncryptCipher().alias
        givenStoredWallet(alias = existing)
        val pending = vault.newEncryptCipher()

        // The cipher was never unlocked through BiometricPrompt, so encryption fails.
        assertThrows(GeneralSecurityException::class.java) { vault.store(pending, ByteArray(16) { 1 }, otherAddress) }

        assertEquals(address, vault.address)
        assertTrue(existing in keys.aliases)
    }

    @Test
    fun invalidInputIsRejectedBeforeAnythingIsTouched() {
        val pending = vault.newEncryptCipher()

        assertThrows(IllegalArgumentException::class.java) { vault.store(pending, ByteArray(0), address) }
        assertThrows(IllegalArgumentException::class.java) { vault.store(pending, ByteArray(16), "0x1234") }

        assertFalse(vault.hasWallet)
    }

    @Test
    fun damagedDataIsKeptButReportedAsInvalidatedWhenDecrypting() {
        prefs
            .edit()
            .putString("alias", SeedKeyStore.ALIAS_PREFIX + "any")
            .putString("ciphertext", "not base64 !!")
            .putString("iv", "not base64 !!")
            .putString("address", address)
            .commit()

        assertTrue(vault.hasWallet)
        assertEquals(address, vault.address)
        assertThrows(VaultInvalidatedException::class.java) { vault.newDecryptCipher() }
        assertThrows(VaultInvalidatedException::class.java) {
            vault.reveal(Cipher.getInstance("AES/GCM/NoPadding"))
        }
    }

    @Test
    fun wipeKeepsTheKeyWhenTheDataCannotBeRemoved() {
        givenStoredWallet()
        val failing = SeedVault(keys, FailingCommitPrefs(prefs))

        assertThrows(VaultStorageException::class.java) { failing.wipe() }

        assertTrue(vault.hasWallet)
        assertTrue(keys.hasKey)
    }

    @Test
    fun wipeRemovesTheWalletAndEveryKey() {
        givenStoredWallet()
        vault.newEncryptCipher()

        vault.wipe()

        assertFalse(vault.hasWallet)
        assertFalse(keys.hasKey)
    }
}

/** Behaves like the real preferences, except that every commit reports failure and writes nothing. */
private class FailingCommitPrefs(
    private val real: SharedPreferences,
) : SharedPreferences by real {
    override fun edit(): SharedPreferences.Editor = FailingCommitEditor(real.edit())
}

private class FailingCommitEditor(
    private val real: SharedPreferences.Editor,
) : SharedPreferences.Editor by real {
    // Delegated chain methods would return the real editor and bypass the failing commit.
    override fun clear(): SharedPreferences.Editor = this

    override fun putString(
        key: String,
        value: String?,
    ): SharedPreferences.Editor = this

    override fun commit(): Boolean = false
}
