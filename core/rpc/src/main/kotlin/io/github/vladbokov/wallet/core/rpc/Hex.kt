package io.github.vladbokov.wallet.core.rpc

import java.math.BigInteger

/**
 * Parses an Ethereum "quantity": hex digits with an optional `0x`/`0X` prefix.
 *
 * Quantities are unsigned and must contain at least one digit, so signs (`-5`, `+5`)
 * and the empty quantity (`0x`) are rejected with [IllegalArgumentException].
 */
fun String.hexToBigInteger(): BigInteger {
    val digits = removePrefix("0x").removePrefix("0X")
    require(digits.isNotEmpty() && digits.all { it.digitToIntOrNull(16) != null }) {
        "Invalid hex quantity: '$this'"
    }
    return BigInteger(digits, 16)
}

/** Encodes as an Ethereum "quantity": `0x`-prefixed hex without leading zeros (`0x0` for zero). */
fun BigInteger.toHexQuantity(): String {
    require(signum() >= 0) { "Quantities are unsigned" }
    return "0x" + toString(16)
}
