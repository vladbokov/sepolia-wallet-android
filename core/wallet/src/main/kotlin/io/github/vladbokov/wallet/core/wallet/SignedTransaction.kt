package io.github.vladbokov.wallet.core.wallet

/**
 * A signed transaction, ready for eth_sendRawTransaction.
 *
 * @property rawHex RLP-encoded signed payload, `0x`-prefixed.
 * @property hash keccak256 of the payload: the transaction id, known before the node answers.
 */
data class SignedTransaction(
    val rawHex: String,
    val hash: String,
)
