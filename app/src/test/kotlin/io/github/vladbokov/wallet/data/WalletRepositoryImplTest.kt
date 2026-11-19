package io.github.vladbokov.wallet.data

import io.github.vladbokov.wallet.core.rpc.EthereumRpc
import io.github.vladbokov.wallet.core.wallet.SignedTransaction
import io.github.vladbokov.wallet.domain.model.DataError
import io.github.vladbokov.wallet.domain.model.Token
import io.github.vladbokov.wallet.domain.model.TxStatus
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import java.math.BigInteger
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class WalletRepositoryImplTest {
    private val server = MockWebServer()
    private lateinit var repository: WalletRepositoryImpl

    private val owner = "0x" + "11".repeat(20)
    private val recipient = "0x" + "22".repeat(20)
    private val hash = "0x" + "ab".repeat(32)

    /** Answers by JSON-RPC method and echoes the request id, because the client checks it. */
    private fun serve(handler: (method: String) -> String) {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
                    val id = body.getValue("id").jsonPrimitive.content
                    val method = body.getValue("method").jsonPrimitive.content
                    return reply(handler(method).replace("%ID%", id))
                }
            }
    }

    private fun reply(
        body: String,
        code: Int = 200,
    ) = MockResponse
        .Builder()
        .code(code)
        .body(body)
        .build()

    private fun result(json: String) = """{"jsonrpc":"2.0","id":%ID%,"result":$json}"""

    private fun nodeError(
        code: Int,
        message: String,
    ) = """{"jsonrpc":"2.0","id":%ID%,"error":{"code":$code,"message":"$message"}}"""

    private fun validContextReply(method: String) =
        when (method) {
            "eth_chainId" -> result("\"0xaa36a7\"")
            "eth_getBalance" -> result("\"0x64\"")
            "eth_getTransactionCount" -> result("\"0x7\"")
            "eth_estimateGas" -> result("\"0x5208\"")
            "eth_getBlockByNumber" -> result("""{"baseFeePerGas":"0x3b9aca00"}""")
            "eth_maxPriorityFeePerGas" -> result("\"0x59682f00\"")
            else -> error("unexpected $method")
        }

    private fun receipt(status: String) = result("""{"status":"$status","blockNumber":"0x10"}""")

    @BeforeTest
    fun setUp() {
        server.start()
        val rpc = EthereumRpc.create(server.url("/v2/key").toString(), OkHttpClient())
        repository = WalletRepositoryImpl(rpc, pollIntervalMillis = 1_000, maxPolls = 3)
    }

    @AfterTest
    fun tearDown() {
        server.close()
    }

    // --- reads ---

    @Test
    fun `balance returns wei`() =
        runTest {
            serve { result("\"0x56bc75e2d63100000\"") }

            assertEquals(BigInteger("100000000000000000000"), repository.balance(owner))
        }

    @Test
    fun `tokenBalance decodes the eth_call result`() =
        runTest {
            serve { result("\"0x" + "0".repeat(63) + "5\"") }

            assertEquals(BigInteger.valueOf(5), repository.tokenBalance(Token(recipient, "LINK", 18), owner))
        }

    @Test
    fun `tokenBalance of an address without a contract is malformed`() =
        runTest {
            serve { result("\"0x\"") }

            assertFailsWith<DataError.MalformedResponse> {
                repository.tokenBalance(Token(recipient, "LINK", 18), owner)
            }
        }

    @Test
    fun `transferContext combines six concurrent reads`() =
        runTest {
            serve(::validContextReply)

            val context = repository.transferContext(owner, recipient, BigInteger.TEN)

            assertEquals(11155111L, context.chainId)
            assertEquals(BigInteger.valueOf(100), context.balance)
            assertEquals(BigInteger.valueOf(7), context.nonce)
            assertEquals(BigInteger.valueOf(21_000), context.estimatedGas)
            assertEquals(BigInteger.valueOf(1_000_000_000), context.baseFee)
            assertEquals(BigInteger.valueOf(1_500_000_000), context.priorityFee)
        }

    @Test
    fun `transferContext fails with one DataError when a single read fails`() =
        runTest {
            // The other five reads succeed, so the failure cannot race with another error.
            serve { method ->
                if (method == "eth_estimateGas") nodeError(3, "execution reverted") else validContextReply(method)
            }

            val e = assertFailsWith<DataError.Rpc> { repository.transferContext(owner, recipient, BigInteger.TEN) }

            assertEquals(3, e.code)
        }

    // --- error mapping ---

    @Test
    fun `unreachable endpoint is a network error`() =
        runTest {
            server.close()

            assertFailsWith<DataError.Network> { repository.balance(owner) }
        }

    @Test
    fun `rate limit and gateway failures are network errors`() =
        runTest {
            for (status in listOf(429, 500, 503)) {
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest) = reply("""{"error":"x"}""", status)
                    }

                assertFailsWith<DataError.Network>("HTTP $status") { repository.balance(owner) }
            }
        }

    @Test
    fun `other HTTP errors are rpc errors with the status`() =
        runTest {
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest) = reply("""{"error":"bad key"}""", 401)
                }

            val e = assertFailsWith<DataError.Rpc> { repository.balance(owner) }

            assertEquals(401, e.code)
        }

    @Test
    fun `unparsable body is a malformed response`() =
        runTest {
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest) = reply("not json")
                }

            assertFailsWith<DataError.MalformedResponse> { repository.balance(owner) }
        }

    // --- broadcast ---

    private val signed = SignedTransaction(rawHex = "0x02f8", hash = hash)

    @Test
    fun `broadcast accepts the matching hash in any letter case`() =
        runTest {
            serve { result("\"${hash.uppercase().replace("0X", "0x")}\"") }

            repository.broadcast(signed)
        }

    @Test
    fun `broadcast rejects a hash the node computed differently`() =
        runTest {
            serve { result("\"0x" + "cd".repeat(32) + "\"") }

            assertFailsWith<DataError.MalformedResponse> { repository.broadcast(signed) }
        }

    @Test
    fun `broadcast maps node rejections`() =
        runTest {
            serve { nodeError(-32000, "nonce too low: next nonce 8, tx nonce 7") }
            assertFailsWith<DataError.NonceTooLow> { repository.broadcast(signed) }

            serve { nodeError(-32000, "insufficient funds for gas * price + value") }
            assertFailsWith<DataError.RejectedInsufficientFunds> { repository.broadcast(signed) }

            serve { nodeError(-32000, "InsufficientFunds") }
            assertFailsWith<DataError.RejectedInsufficientFunds> { repository.broadcast(signed) }

            serve { nodeError(-32000, "replacement transaction underpriced") }
            val e = assertFailsWith<DataError.Rpc> { repository.broadcast(signed) }
            assertEquals(-32000, e.code)
        }

    @Test
    fun `broadcast treats a transaction the node already holds as sent`() =
        runTest {
            // A repeat of a send whose answer was lost: same signed bytes, same hash.
            for (message in listOf("already known", "AlreadyKnown")) {
                serve { nodeError(-32000, message) }

                repository.broadcast(signed)
            }
        }

    // --- lost connection: the outcome of a broadcast is unknown, not "invalid response" ---

    private fun repositoryWith(client: OkHttpClient) =
        WalletRepositoryImpl(EthereumRpc.create(server.url("/v2/key").toString(), client))

    @Test
    fun `a timeout while waiting for the answer is a network error`() =
        runTest {
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest) =
                        MockResponse
                            .Builder()
                            .headersDelay(2, TimeUnit.SECONDS)
                            .body("""{"jsonrpc":"2.0","id":1,"result":"$hash"}""")
                            .build()
                }
            val impatient = OkHttpClient.Builder().callTimeout(300, TimeUnit.MILLISECONDS).build()

            assertFailsWith<DataError.Network> { repositoryWith(impatient).broadcast(signed) }
        }

    @Test
    fun `a connection dropped in the middle of the answer is a network error`() =
        runTest {
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest) =
                        MockResponse
                            .Builder()
                            .body("""{"jsonrpc":"2.0","id":1,"result":"$hash"}""")
                            .onResponseBody(SocketEffect.CloseSocket())
                            .build()
                }

            assertFailsWith<DataError.Network> { repositoryWith(OkHttpClient()).broadcast(signed) }
        }

    // --- observeStatus ---

    @Test
    fun `observeStatus emits Pending then Confirmed once the receipt appears`() =
        runTest {
            var polls = 0
            serve { if (++polls < 2) result("null") else receipt("0x1") }

            val statuses = repository.observeStatus(hash).toList()

            assertEquals(listOf(TxStatus.Pending, TxStatus.Confirmed(BigInteger.valueOf(16))), statuses)
        }

    @Test
    fun `observeStatus reports a reverted transaction as Failed`() =
        runTest {
            serve { receipt("0x0") }

            assertEquals(listOf(TxStatus.Pending, TxStatus.Failed), repository.observeStatus(hash).toList())
        }

    @Test
    fun `observeStatus times out when the receipt never appears`() =
        runTest {
            serve { result("null") }

            assertEquals(listOf(TxStatus.Pending, TxStatus.TimedOut), repository.observeStatus(hash).toList())
        }

    @Test
    fun `observeStatus keeps polling through a temporary failure`() =
        runTest {
            var polls = 0
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val id = Json.parseToJsonElement(request.body!!.utf8()).jsonObject.getValue("id")
                        return if (++polls == 1) {
                            reply("{}", 503)
                        } else {
                            reply(receipt("0x1").replace("%ID%", id.jsonPrimitive.content))
                        }
                    }
                }

            val statuses = repository.observeStatus(hash).toList()

            assertIs<TxStatus.Confirmed>(statuses.last())
        }

    @Test
    fun `observeStatus fails instead of timing out when no poll was ever answered`() =
        runTest {
            server.close()

            assertFailsWith<DataError.Network> { repository.observeStatus(hash).toList() }
        }

    @Test
    fun `observeStatus fails on a non-temporary error`() =
        runTest {
            serve { nodeError(-32000, "boom") }

            assertFailsWith<DataError.Rpc> { repository.observeStatus(hash).toList() }
        }
}
