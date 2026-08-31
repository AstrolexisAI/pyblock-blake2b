package com.astrolexis.pyblock.data.model

import kotlinx.serialization.Serializable

/** Response of `wallet_mempool.php?address=` — unconfirmed txs paying an address,
 *  served from PyBLØCK's own node (0-conf). `hex` = raw serialized tx. */
@Serializable
data class WalletMempoolResp(
    val address: String = "",
    val txs: List<MempoolTx> = emptyList(),
)

@Serializable
data class MempoolTx(
    val txid: String = "",
    val hex: String = "",
    val seen: Long = 0,
)
