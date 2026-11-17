package io.github.vladbokov.wallet.core.rpc

import java.math.BigInteger

/** Outcome of a transaction that has been included in a block. */
data class TransactionReceipt(
    /** False when the transaction was included but reverted (it still consumed gas). */
    val success: Boolean,
    val blockNumber: BigInteger,
)
