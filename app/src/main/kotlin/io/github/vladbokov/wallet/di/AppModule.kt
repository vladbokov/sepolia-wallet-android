package io.github.vladbokov.wallet.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.vladbokov.wallet.BuildConfig
import io.github.vladbokov.wallet.core.rpc.EthereumRpc
import io.github.vladbokov.wallet.core.rpc.EthereumRpcClient
import io.github.vladbokov.wallet.core.wallet.vault.SeedVault
import io.github.vladbokov.wallet.domain.model.ChainConfig
import io.github.vladbokov.wallet.domain.model.Token
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideChainConfig(): ChainConfig =
        ChainConfig(
            chainId = 11155111,
            name = "Sepolia",
            symbol = "ETH",
            explorerTxUrl = "https://sepolia.etherscan.io/tx/",
            // Chainlink's LINK test token on Sepolia; used only to demonstrate an ERC-20 eth_call.
            demoToken =
                Token(
                    address = "0x779877A7B0D9E8603169DdbD7836e478b4624789",
                    symbol = "LINK",
                    decimals = 18,
                ),
        )

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient =
        OkHttpClient
            .Builder()
            .callTimeout(20, TimeUnit.SECONDS)
            .build()

    @Provides
    @Singleton
    fun provideRpcClient(okHttpClient: OkHttpClient): EthereumRpcClient =
        EthereumRpc.create(BuildConfig.RPC_URL, okHttpClient)

    @Provides
    @Singleton
    fun provideSeedVault(
        @ApplicationContext context: Context,
    ): SeedVault = SeedVault(context)
}
