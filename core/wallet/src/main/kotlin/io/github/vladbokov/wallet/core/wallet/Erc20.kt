package io.github.vladbokov.wallet.core.wallet

import org.web3j.crypto.Hash
import java.math.BigInteger

/**
 * Hand-rolled ABI encoding for the two ERC-20 calls a wallet needs.
 * Call data = 4-byte function selector + arguments, each padded to a 32-byte word.
 */
object Erc20 {
    private val BALANCE_OF = selector("balanceOf(address)") // 0x70a08231
    private val TRANSFER = selector("transfer(address,uint256)") // 0xa9059cbb

    /** Use with eth_call to read a token balance. */
    fun balanceOfCallData(owner: String): String = BALANCE_OF + addressWord(owner)

    /** Goes into the `data` field of a transaction sent to the token contract (value = 0). */
    fun transferCallData(
        to: String,
        amount: BigInteger,
    ): String = TRANSFER + addressWord(to) + uintWord(amount)

    /** First 4 bytes of keccak256 of the canonical function signature. */
    private fun selector(signature: String): String {
        val hash = Hash.sha3(signature.toByteArray(Charsets.US_ASCII))
        return "0x" + hash.take(4).joinToString("") { "%02x".format(it) }
    }

    private fun addressWord(address: String): String {
        require(Addresses.isValid(address)) { "Not a valid address" }
        return address.removePrefix("0x").lowercase().padStart(64, '0')
    }

    private fun uintWord(value: BigInteger): String {
        require(value.signum() >= 0 && value.bitLength() <= 256) { "Value does not fit uint256" }
        return value.toString(16).padStart(64, '0')
    }
}
