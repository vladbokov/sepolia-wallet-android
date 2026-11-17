package io.github.vladbokov.wallet.core.rpc

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import retrofit2.HttpException
import java.math.BigInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class JsonRpcApiTest {
    private val server = MockWebServer()

    @BeforeTest
    fun setUp() {
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.close()
    }

    private fun api(): JsonRpcApi = EthereumRpc.createApi(server.url("/v2/key").toString(), OkHttpClient())

    private fun enqueue(
        body: String,
        code: Int = 200,
    ) {
        server.enqueue(
            MockResponse
                .Builder()
                .code(code)
                .body(body)
                .build(),
        )
    }

    private fun request(method: String = "eth_getBalance") =
        JsonRpcRequest(
            method = method,
            params = JsonArray(listOf(JsonPrimitive("0xabc"), JsonPrimitive("latest"))),
            id = 7,
        )

    @Test
    fun `posts the request body to the url path`() =
        runTest {
            enqueue("""{"jsonrpc":"2.0","id":7,"result":"0x10"}""")

            api().call(request())

            val recorded = server.takeRequest()
            assertEquals("POST", recorded.method)
            assertEquals("/v2/key/", recorded.url.encodedPath)
            val body = RpcJson.parseToJsonElement(recorded.body!!.utf8()).jsonObject
            assertEquals("2.0", body.getValue("jsonrpc").jsonPrimitive.content)
            assertEquals("eth_getBalance", body.getValue("method").jsonPrimitive.content)
            assertEquals(
                7L,
                body
                    .getValue("id")
                    .jsonPrimitive.content
                    .toLong(),
            )
            assertEquals(2, body.getValue("params").jsonArray.size)
            assertEquals("application/json", recorded.headers["Content-Type"]?.substringBefore(";"))
        }

    @Test
    fun `parses successful result`() =
        runTest {
            enqueue("""{"jsonrpc":"2.0","id":7,"result":"0xde0b6b3a7640000"}""")

            val response = api().call(request())

            assertNull(response.error)
            val balance =
                response.result!!
                    .jsonPrimitive.content
                    .hexToBigInteger()
            assertEquals(BigInteger.TEN.pow(18), balance)
        }

    @Test
    fun `parses json-rpc error delivered with http 200`() =
        runTest {
            enqueue(
                """{"jsonrpc":"2.0","id":7,"error":{"code":3,"message":"execution reverted","data":"0x08c379a0"}}""",
            )

            val response = api().call(request("eth_call"))

            assertNull(response.result)
            val error = assertNotNull(response.error)
            assertEquals(3, error.code)
            assertEquals("execution reverted", error.message)
            assertEquals("0x08c379a0", error.data?.jsonPrimitive?.content)
        }

    @Test
    fun `null result is a success`() =
        runTest {
            enqueue("""{"jsonrpc":"2.0","id":7,"result":null}""")

            val response = api().call(request("eth_getTransactionReceipt"))

            assertNull(response.error)
            assertNull(response.result)
        }

    @Test
    fun `unknown response fields are ignored`() =
        runTest {
            enqueue("""{"jsonrpc":"2.0","id":7,"result":"0x1","node":"x","meta":{"a":1}}""")

            assertEquals(
                "0x1",
                api()
                    .call(request())
                    .result
                    ?.jsonPrimitive
                    ?.content,
            )
        }

    @Test
    fun `http error status throws HttpException`() =
        runTest {
            enqueue("""{"message":"boom"}""", code = 500)

            val failure = assertFailsWith<HttpException> { api().call(request()) }

            assertEquals(500, failure.code())
        }
}
