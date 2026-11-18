package io.github.vladbokov.wallet.core.wallet

import java.math.BigInteger

/**
 * EIP-1559 fee parameters of a transaction: actual price per gas = baseFee (burned) + tip (to the validator),
 * where the tip is capped by [maxPriorityFeePerGas] and the whole price by [maxFeePerGas].
 */
data class FeeQuote(
    val gasLimit: BigInteger,
    val maxPriorityFeePerGas: BigInteger,
    val maxFeePerGas: BigInteger,
) {
    init {
        require(gasLimit.signum() > 0) { "Gas limit must be positive" }
        require(maxPriorityFeePerGas.signum() >= 0) { "maxPriorityFeePerGas must not be negative" }
        // The protocol rejects a transaction whose tip cap exceeds its total price cap.
        require(maxFeePerGas >= maxPriorityFeePerGas) { "maxFeePerGas must cover maxPriorityFeePerGas" }
    }

    /**
     * Upper bound of the fee (the transferred value is not included); the unused part is refunded.
     * It is computed from the buffered [gasLimit], so it overstates what a plain transfer really costs:
     * a "send max" calculation should use the estimated gas, not this value.
     */
    val maxTotalFee: BigInteger get() = gasLimit * maxFeePerGas

    companion object {
        private val TWO = BigInteger.valueOf(2)
        private val HUNDRED = BigInteger.valueOf(100)
        private val GAS_BUFFER_PERCENT = BigInteger.valueOf(120)

        /** Sanity bound against a faulty or malicious RPC node: well above any real block gas limit. */
        val MAX_ESTIMATED_GAS: BigInteger = BigInteger.valueOf(30_000_000)

        /** Sanity bound for base and priority fees: 10 000 gwei per gas. */
        val MAX_FEE_PER_GAS: BigInteger = BigInteger.TEN.pow(13)

        /**
         * maxFeePerGas = 2 * baseFee + tip. The base fee grows at most 12.5% per full block, so doubling it keeps
         * the transaction valid for about six consecutive full blocks (1.125^6 is roughly 2.03).
         * The gas limit gets a 20% safety buffer, rounded up.
         *
         * @throws IllegalArgumentException if [estimatedGas] is not positive or above [MAX_ESTIMATED_GAS],
         * or a fee is negative or above [MAX_FEE_PER_GAS].
         */
        fun estimate(
            estimatedGas: BigInteger,
            baseFee: BigInteger,
            priorityFee: BigInteger,
        ): FeeQuote {
            require(estimatedGas.signum() > 0 && estimatedGas <= MAX_ESTIMATED_GAS) {
                "Estimated gas must be in 1..$MAX_ESTIMATED_GAS"
            }
            require(baseFee in BigInteger.ZERO..MAX_FEE_PER_GAS && priorityFee in BigInteger.ZERO..MAX_FEE_PER_GAS) {
                "Fees must be in 0..$MAX_FEE_PER_GAS"
            }
            return FeeQuote(
                gasLimit = (estimatedGas * GAS_BUFFER_PERCENT + HUNDRED - BigInteger.ONE) / HUNDRED,
                maxPriorityFeePerGas = priorityFee,
                maxFeePerGas = baseFee * TWO + priorityFee,
            )
        }
    }
}
