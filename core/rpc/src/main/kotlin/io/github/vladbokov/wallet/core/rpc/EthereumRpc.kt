package io.github.vladbokov.wallet.core.rpc

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Wires Retrofit to a JSON-RPC endpoint (e.g. a Sepolia node URL with an API key in its path). */
object EthereumRpc {

    /** Typed client on top of [createApi]; same [rpcUrl] rules and exceptions. */
    fun create(rpcUrl: String, okHttpClient: OkHttpClient): EthereumRpcClient =
        EthereumRpcClient(createApi(rpcUrl, okHttpClient))

    /**
     * @param rpcUrl node URL; an API key must be part of the path (".../v2/<key>"), not of the query string.
     * @throws IllegalArgumentException if [rpcUrl] is not a valid http(s) URL or has a query string.
     */
    fun createApi(rpcUrl: String, okHttpClient: OkHttpClient): JsonRpcApi =
        Retrofit.Builder()
            .baseUrl(rpcUrl.toRpcBaseUrl())
            .client(okHttpClient)
            .addConverterFactory(RpcJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(JsonRpcApi::class.java)
}

/**
 * Retrofit requires a base URL ending in "/" and resolves @POST(".") against it, so a provider URL with a path
 * (".../v2/<key>") is posted to as-is. Resolving a relative path drops the base URL's query, so a query string
 * (e.g. "?key=...") would be silently lost: fail fast instead.
 */
internal fun String.toRpcBaseUrl(): HttpUrl {
    val url = toHttpUrl()
    require(url.query == null) { "RPC URL must not contain a query string; put the API key in the path" }
    return if (url.encodedPath.endsWith("/")) url else url.newBuilder().addPathSegment("").build()
}
