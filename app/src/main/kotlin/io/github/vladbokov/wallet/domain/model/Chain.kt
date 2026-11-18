package io.github.vladbokov.wallet.domain.model

data class Token(
    val address: String,
    val symbol: String,
    val decimals: Int,
)

/** The network the wallet works with. Transport details such as the RPC endpoint stay outside the domain. */
data class ChainConfig(
    val chainId: Long,
    val name: String,
    val symbol: String,
    val explorerTxUrl: String,
    val demoToken: Token? = null,
)
