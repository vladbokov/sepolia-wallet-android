package io.github.vladbokov.wallet.core.rpc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EthereumRpcTest {
    @Test
    fun `base url gets a trailing slash`() {
        assertEquals("https://node.example/v2/key/", "https://node.example/v2/key".toRpcBaseUrl().toString())
    }

    @Test
    fun `base url with trailing slash is kept`() {
        assertEquals("https://node.example/v2/key/", "https://node.example/v2/key/".toRpcBaseUrl().toString())
    }

    @Test
    fun `host only url is kept`() {
        assertEquals("https://node.example/", "https://node.example".toRpcBaseUrl().toString())
    }

    @Test
    fun `query string is rejected`() {
        assertFailsWith<IllegalArgumentException> { "https://node.example/v2?key=abc".toRpcBaseUrl() }
    }

    @Test
    fun `invalid url is rejected`() {
        assertFailsWith<IllegalArgumentException> { "not a url".toRpcBaseUrl() }
    }
}
