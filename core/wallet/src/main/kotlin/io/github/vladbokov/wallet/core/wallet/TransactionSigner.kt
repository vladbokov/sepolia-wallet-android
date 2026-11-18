package io.github.vladbokov.wallet.core.wallet

import org.web3j.crypto.Credentials
import org.web3j.crypto.Hash
import org.web3j.crypto.RawTransaction
import org.web3j.crypto.TransactionEncoder
import org.web3j.utils.Numeric

internal object TransactionSigner {
    /**
     * RLP-encodes the transaction, signs keccak256 of it with secp256k1 (deterministic, RFC 6979)
     * and returns the signed payload together with its hash.
     * The chain id is part of the signed data, which prevents replay on other networks.
     */
    fun sign(
        transaction: UnsignedTransaction,
        credentials: Credentials,
    ): SignedTransaction {
        val raw =
            RawTransaction.createTransaction(
                transaction.chainId,
                transaction.nonce,
                transaction.fee.gasLimit,
                transaction.to,
                transaction.value,
                transaction.data,
                transaction.fee.maxPriorityFeePerGas,
                transaction.fee.maxFeePerGas,
            )
        val rawHex = Numeric.toHexString(TransactionEncoder.signMessage(raw, credentials))
        return SignedTransaction(rawHex = rawHex, hash = Hash.sha3(rawHex))
    }
}
