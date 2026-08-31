package com.astrolexis.pyblock.data.blake

import android.content.Context
import com.astrolexis.pyblock.data.wallet.WalletStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Server-read balance/UTXO state for the BLAKE2b wallet — the Node B (PoW-agnostic)
 * counterpart to the SHA-256 app's on-device CBF node. A CBF client can't follow BLAKE2b
 * after the PoW change, so balances/UTXOs are read per-address from the server
 * (`BlakeApi.walletUtxos`, chain=blake2b). Keys/wallets live in the shared [WalletStore]
 * (own EncryptedSharedPreferences vault); this layer never touches secrets.
 *
 * Spendable vs locked follows [BlakeFork]: only mature (100-conf) POST-FORK coinbase is
 * spendable (non-replayable); everything else is replay-exposed and shown as locked.
 * Mirrors iOS `BlakeWalletStore` balance/UTXO half.
 */
object BlakeBalanceStore {
    /** address → its UTXOs (server-read). */
    private val _utxos = MutableStateFlow<Map<String, List<BlakeApi.Utxo>>>(emptyMap())
    val utxos: StateFlow<Map<String, List<BlakeApi.Utxo>>> = _utxos.asStateFlow()

    /** Chain tip height (for confirmations / maturity). */
    private val _tip = MutableStateFlow(0)
    val tip: StateFlow<Int> = _tip.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** Fires (delta sats) when the total confirmed balance INCREASES after a refresh —
     *  drives the "received" effect. Null until the first successful refresh (no false ding). */
    private val _receiveEvent = MutableStateFlow<ReceiveEvent?>(null)
    val receiveEvent: StateFlow<ReceiveEvent?> = _receiveEvent.asStateFlow()
    data class ReceiveEvent(val id: Long, val deltaSats: Long)
    private var lastTotal: Long? = null
    private var eventSeq = 0L

    // ---- Derived totals ----

    /** All UTXOs across every wallet, flattened. */
    fun allUtxos(): List<BlakeApi.Utxo> = _utxos.value.values.flatten()

    /** Confirmed balance = every UTXO's value (server returns confirmed only). */
    fun totalSats(): Long = allUtxos().sumOf { it.value }

    /** Spendable = mature post-fork coinbase only (non-replayable). */
    fun spendableSats(): Long {
        val t = _tip.value
        return allUtxos().filter { BlakeFork.isSpendable(it, t) }.sumOf { it.value }
    }

    /** Locked = everything not safely spendable (pre-fork/shared or immature coinbase). */
    fun lockedSats(): Long = totalSats() - spendableSats()

    /** Per-wallet confirmed balance (by address). */
    fun balanceForAddress(address: String): Long = _utxos.value[address].orEmpty().sumOf { it.value }

    // ---- Refresh ----

    /** Re-read UTXOs for every wallet from the server. Preserves last-good on a per-address
     *  failure/warming (never a false zero). Emits a receive event when the total rises. */
    suspend fun refresh(ctx: Context) {
        WalletStore.ensureLoaded(ctx)
        val wallets = WalletStore.wallets.value.filter { it.address.isNotBlank() }
        if (wallets.isEmpty()) { _utxos.value = emptyMap(); lastTotal = 0L; return }
        _loading.value = true
        val next = _utxos.value.toMutableMap()
        var tipSeen = _tip.value
        for (w in wallets) {
            val r = BlakeApi.walletUtxos(w.address) ?: continue   // warming/fail → keep last-good
            next[w.address] = r.first
            if (r.second > tipSeen) tipSeen = r.second
        }
        _utxos.value = next
        if (tipSeen > 0) _tip.value = tipSeen
        _loading.value = false

        val total = totalSats()
        val prev = lastTotal
        if (prev != null && total > prev) {
            eventSeq += 1
            _receiveEvent.value = ReceiveEvent(eventSeq, total - prev)
        }
        lastTotal = total
    }

    /** Consume the pending receive event (after the UI plays the effect). */
    fun clearReceiveEvent() { _receiveEvent.value = null }
}
