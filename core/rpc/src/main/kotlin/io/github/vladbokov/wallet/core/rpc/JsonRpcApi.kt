package io.github.vladbokov.wallet.core.rpc

import retrofit2.http.Body
import retrofit2.http.POST

/** JSON-RPC has a single endpoint: the method name travels in the body, not in the URL. */
interface JsonRpcApi {
    @POST(".")
    suspend fun call(@Body request: JsonRpcRequest): JsonRpcResponse
}
