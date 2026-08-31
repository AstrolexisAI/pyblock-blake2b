package com.astrolexis.pyblock.data.model

import kotlinx.serialization.Serializable

/** Response of `wallet_utxos.php?addresses=` — CONFIRMED unspent outputs paying one or
 *  more addresses, served from PyBLØCK's own node (scantxoutset / electrs). Complements
 *  `wallet_mempool.php` (which is 0-conf only), so the app can see confirmed payments —
 *  e.g. a PayNym stealth address that already confirmed — without a full on-device CBF scan. */
@Serializable
data class WalletUtxosResp(
    val utxos: List<Utxo> = emptyList(),
    // true = server scan was busy (cache miss during a concurrent scan) — NOT an empty
    // balance. Treated as a failure so the sweep retries instead of showing a false 0.
    val warming: Boolean = false,
)

@Serializable
data class Utxo(
    val address: String = "",
    val txid: String = "",
    val vout: Int = 0,
    val value: Long = 0,        // sats
    val height: Int = 0,        // confirmation height
    val hex: String = "",       // raw funding tx (to credit the UTXO into BDK instantly)
    val coinbase: Boolean = false, // mined (coinbase) output — 100-block maturity
)
