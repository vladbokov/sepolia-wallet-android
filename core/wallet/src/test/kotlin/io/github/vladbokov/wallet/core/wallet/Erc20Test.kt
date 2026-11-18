package io.github.vladbokov.wallet.core.wallet

import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.crypto.SignedRawTransaction
import org.web3j.crypto.TransactionDecoder
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Erc20Test {
    private val owner = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266"
    private val recipient = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8"
    private val maxUint256 = BigInteger.TWO.pow(256) - BigInteger.ONE

    @Test
    fun `erc20 call data is selector plus 32-byte words`() {
        assertEquals(
            "0x70a08231000000000000000000000000f39fd6e51aad88f6f4ce6ab8827279cfffb92266",
            Erc20.balanceOfCallData(owner),
        )
        assertEquals(
            "0xa9059cbb00000000000000000000000070997970c51812dc3a010c7d01b50e0d17dc79c8" +
                "00000000000000000000000000000000000000000000000000000000000003e8",
            Erc20.transferCallData(recipient, BigInteger.valueOf(1000)),
        )
    }

    @Test
    fun `balanceOf matches the web3j reference encoder`() {
        val reference = FunctionEncoder.encode(Function("balanceOf", listOf(Address(owner)), emptyList()))

        assertEquals(reference, Erc20.balanceOfCallData(owner))
    }

    @Test
    fun `transfer matches the web3j reference encoder for boundary amounts`() {
        listOf(BigInteger.ZERO, BigInteger.ONE, BigInteger.TEN.pow(18), BigInteger.valueOf(Long.MAX_VALUE), maxUint256)
            .forEach { amount ->
                val reference =
                    FunctionEncoder.encode(
                        Function("transfer", listOf(Address(recipient), Uint256(amount)), emptyList()),
                    )

                assertEquals(reference, Erc20.transferCallData(recipient, amount), "amount $amount")
            }
    }

    @Test
    fun `the case of the address does not change the call data`() {
        assertEquals(
            Erc20.balanceOfCallData(owner),
            Erc20.balanceOfCallData(owner.lowercase()),
        )
    }

    @Test
    fun `amounts outside uint256 are rejected`() {
        assertFailsWith<IllegalArgumentException> { Erc20.transferCallData(recipient, BigInteger.valueOf(-1)) }
        assertFailsWith<IllegalArgumentException> { Erc20.transferCallData(recipient, maxUint256 + BigInteger.ONE) }
    }

    @Test
    fun `invalid addresses are rejected`() {
        val invalid =
            listOf(
                "",
                "0x1234",
                owner.removePrefix("0x"),
                "0x" + "g".repeat(40),
                "0xf39fd6e51aad88F6F4ce6aB8827279cffFb92266", // mixed case with a broken EIP-55 checksum
            )

        invalid.forEach { address ->
            assertFailsWith<IllegalArgumentException>(address) { Erc20.balanceOfCallData(address) }
            assertFailsWith<IllegalArgumentException>(address) { Erc20.transferCallData(address, BigInteger.ONE) }
        }
    }

    @Test
    fun `transfer call data is signed as the data of a token transaction`() {
        val tokenContract = "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB" // stands in for a token address
        val callData = Erc20.transferCallData(recipient, BigInteger.valueOf(1000))
        val transaction =
            UnsignedTransaction(
                chainId = 11155111,
                nonce = BigInteger.ZERO,
                to = tokenContract,
                value = BigInteger.ZERO,
                fee = FeeQuote(BigInteger.valueOf(60_000), BigInteger.ONE, BigInteger.TEN),
                data = callData,
            )

        val decoded = TransactionDecoder.decode(signer.sign(transaction).rawHex) as SignedRawTransaction

        assertEquals(callData, "0x" + decoded.data.removePrefix("0x"))
        assertEquals(tokenContract.lowercase(), decoded.to.lowercase())
        assertEquals(BigInteger.ZERO, decoded.value)
    }

    private companion object {
        // Derived once: PBKDF2 is slow and JUnit creates a new test instance per method.
        val signer =
            Account.fromMnemonic(
                checkNotNull(Mnemonic.parse("test test test test test test test test test test test junk")),
            )
    }
}
