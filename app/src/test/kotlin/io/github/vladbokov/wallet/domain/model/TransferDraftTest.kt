package io.github.vladbokov.wallet.domain.model

import io.github.vladbokov.wallet.core.wallet.FeeQuote
import io.github.vladbokov.wallet.core.wallet.UnsignedTransaction
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TransferDraftTest {
    private val recipient = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8"
    private val fee =
        FeeQuote(
            gasLimit = BigInteger.valueOf(21_000),
            maxPriorityFeePerGas = BigInteger.valueOf(1_500_000_000),
            maxFeePerGas = BigInteger.valueOf(30_000_000_000),
        )

    private fun transaction(data: String = "0x") =
        UnsignedTransaction(
            chainId = 11155111,
            nonce = BigInteger.ZERO,
            to = recipient,
            value = BigInteger.ONE,
            fee = fee,
            data = data,
        )

    @Test
    fun `a plain ETH transfer is accepted`() {
        val draft = TransferDraft(transaction())

        assertEquals(recipient, draft.transaction.to)
    }

    @Test
    fun `calldata is rejected so the confirmation cannot differ from what is signed`() {
        // An ERC-20 transfer(address,uint256) selector: `to` would be the token contract, not the real recipient.
        assertFailsWith<IllegalArgumentException> {
            TransferDraft(transaction(data = "0xa9059cbb"))
        }
    }

    @Test
    fun `max fee is the fee quote upper bound`() {
        assertEquals(fee.maxTotalFee, TransferDraft(transaction()).maxFee)
    }
}
