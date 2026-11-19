package io.github.vladbokov.wallet.domain.usecase

import io.github.vladbokov.wallet.core.wallet.Account
import io.github.vladbokov.wallet.core.wallet.Mnemonic
import io.github.vladbokov.wallet.core.wallet.SignedTransaction
import io.github.vladbokov.wallet.core.wallet.vault.VaultException
import io.github.vladbokov.wallet.domain.model.DataError
import io.github.vladbokov.wallet.domain.model.SendResult
import io.github.vladbokov.wallet.domain.model.TransferDraft
import io.github.vladbokov.wallet.domain.repository.WalletRepository
import io.github.vladbokov.wallet.domain.repository.WalletSecrets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.crypto.Cipher
import javax.inject.Inject

/**
 * Signs on-device and broadcasts. The node only ever sees the signed transaction: the phrase is decrypted in memory
 * for the duration of signing and its bytes are zeroed right after. This is the one place where the phrase is
 * decrypted for sending.
 *
 * The phrase has to pass through a `String` to be parsed, and a `String` cannot be zeroed; that part is left to
 * the garbage collector.
 *
 * Cancellation: while the transaction is being signed it is harmless, nothing has been sent. If the coroutine is
 * cancelled during the broadcast, the request may already have reached the node, yet the caller never learns the
 * hash and might send the transfer again. Call this from a scope that outlives the screen.
 */
class SendTransferUseCase
    @Inject
    constructor(
        private val repository: WalletRepository,
        private val secrets: WalletSecrets,
    ) {
        /**
         * @param authenticatedCipher a decrypt cipher already unlocked through `BiometricPrompt`.
         * @return [SendResult.Sent], or [SendResult.Unconfirmed] if the connection failed during the broadcast and
         * the node may or may not have taken the transaction.
         * @throws DataError if the node rejected the transaction or answered with something invalid.
         * @throws VaultException if the vault cannot decrypt.
         * @throws IllegalStateException if the stored phrase is damaged or does not belong to the stored address.
         */
        suspend operator fun invoke(
            draft: TransferDraft,
            authenticatedCipher: Cipher,
        ): SendResult {
            val signed = withContext(Dispatchers.Default) { sign(draft, authenticatedCipher) }
            return try {
                repository.broadcast(signed)
                SendResult.Sent(signed.hash)
            } catch (e: DataError.Network) {
                // The hash is fixed by the signed bytes, so the caller can still track the transaction by it.
                SendResult.Unconfirmed(signed.hash)
            }
        }

        private fun sign(
            draft: TransferDraft,
            authenticatedCipher: Cipher,
        ): SignedTransaction {
            val secret = secrets.reveal(authenticatedCipher)
            try {
                val mnemonic =
                    Mnemonic.parse(String(secret, Charsets.UTF_8)) ?: error("Stored recovery phrase is not valid")
                val account = Account.fromMnemonic(mnemonic)
                check(secrets.address.equals(account.address, ignoreCase = true)) {
                    "Stored phrase does not match the wallet address"
                }
                return account.sign(draft.transaction)
            } finally {
                secret.fill(0)
            }
        }
    }
