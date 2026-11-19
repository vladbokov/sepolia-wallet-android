package io.github.vladbokov.wallet.data

import io.github.vladbokov.wallet.core.wallet.vault.SeedVault
import io.github.vladbokov.wallet.domain.repository.WalletSecrets
import javax.crypto.Cipher

/** [WalletSecrets] backed by the Keystore-encrypted [SeedVault]. */
internal class VaultWalletSecrets(
    private val vault: SeedVault,
) : WalletSecrets {
    override val address: String? get() = vault.address

    override fun reveal(authenticatedCipher: Cipher): ByteArray = vault.reveal(authenticatedCipher)
}
