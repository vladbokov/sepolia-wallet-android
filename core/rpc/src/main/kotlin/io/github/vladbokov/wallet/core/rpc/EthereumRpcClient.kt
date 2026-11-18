package io.github.vladbokov.wallet.core.rpc

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import retrofit2.HttpException
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Typed wrapper over the Ethereum execution-layer JSON-RPC API.
 * Reads account state (balance, nonce), the inputs of an EIP-1559 fee (gas estimate, base fee, priority fee)
 * and performs read-only contract calls. Broadcasts signed transactions and tracks them by their receipt.
 *
 * Failures of the call itself are reported as [JsonRpcException]; transport failures stay `IOException`.
 */
class EthereumRpcClient(
    private val api: JsonRpcApi,
) {
    private val nextId = AtomicLong(1)

    /** @throws JsonRpcException.Malformed if the id does not fit in a [Long]. */
    suspend fun chainId(): Long {
        val id = quantity("eth_chainId")
        return try {
            id.longValueExact()
        } catch (e: ArithmeticException) {
            throw JsonRpcException.Malformed("Chain id in eth_chainId response does not fit in Long", e)
        }
    }

    /**
     * Balance in wei at the latest block.
     * @param address 20-byte account address, `0x` + 40 hex digits.
     * @throws IllegalArgumentException if [address] is not in that format.
     */
    suspend fun getBalance(address: String): BigInteger {
        requireAddress(address)
        return quantity("eth_getBalance", JsonPrimitive(address), JsonPrimitive("latest"))
    }

    /**
     * Number of transactions sent from [address], i.e. the nonce for its next transaction.
     * Counts transactions still in the mempool ("pending"), so two quick sends don't reuse a nonce.
     * @throws IllegalArgumentException if [address] is not a 20-byte hex address.
     */
    suspend fun getTransactionCount(address: String): BigInteger {
        requireAddress(address)
        return quantity("eth_getTransactionCount", JsonPrimitive(address), JsonPrimitive("pending"))
    }

    /**
     * Gas units the node expects a transfer of [value] wei from [from] to [to] to use.
     * This is an estimate for the current state: add a safety margin when building a real transaction,
     * since the state may change before it is included in a block.
     * @param data calldata as `0x`-prefixed hex, for contract calls such as an ERC-20 transfer.
     * @throws IllegalArgumentException if an address or [data] is malformed, or [value] is negative.
     * @throws JsonRpcException.Node if the node expects the transaction to fail (e.g. revert or insufficient funds).
     */
    suspend fun estimateGas(
        from: String,
        to: String,
        value: BigInteger,
        data: String? = null,
    ): BigInteger {
        requireAddress(from)
        requireAddress(to)
        require(data == null || HEX_DATA.matches(data)) { "Invalid calldata: '$data'" }
        return quantity(
            "eth_estimateGas",
            buildJsonObject {
                put("from", from)
                put("to", to)
                put("value", value.toHexQuantity())
                if (data != null) put("data", data)
            },
        )
    }

    /** Priority fee (tip) per gas, in wei, that the node suggests to get a transaction included soon. */
    suspend fun maxPriorityFeePerGas(): BigInteger = quantity("eth_maxPriorityFeePerGas")

    /** Base fee per gas of the latest block, in wei (EIP-1559). The next block's base fee moves at most 12.5%. */
    suspend fun latestBaseFee(): BigInteger {
        val block = required("eth_getBlockByNumber", JsonPrimitive("latest"), JsonPrimitive(false))
        if (block !is JsonObject) {
            throw JsonRpcException.Malformed("Expected a block object in eth_getBlockByNumber response")
        }
        val baseFee =
            block["baseFeePerGas"]
                ?: throw JsonRpcException.Malformed("Latest block has no baseFeePerGas (node without EIP-1559)")
        return parseQuantity("eth_getBlockByNumber", baseFee)
    }

    /**
     * Read-only contract call: the node executes it locally and returns the raw result as `0x`-prefixed hex
     * (`0x` when there is none). Nothing is signed or sent to the network.
     *
     * `0x` is also what a call to an address without contract code returns, so a caller that expects data
     * must treat an empty result as "no such contract or function", not as a valid value.
     * @throws IllegalArgumentException if [to] or [data] is malformed.
     * @throws JsonRpcException.Node if the call reverts; [JsonRpcException.Node.data] holds the revert data.
     */
    suspend fun call(
        to: String,
        data: String,
    ): String {
        requireAddress(to)
        require(HEX_DATA.matches(data)) { "Invalid calldata: '$data'" }
        val result =
            required(
                "eth_call",
                buildJsonObject {
                    put("to", to)
                    put("data", data)
                },
                JsonPrimitive("latest"),
            )
        return hexResult("eth_call", result, HEX_DATA, "hex data")
    }

    /**
     * Broadcasts a signed, RLP-encoded transaction and returns its hash. The node never sees the key:
     * it only receives bytes that are already signed.
     *
     * Not safe to blindly retry: if the first attempt reached the node but the response was lost (timeout),
     * a retry fails with "already known" or "nonce too low" although the transaction was broadcast.
     * After such an error check [getTransactionReceipt] with the hash computed from the signed bytes
     * before treating the send as failed.
     * @param signedTransactionHex `0x`-prefixed hex of the signed transaction.
     * @throws IllegalArgumentException if [signedTransactionHex] is not non-empty hex data.
     */
    suspend fun sendRawTransaction(signedTransactionHex: String): String {
        require(signedTransactionHex.length > 2 && HEX_DATA.matches(signedTransactionHex)) {
            "Invalid signed transaction hex"
        }
        val hash = required("eth_sendRawTransaction", JsonPrimitive(signedTransactionHex))
        return hexResult("eth_sendRawTransaction", hash, HASH, "a 32-byte hash")
    }

    /**
     * Receipt of a transaction, or null while the node does not know it in a block yet
     * (not mined, or still in the mempool).
     * @param transactionHash 32-byte hash, `0x` + 64 hex digits.
     * @throws IllegalArgumentException if [transactionHash] is not in that format.
     */
    suspend fun getTransactionReceipt(transactionHash: String): TransactionReceipt? {
        require(HASH.matches(transactionHash)) { "Invalid transaction hash: '$transactionHash'" }
        val receipt = request("eth_getTransactionReceipt", JsonPrimitive(transactionHash)) ?: return null
        if (receipt !is JsonObject) {
            throw JsonRpcException.Malformed("Expected a receipt object in eth_getTransactionReceipt response")
        }
        val status = parseQuantity("eth_getTransactionReceipt", receipt.field("status"))
        val blockNumber = parseQuantity("eth_getTransactionReceipt", receipt.field("blockNumber"))
        return when (status) {
            BigInteger.ONE -> TransactionReceipt(success = true, blockNumber = blockNumber)
            BigInteger.ZERO -> TransactionReceipt(success = false, blockNumber = blockNumber)
            else -> throw JsonRpcException.Malformed("Unknown receipt status $status")
        }
    }

    private suspend fun quantity(
        method: String,
        vararg params: JsonElement,
    ): BigInteger = parseQuantity(method, required(method, *params))

    private fun parseQuantity(
        method: String,
        element: JsonElement,
    ): BigInteger {
        // A JSON number like 5 would otherwise be read as the hex string "5" and silently give a wrong value.
        if (element !is JsonPrimitive || !element.isString) {
            throw JsonRpcException.Malformed("Expected a hex string in $method response")
        }
        return try {
            element.content.hexToBigInteger()
        } catch (e: IllegalArgumentException) {
            throw JsonRpcException.Malformed("Malformed quantity in $method response", e)
        }
    }

    private suspend fun required(
        method: String,
        vararg params: JsonElement,
    ): JsonElement = request(method, *params) ?: throw JsonRpcException.Malformed("Empty result for $method")

    private suspend fun request(
        method: String,
        vararg params: JsonElement,
    ): JsonElement? {
        val id = nextId.getAndIncrement()
        val response =
            try {
                api.call(JsonRpcRequest(method, JsonArray(params.toList()), id))
            } catch (e: HttpException) {
                throw JsonRpcException.Http(e.code(), e.nodeError(), e)
            } catch (e: SerializationException) {
                throw JsonRpcException.Malformed("Malformed response from RPC endpoint", e)
            }
        // A null id is allowed: nodes answer with it when they could not even parse the request.
        if (response.id != null && response.id != id) {
            throw JsonRpcException.Malformed("Response id ${response.id} does not match request id $id")
        }
        response.error?.let { throw JsonRpcException.Node(it.code, it.message, it.data) }
        return response.result?.takeUnless { it is JsonNull }
    }

    /** Providers often put a JSON-RPC error with an explanation into the body of a non-2xx response. */
    private fun HttpException.nodeError(): JsonRpcError? {
        val body = response()?.errorBody()?.string() ?: return null
        return try {
            RpcJson.decodeFromString(JsonRpcResponse.serializer(), body).error
        } catch (e: SerializationException) {
            null
        }
    }

    /** Reads a string result that must match [format]; [what] names the expected value in the error message. */
    private fun hexResult(
        method: String,
        element: JsonElement,
        format: Regex,
        what: String,
    ): String {
        if (element !is JsonPrimitive || !element.isString || !format.matches(element.content)) {
            throw JsonRpcException.Malformed("Expected $what in $method response")
        }
        return element.content
    }

    private fun JsonObject.field(name: String): JsonElement =
        this[name] ?: throw JsonRpcException.Malformed("Receipt has no $name")

    private fun requireAddress(address: String) {
        require(ADDRESS.matches(address)) { "Invalid address: '$address'" }
    }

    private companion object {
        val ADDRESS = Regex("0x[0-9a-fA-F]{40}")
        val HEX_DATA = Regex("0x([0-9a-fA-F]{2})*")
        val HASH = Regex("0x[0-9a-fA-F]{64}")
    }
}
