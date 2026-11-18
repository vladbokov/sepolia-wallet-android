package io.github.vladbokov.wallet.domain.model

import java.math.BigInteger

sealed interface TxStatus {
    data object Pending : TxStatus

    data class Confirmed(
        val blockNumber: BigInteger,
    ) : TxStatus

    data object Failed : TxStatus

    data object TimedOut : TxStatus
}
