package io.github.vladbokov.wallet.core.rpc

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.boolean
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
import kotlin.test.assertNull

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

    private fun result(
        value: String,
        id: Int = 1,
    ) = enqueue("""{"jsonrpc":"2.0","id":$id,"result":"$value"}""")

    private fun takeBody() = RpcJson.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject

    @Test
    fun `chainId parses the hex quantity`() =
        runTest {
            result("0xaa36a7")

            assertEquals(11155111L, client.chainId())
            assertEquals("eth_chainId", takeBody().getValue("method").jsonPrimitive.content)
        }

    @Test
    fun `chainId that does not fit in Long is malformed`() =
        runTest {
            result("0x" + "f".repeat(20))

            val e = assertFailsWith<JsonRpcException.Malformed> { client.chainId() }

            assertIs<ArithmeticException>(e.cause)
        }

    @Test
    fun `getBalance sends address and latest block and returns wei`() =
        runTest {
            result("0x56bc75e2d63100000") // 100 ETH, more than Long.MAX_VALUE wei

            val balance = client.getBalance(ADDRESS)

            assertEquals(BigInteger.TEN.pow(20), balance)
            val params = takeBody().getValue("params").jsonArray
            assertEquals(listOf(ADDRESS, "latest"), params.map { it.jsonPrimitive.content })
        }

    @Test
    fun `getBalance rejects an invalid address without calling the node`() =
        runTest {
            assertFailsWith<IllegalArgumentException> { client.getBalance("0xabc") }
            assertFailsWith<IllegalArgumentException> { client.getBalance("0x" + "g".repeat(40)) }

            assertEquals(0, server.requestCount)
        }

    @Test
    fun `request ids increase with every call`() =
        runTest {
            result("0x1", id = 1)
            result("0x2", id = 2)

            client.chainId()
            client.chainId()

            assertEquals(
                1L,
                takeBody()
                    .getValue("id")
                    .jsonPrimitive.content
                    .toLong(),
            )
            assertEquals(
                2L,
                takeBody()
                    .getValue("id")
                    .jsonPrimitive.content
                    .toLong(),
            )
        }

    @Test
    fun `response with another id is malformed`() =
        runTest {
            result("0x1", id = 99)

            assertFailsWith<JsonRpcException.Malformed> { client.chainId() }
        }

    @Test
    fun `node error keeps code, message and data`() =
        runTest {
            enqueue(
                """{"jsonrpc":"2.0","id":1,"error":{"code":3,"message":"execution reverted","data":"0x08c379a0"}}""",
            )

            val e = assertFailsWith<JsonRpcException.Node> { client.chainId() }

            assertEquals(3, e.code)
            assertEquals("execution reverted", e.message)
            assertEquals("0x08c379a0", e.data?.jsonPrimitive?.content)
        }

    @Test
    fun `http error keeps the status`() =
        runTest {
            enqueue("rate limited", code = 429)

            val e = assertFailsWith<JsonRpcException.Http> { client.chainId() }

            assertEquals(429, e.status)
            assertEquals(null, e.error)
            assertNotNull(e.cause)
        }

    @Test
    fun `http error body with a json-rpc error is exposed`() =
        runTest {
            enqueue(
                """{"jsonrpc":"2.0","id":null,"error":{"code":-32005,"message":"daily limit reached"}}""",
                code = 429,
            )

            val e = assertFailsWith<JsonRpcException.Http> { client.chainId() }

            assertEquals(-32005, e.error?.code)
            assertEquals("HTTP 429: daily limit reached", e.message)
        }

    @Test
    fun `non-json body is malformed and keeps its cause`() =
        runTest {
            enqueue("<html>bad gateway</html>")

            val e = assertFailsWith<JsonRpcException.Malformed> { client.chainId() }

            assertNotNull(e.cause)
        }

    @Test
    fun `null result is malformed`() =
        runTest {
            enqueue("""{"jsonrpc":"2.0","id":1,"result":null}""")

            val e = assertFailsWith<JsonRpcException.Malformed> { client.getBalance(ADDRESS) }

            assertEquals("Empty result for eth_getBalance", e.message)
        }

    @Test
    fun `non-string result is malformed`() =
        runTest {
            enqueue("""{"jsonrpc":"2.0","id":1,"result":5}""")
            enqueue("""{"jsonrpc":"2.0","id":2,"result":{"a":1}}""")

            assertFailsWith<JsonRpcException.Malformed> { client.chainId() }
            assertFailsWith<JsonRpcException.Malformed> { client.chainId() }
        }

    @Test
    fun `malformed quantity is malformed and keeps its cause`() =
        runTest {
            result("0xzz")

            val e = assertFailsWith<JsonRpcException.Malformed> { client.getBalance(ADDRESS) }

            assertIs<IllegalArgumentException>(e.cause)
        }

    @Test
    fun `network failure propagates as IOException`() =
        runTest {
            server.close()

            assertFailsWith<IOException> { client.chainId() }
        }

    @Test
    fun `getTransactionCount asks for the pending block`() =
        runTest {
            result("0x7")

            assertEquals(BigInteger.valueOf(7), client.getTransactionCount(ADDRESS))

            val body = takeBody()
            assertEquals("eth_getTransactionCount", body.getValue("method").jsonPrimitive.content)
            assertEquals(listOf(ADDRESS, "pending"), body.getValue("params").jsonArray.map { it.jsonPrimitive.content })
        }

    @Test
    fun `getTransactionCount rejects an invalid address without calling the node`() =
        runTest {
            assertFailsWith<IllegalArgumentException> { client.getTransactionCount("0x1") }

            assertEquals(0, server.requestCount)
        }

    @Test
    fun `estimateGas sends a call object with hex value`() =
        runTest {
            result("0x5208")

            val gas = client.estimateGas(ADDRESS, OTHER_ADDRESS, BigInteger.TEN.pow(18))

            assertEquals(BigInteger.valueOf(21_000), gas)
            val call =
                takeBody()
                    .getValue("params")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals(ADDRESS, call.getValue("from").jsonPrimitive.content)
            assertEquals(OTHER_ADDRESS, call.getValue("to").jsonPrimitive.content)
            assertEquals("0xde0b6b3a7640000", call.getValue("value").jsonPrimitive.content)
            assertNull(call["data"])
        }

    @Test
    fun `estimateGas includes calldata when given`() =
        runTest {
            result("0xd6d8")

            client.estimateGas(ADDRESS, OTHER_ADDRESS, BigInteger.ZERO, data = "0xa9059cbb")

            val call =
                takeBody()
                    .getValue("params")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("0xa9059cbb", call.getValue("data").jsonPrimitive.content)
        }

    @Test
    fun `estimateGas accepts empty calldata`() =
        runTest {
            result("0x5208")

            client.estimateGas(ADDRESS, OTHER_ADDRESS, BigInteger.ONE, data = "0x")

            val call =
                takeBody()
                    .getValue("params")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("0x", call.getValue("data").jsonPrimitive.content)
        }

    @Test
    fun `estimateGas rejects malformed input without calling the node`() =
        runTest {
            assertFailsWith<IllegalArgumentException> { client.estimateGas("0x1", OTHER_ADDRESS, BigInteger.ONE) }
            assertFailsWith<IllegalArgumentException> { client.estimateGas(ADDRESS, "0x1", BigInteger.ONE) }
            assertFailsWith<IllegalArgumentException> {
                client.estimateGas(ADDRESS, OTHER_ADDRESS, BigInteger.ONE, "0xabc")
            }
            assertFailsWith<IllegalArgumentException> {
                client.estimateGas(ADDRESS, OTHER_ADDRESS, BigInteger.valueOf(-1))
            }

            assertEquals(0, server.requestCount)
        }

    @Test
    fun `maxPriorityFeePerGas parses the hex quantity`() =
        runTest {
            result("0x3b9aca00")

            assertEquals(BigInteger.valueOf(1_000_000_000), client.maxPriorityFeePerGas())
            assertEquals("eth_maxPriorityFeePerGas", takeBody().getValue("method").jsonPrimitive.content)
        }

    @Test
    fun `latestBaseFee is read from the latest block without transactions`() =
        runTest {
            enqueue("""{"jsonrpc":"2.0","id":1,"result":{"number":"0x1","baseFeePerGas":"0x3b9aca00"}}""")

            assertEquals(BigInteger.valueOf(1_000_000_000), client.latestBaseFee())

            val params = takeBody().getValue("params").jsonArray
            assertEquals("latest", params[0].jsonPrimitive.content)
            assertEquals(false, params[1].jsonPrimitive.boolean)
        }

    @Test
    fun `latestBaseFee fails on a block without baseFeePerGas`() =
        runTest {
            enqueue("""{"jsonrpc":"2.0","id":1,"result":{"number":"0x1"}}""")

            assertFailsWith<JsonRpcException.Malformed> { client.latestBaseFee() }
        }

    @Test
    fun `latestBaseFee fails when the result is not a block`() =
        runTest {
            result("0x1")

            assertFailsWith<JsonRpcException.Malformed> { client.latestBaseFee() }
        }

    @Test
    fun `call sends to and data at the latest block and returns the raw result`() =
        runTest {
            result("0x000000000000000000000000000000000000000000000000000000000000000a")

            val raw = client.call(ADDRESS, "0x70a08231")

            assertEquals("0x" + "0".repeat(62) + "0a", raw)
            val params = takeBody().getValue("params").jsonArray
            val call = params[0].jsonObject
            assertEquals(ADDRESS, call.getValue("to").jsonPrimitive.content)
            assertEquals("0x70a08231", call.getValue("data").jsonPrimitive.content)
            assertEquals("latest", params[1].jsonPrimitive.content)
        }

    @Test
    fun `call accepts an empty result`() =
        runTest {
            result("0x")

            assertEquals("0x", client.call(ADDRESS, "0x70a08231"))
        }

    @Test
    fun `call that reverts keeps the revert data`() =
        runTest {
            enqueue(
                """{"jsonrpc":"2.0","id":1,"error":{"code":3,"message":"execution reverted","data":"0x08c379a0"}}""",
            )

            val e = assertFailsWith<JsonRpcException.Node> { client.call(ADDRESS, "0x70a08231") }

            assertEquals("0x08c379a0", e.data?.jsonPrimitive?.content)
        }

    @Test
    fun `call rejects a non-hex result`() =
        runTest {
            result("hello")

            assertFailsWith<JsonRpcException.Malformed> { client.call(ADDRESS, "0x70a08231") }
        }

    @Test
    fun `call rejects malformed input without calling the node`() =
        runTest {
            assertFailsWith<IllegalArgumentException> { client.call("0x1", "0x70a08231") }
            assertFailsWith<IllegalArgumentException> { client.call(ADDRESS, "70a08231") }
            assertFailsWith<IllegalArgumentException> { client.call(ADDRESS, "0x7") }

            assertEquals(0, server.requestCount)
        }

    @Test
    fun `sendRawTransaction posts the signed hex and returns the hash`() =
        runTest {
            result(HASH)

            assertEquals(HASH, client.sendRawTransaction("0x02f8"))

            val body = takeBody()
            assertEquals("eth_sendRawTransaction", body.getValue("method").jsonPrimitive.content)
            assertEquals(listOf("0x02f8"), body.getValue("params").jsonArray.map { it.jsonPrimitive.content })
        }

    @Test
    fun `sendRawTransaction surfaces a node rejection`() =
        runTest {
            enqueue("""{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"nonce too low"}}""")

            val e = assertFailsWith<JsonRpcException.Node> { client.sendRawTransaction("0x02f8") }

            assertEquals("nonce too low", e.message)
        }

    @Test
    fun `sendRawTransaction rejects a malformed hash in the response`() =
        runTest {
            result("0x1234")

            assertFailsWith<JsonRpcException.Malformed> { client.sendRawTransaction("0x02f8") }
        }

    @Test
    fun `sendRawTransaction rejects invalid input without calling the node`() =
        runTest {
            assertFailsWith<IllegalArgumentException> { client.sendRawTransaction("0x") }
            assertFailsWith<IllegalArgumentException> { client.sendRawTransaction("02f8") }
            assertFailsWith<IllegalArgumentException> { client.sendRawTransaction("0x02f") }

            assertEquals(0, server.requestCount)
        }

    @Test
    fun `receipt is null while the transaction is not in a block`() =
        runTest {
            enqueue("""{"jsonrpc":"2.0","id":1,"result":null}""")

            assertNull(client.getTransactionReceipt(HASH))
        }

    @Test
    fun `receipt of a successful transaction`() =
        runTest {
            enqueue("""{"jsonrpc":"2.0","id":1,"result":{"status":"0x1","blockNumber":"0x10","gasUsed":"0x5208"}}""")

            val receipt = client.getTransactionReceipt(HASH)

            assertEquals(TransactionReceipt(success = true, blockNumber = BigInteger.valueOf(16)), receipt)
            assertEquals(listOf(HASH), takeBody().getValue("params").jsonArray.map { it.jsonPrimitive.content })
        }

    @Test
    fun `receipt of a reverted transaction`() =
        runTest {
            enqueue("""{"jsonrpc":"2.0","id":1,"result":{"status":"0x0","blockNumber":"0x11"}}""")

            assertEquals(
                TransactionReceipt(success = false, blockNumber = BigInteger.valueOf(17)),
                client.getTransactionReceipt(HASH),
            )
        }

    @Test
    fun `receipt without status or block number is malformed`() =
        runTest {
            enqueue("""{"jsonrpc":"2.0","id":1,"result":{"blockNumber":"0x10"}}""")
            enqueue("""{"jsonrpc":"2.0","id":2,"result":{"status":"0x1"}}""")

            assertFailsWith<JsonRpcException.Malformed> { client.getTransactionReceipt(HASH) }
            assertFailsWith<JsonRpcException.Malformed> { client.getTransactionReceipt(HASH) }
        }

    @Test
    fun `receipt with an unknown status is malformed`() =
        runTest {
            enqueue("""{"jsonrpc":"2.0","id":1,"result":{"status":"0x2","blockNumber":"0x10"}}""")

            assertFailsWith<JsonRpcException.Malformed> { client.getTransactionReceipt(HASH) }
        }

    @Test
    fun `receipt that is not an object is malformed`() =
        runTest {
            result("0x1")

            assertFailsWith<JsonRpcException.Malformed> { client.getTransactionReceipt(HASH) }
        }

    @Test
    fun `getTransactionReceipt rejects a malformed hash without calling the node`() =
        runTest {
            assertFailsWith<IllegalArgumentException> { client.getTransactionReceipt("0x1234") }

            assertEquals(0, server.requestCount)
        }

    private companion object {
        const val HASH = "0x88df016429689c079f3b2f6ad39fa052532c56795b733da78a91ebe6a713944b"
        const val ADDRESS = "0x0123456789abcdef0123456789abcdef01234567"
        const val OTHER_ADDRESS = "0x89abcdef0123456789abcdef0123456789abcdef"
    }
}
