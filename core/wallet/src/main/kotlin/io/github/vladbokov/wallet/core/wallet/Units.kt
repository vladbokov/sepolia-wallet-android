package io.github.vladbokov.wallet.core.wallet

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/** Money is always integer base units (wei) inside the app; decimals exist only at the UI edge. */
object Units {
    const val ETH_DECIMALS = 18
    const val MAX_DECIMALS = 36
    const val MAX_INPUT_LENGTH = 100

    // Plain ASCII decimals with a '.' separator only: BigDecimal alone would also accept signs, exponents ("1e5")
    // and non-ASCII digits. A comma is rejected on purpose: "1,000" is "one thousand" in some locales and
    // "one" in others, so the UI layer must normalise locale separators before calling parse.
    private val AMOUNT = Regex("""\d+\.?\d*|\.\d+""")

    /**
     * Parses an amount typed by a user ("0.01") into base units.
     * Returns null for malformed or overlong input, negative amounts, a comma separator,
     * or more fraction digits than [decimals] allow.
     *
     * Zero is a valid result ("0", "0.0"): rejecting empty transfers is the caller's job.
     */
    fun parse(
        text: String,
        decimals: Int = ETH_DECIMALS,
    ): BigInteger? {
        require(decimals in 0..MAX_DECIMALS) { "decimals must be in 0..$MAX_DECIMALS" }
        val amount = text.trim()
        if (amount.length > MAX_INPUT_LENGTH || !AMOUNT.matches(amount)) return null
        return try {
            BigDecimal(amount).movePointRight(decimals).toBigIntegerExact()
        } catch (e: ArithmeticException) {
            // A fraction of the smallest unit cannot be represented.
            null
        }
    }

    /** Formats base units for display: no trailing zeros, rounded down to [maxFractionDigits]. */
    fun format(
        amount: BigInteger,
        decimals: Int = ETH_DECIMALS,
        maxFractionDigits: Int = 6,
    ): String {
        require(decimals in 0..MAX_DECIMALS && maxFractionDigits >= 0) {
            "decimals must be in 0..$MAX_DECIMALS and maxFractionDigits must not be negative"
        }
        val value = BigDecimal(amount).movePointLeft(decimals).setScale(maxFractionDigits, RoundingMode.DOWN)
        return if (value.signum() == 0) "0" else value.stripTrailingZeros().toPlainString()
    }
}
