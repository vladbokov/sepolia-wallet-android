package io.github.vladbokov.wallet.core.wallet.vault

import org.junit.Assume.assumeNoException

/**
 * Skips (does not fail) the current test class on a device that cannot create a per-use biometric key,
 * i.e. one without a secure lock screen or an enrolled strong biometric. Call it from @Before.
 */
internal fun assumeVaultKeysAvailable(keys: SeedKeyStore) {
    try {
        keys.newEncryptCipher()
    } catch (e: VaultUnavailableException) {
        assumeNoException("Device has no secure lock screen / strong biometric", e)
    } finally {
        keys.delete()
    }
}
