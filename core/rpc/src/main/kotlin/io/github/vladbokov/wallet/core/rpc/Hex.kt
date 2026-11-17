package io.github.vladbokov.wallet.core.rpc

import java.math.BigInteger

/**
 * Parses an Ethereum "quantity": hex digits with an optional `0x`/`0X` prefix.
 *
 * Quantities are unsigned and must contain at least one digit, so signs (`-5`, `+5`)
 * and the empty quantity (`0x`) are rejected with [IllegalArgumentException].
 */
fun String.hexToBigInteger(): BigInteger {
    val digits = if (startsWith("0x") || startsWith("0X")) substring(2) else this
    require(digits.isNotEmpty() && digits.all(::isAsciiHexDigit)) {
        "Invalid hex quantity: '$this'"
    }
    return BigInteger(digits, 16)
}

private fun isAsciiHexDigit(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

/** Encodes as an Ethereum "quantity": `0x`-prefixed hex without leading zeros (`0x0` for zero). */
fun BigInteger.toHexQuantity(): String {
    require(signum() >= 0) { "Quantities are unsigned" }
    return "0x" + toString(16)
}
