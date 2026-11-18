package io.github.vladbokov.wallet.core.wallet

import org.web3j.crypto.Keys

object Addresses {
    private val SHAPE = Regex("0x[0-9a-fA-F]{40}")

    /**
     * EIP-55: the letter case of a mixed-case address encodes a checksum, so a typo is caught
     * before funds go to a wrong address. All-lowercase / all-uppercase input carries no checksum.
     */
    fun isValid(address: String): Boolean {
        if (!SHAPE.matches(address)) return false
        val body = address.substring(2)
        val hasNoChecksum = body == body.lowercase() || body == body.uppercase()
        return hasNoChecksum || Keys.toChecksumAddress(address) == address
    }

    /**
     * Returns the EIP-55 checksummed form of an address that is [isValid]: all-lowercase / all-uppercase input
     * is normalized, mixed-case input must already carry a correct checksum. A mistyped mixed-case address is
     * rejected rather than silently "fixed", so this cannot be used to bypass the typo check.
     */
    fun toChecksum(address: String): String {
        require(isValid(address)) { "Not a valid 20-byte hex address" }
        return Keys.toChecksumAddress(address)
    }
}
