package io.github.vladbokov.wallet.core.wallet

import java.math.BigInteger

/**
 * EIP-1559 (type 2) transaction before signing. All amounts are in wei.
 * Contract creation (no recipient) is not supported: a wallet only sends transfers and contract calls.
 *
 * @property data calldata as `0x`-prefixed hex; `0x` for a plain transfer.
 */
data class UnsignedTransaction(
    val chainId: Long,
    val nonce: BigInteger,
    val to: String,
    val value: BigInteger,
    val fee: FeeQuote,
    val data: String = "0x",
) {
    init {
        require(chainId > 0) { "Chain id must be positive" }
        require(nonce.signum() >= 0 && nonce.bitLength() <= 64) { "Nonce must fit in uint64" }
        require(Addresses.isValid(to)) { "Recipient is not a valid address" }
        require(value.signum() >= 0 && value.bitLength() <= 256) { "Value must fit in uint256" }
        require(isWholeByteHex(data)) { "Calldata must be 0x-prefixed hex with whole bytes" }
    }

    private companion object {
        // A loop instead of a regex: a repeated regex group recurses and overflows the stack on long calldata.
        fun isWholeByteHex(data: String): Boolean =
            data.startsWith("0x") && data.length % 2 == 0 && (2 until data.length).all { data[it].isHexDigit() }

        fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
    }
}
