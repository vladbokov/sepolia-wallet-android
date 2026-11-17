package io.github.vladbokov.wallet.core.rpc

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class JsonRpcModelsTest {

    @Test
    fun `request always contains jsonrpc 2_0`() {
        val request = JsonRpcRequest(
            method = "eth_getBalance",
            params = JsonArray(listOf(JsonPrimitive("0xabc"), JsonPrimitive("latest"))),
            id = 1,
        )

        val json = RpcJson.parseToJsonElement(RpcJson.encodeToString(request)).jsonObject

        assertEquals("2.0", json.getValue("jsonrpc").jsonPrimitive.content)
        assertEquals("eth_getBalance", json.getValue("method").jsonPrimitive.content)
        assertEquals(1L, json.getValue("id").jsonPrimitive.content.toLong())
    }

    @Test
    fun `parses successful response`() {
        val response = RpcJson.decodeFromString<JsonRpcResponse>(
            """{"jsonrpc":"2.0","id":1,"result":"0x10"}""",
        )

        assertNull(response.error)
        assertEquals("0x10", response.result?.jsonPrimitive?.content)
    }

    @Test
    fun `parses null result as success`() {
        val response = RpcJson.decodeFromString<JsonRpcResponse>(
            """{"jsonrpc":"2.0","id":2,"result":null}""",
        )

        assertNull(response.error)
        assertNull(response.result)
    }

    @Test
    fun `parses error with revert data and unknown fields`() {
        val response = RpcJson.decodeFromString<JsonRpcResponse>(
            """{"jsonrpc":"2.0","id":3,"extra":true,
               "error":{"code":3,"message":"execution reverted","data":"0x08c379a0"}}""",
        )

        val error = assertNotNull(response.error)
        assertEquals(3, error.code)
        assertEquals("execution reverted", error.message)
        assertEquals("0x08c379a0", error.data?.jsonPrimitive?.content)
    }
}
