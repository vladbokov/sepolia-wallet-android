package io.github.vladbokov.wallet.domain.usecase

import io.github.vladbokov.wallet.core.wallet.Addresses
import io.github.vladbokov.wallet.core.wallet.FeeQuote
import io.github.vladbokov.wallet.core.wallet.Units
import io.github.vladbokov.wallet.core.wallet.UnsignedTransaction
import io.github.vladbokov.wallet.domain.model.ChainConfig
import io.github.vladbokov.wallet.domain.model.DataError
import io.github.vladbokov.wallet.domain.model.DraftResult
import io.github.vladbokov.wallet.domain.model.TransferDraft
import io.github.vladbokov.wallet.domain.model.TransferError
import io.github.vladbokov.wallet.domain.repository.WalletRepository
import javax.inject.Inject

/**
 * Validates user input against chain state and produces the exact transaction the user will sign.
 * No key material is involved at this stage, so it runs without a biometric prompt.
 *
 * Invalid input or a balance that is too low comes back as [DraftResult.Failure]. Infrastructure failures
 * ([DataError]) are not caught here: the caller handles them in one place.
 *
 * The chain id is compared after [WalletRepository.transferContext], which reads the chain state in one go. On an
 * endpoint of a different network one of those reads (for example `eth_estimateGas`) may therefore fail first, and
 * the caller sees that [DataError] instead of [TransferError.WrongNetwork]. Nothing is signed in that case.
 */
class PrepareTransferUseCase
    @Inject
    constructor(
        private val repository: WalletRepository,
        private val chain: ChainConfig,
    ) {
        /** @throws DataError if the node cannot be reached, or answers with values that cannot be a valid fee or nonce. */
        suspend operator fun invoke(
            from: String,
            toInput: String,
            amountInput: String,
        ): DraftResult {
            val to = toInput.trim()
            if (!Addresses.isValid(to)) return DraftResult.Failure(TransferError.InvalidAddress)

            val value = Units.parse(amountInput)
            if (value == null || value.signum() <= 0) return DraftResult.Failure(TransferError.InvalidAmount)

            val context = repository.transferContext(from, to, value)
            // Guards against a misconfigured or malicious endpoint: we would otherwise sign for a chain
            // the user did not intend.
            if (context.chainId != chain.chainId) {
                return DraftResult.Failure(TransferError.WrongNetwork(chain.chainId, context.chainId))
            }

            // Everything below the node reported is checked explicitly, so that only a faulty node ends up as
            // MalformedResponse. A failed `require` anywhere else is a bug in our code and must stay visible.
            if (context.nonce.signum() < 0 || context.nonce.bitLength() > MAX_NONCE_BITS) {
                throw DataError.MalformedResponse()
            }
            if (context.balance.signum() < 0 || context.balance.bitLength() > MAX_UINT_BITS) {
                throw DataError.MalformedResponse()
            }
            val fee =
                try {
                    FeeQuote.estimate(context.estimatedGas, context.baseFee, context.priorityFee)
                } catch (e: IllegalArgumentException) {
                    throw DataError.MalformedResponse(e)
                }

            if (context.balance < value + fee.maxTotalFee) return DraftResult.Failure(TransferError.InsufficientFunds)

            val transaction =
                UnsignedTransaction(
                    chainId = chain.chainId,
                    nonce = context.nonce,
                    to = to,
                    value = value,
                    fee = fee,
                )
            return DraftResult.Success(TransferDraft(transaction))
        }

        private companion object {
            const val MAX_NONCE_BITS = 64
            const val MAX_UINT_BITS = 256
        }
    }
