package io.github.vladbokov.wallet.core.rpc

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import java.io.IOException
import java.math.BigInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class EthereumRpcClientTest {

    private val server = MockWebServer()
    private lateinit var client: EthereumRpcClient

    @BeforeTest
    fun setUp() {
        server.start()
        client = EthereumRpc.create(server.url("/v2/key").toString(), OkHttpClient())
    }

    @AfterTest
    fun tearDown() {
        server.close()
    }

    private fun enqueue(body: String, code: Int = 200) {
        server.enqueue(MockResponse.Builder().code(code).body(body).build())
    }

    private fun result(value: String, id: Int = 1) =
        enqueue("""{"jsonrpc":"2.0","id":$id,"result":"$value"}""")

    private fun takeBody() = RpcJson.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject

    @Test
    fun `chainId parses the hex quantity`() = runTest {
        result("0xaa36a7")

        assertEquals(11155111L, client.chainId())
        assertEquals("eth_chainId", takeBody().getValue("method").jsonPrimitive.content)
    }

    @Test
    fun `chainId that does not fit in Long is malformed`() = runTest {
        result("0x" + "f".repeat(20))

        val e = assertFailsWith<JsonRpcException.Malformed> { client.chainId() }

        assertIs<ArithmeticException>(e.cause)
    }

    @Test
    fun `getBalance sends address and latest block and returns wei`() = runTest {
        result("0x56bc75e2d63100000") // 100 ETH, more than Long.MAX_VALUE wei

        val balance = client.getBalance(ADDRESS)

        assertEquals(BigInteger.TEN.pow(20), balance)
        val params = takeBody().getValue("params").jsonArray
        assertEquals(listOf(ADDRESS, "latest"), params.map { it.jsonPrimitive.content })
    }

    @Test
    fun `getBalance rejects an invalid address without calling the node`() = runTest {
        assertFailsWith<IllegalArgumentException> { client.getBalance("0xabc") }
        assertFailsWith<IllegalArgumentException> { client.getBalance("0x" + "g".repeat(40)) }

        assertEquals(0, server.requestCount)
    }

    @Test
    fun `request ids increase with every call`() = runTest {
        result("0x1", id = 1)
        result("0x2", id = 2)

        client.chainId()
        client.chainId()

        assertEquals(1L, takeBody().getValue("id").jsonPrimitive.content.toLong())
        assertEquals(2L, takeBody().getValue("id").jsonPrimitive.content.toLong())
    }

    @Test
    fun `response with another id is malformed`() = runTest {
        result("0x1", id = 99)

        assertFailsWith<JsonRpcException.Malformed> { client.chainId() }
    }

    @Test
    fun `node error keeps code, message and data`() = runTest {
        enqueue(
            """{"jsonrpc":"2.0","id":1,"error":{"code":3,"message":"execution reverted","data":"0x08c379a0"}}""",
        )

        val e = assertFailsWith<JsonRpcException.Node> { client.chainId() }

        assertEquals(3, e.code)
        assertEquals("execution reverted", e.message)
        assertEquals("0x08c379a0", e.data?.jsonPrimitive?.content)
    }

    @Test
    fun `http error keeps the status`() = runTest {
        enqueue("rate limited", code = 429)

        val e = assertFailsWith<JsonRpcException.Http> { client.chainId() }

        assertEquals(429, e.status)
        assertEquals(null, e.error)
        assertNotNull(e.cause)
    }

    @Test
    fun `http error body with a json-rpc error is exposed`() = runTest {
        enqueue("""{"jsonrpc":"2.0","id":null,"error":{"code":-32005,"message":"daily limit reached"}}""", code = 429)

        val e = assertFailsWith<JsonRpcException.Http> { client.chainId() }

        assertEquals(-32005, e.error?.code)
        assertEquals("HTTP 429: daily limit reached", e.message)
    }

    @Test
    fun `non-json body is malformed and keeps its cause`() = runTest {
        enqueue("<html>bad gateway</html>")

        val e = assertFailsWith<JsonRpcException.Malformed> { client.chainId() }

        assertNotNull(e.cause)
    }

    @Test
    fun `null result is malformed`() = runTest {
        enqueue("""{"jsonrpc":"2.0","id":1,"result":null}""")

        val e = assertFailsWith<JsonRpcException.Malformed> { client.getBalance(ADDRESS) }

        assertEquals("Empty result for eth_getBalance", e.message)
    }

    @Test
    fun `non-string result is malformed`() = runTest {
        enqueue("""{"jsonrpc":"2.0","id":1,"result":5}""")
        enqueue("""{"jsonrpc":"2.0","id":2,"result":{"a":1}}""")

        assertFailsWith<JsonRpcException.Malformed> { client.chainId() }
        assertFailsWith<JsonRpcException.Malformed> { client.chainId() }
    }

    @Test
    fun `malformed quantity is malformed and keeps its cause`() = runTest {
        result("0xzz")

        val e = assertFailsWith<JsonRpcException.Malformed> { client.getBalance(ADDRESS) }

        assertIs<IllegalArgumentException>(e.cause)
    }

    @Test
    fun `network failure propagates as IOException`() = runTest {
        server.close()

        assertFailsWith<IOException> { client.chainId() }
    }

    private companion object {
        const val ADDRESS = "0x0123456789abcdef0123456789abcdef01234567"
    }
}
