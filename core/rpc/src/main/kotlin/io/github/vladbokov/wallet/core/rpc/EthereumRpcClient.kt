package io.github.vladbokov.wallet.core.rpc

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.HttpException
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Typed wrapper over the Ethereum execution-layer JSON-RPC API.
 * Currently reads the chain id and account balances.
 *
 * Failures of the call itself are reported as [JsonRpcException]; transport failures stay `IOException`.
 */
class EthereumRpcClient(private val api: JsonRpcApi) {

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
        require(ADDRESS.matches(address)) { "Invalid address: '$address'" }
        return quantity("eth_getBalance", JsonPrimitive(address), JsonPrimitive("latest"))
    }

    private suspend fun quantity(method: String, vararg params: JsonElement): BigInteger {
        val result = required(method, *params)
        // A JSON number like 5 would otherwise be read as the hex string "5" and silently give a wrong value.
        if (result !is JsonPrimitive || !result.isString) {
            throw JsonRpcException.Malformed("Expected a hex string in $method response")
        }
        return try {
            result.content.hexToBigInteger()
        } catch (e: IllegalArgumentException) {
            throw JsonRpcException.Malformed("Malformed quantity in $method response", e)
        }
    }

    private suspend fun required(method: String, vararg params: JsonElement): JsonElement =
        request(method, *params) ?: throw JsonRpcException.Malformed("Empty result for $method")

    private suspend fun request(method: String, vararg params: JsonElement): JsonElement? {
        val id = nextId.getAndIncrement()
        val response = try {
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

    private companion object {
        val ADDRESS = Regex("0x[0-9a-fA-F]{40}")
    }
}
