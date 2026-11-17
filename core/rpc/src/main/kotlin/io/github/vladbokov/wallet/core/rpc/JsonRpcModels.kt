package io.github.vladbokov.wallet.core.rpc

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/** Json configuration for talking to nodes: they add fields we don't model (e.g. extra error details). */
val RpcJson: Json = Json { ignoreUnknownKeys = true }

/** JSON-RPC 2.0 request envelope. Every Ethereum node call is a POST with this body. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class JsonRpcRequest(
    val method: String,
    val params: JsonArray,
    val id: Long,
    // Must always be encoded: by default kotlinx.serialization skips fields equal to their default.
    @EncodeDefault val jsonRpc: String = "2.0",
)

/**
 * JSON-RPC 2.0 response envelope. The call succeeded when [error] is null.
 * A successful call may still have a null [result] (e.g. a receipt of a transaction that is not mined yet).
 */
@Serializable
data class JsonRpcResponse(
    val id: Long? = null,
    val jsonRpc: String? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null,
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    /** Optional extra details, e.g. the revert data of a failed eth_call. */
    val data: JsonElement? = null,
)
