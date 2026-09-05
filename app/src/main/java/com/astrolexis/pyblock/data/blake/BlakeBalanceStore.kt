package com.astrolexis.pyblock.data.blake

import android.content.Context
import com.astrolexis.pyblock.data.wallet.WalletStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

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

    // ---- Pending (0-conf mempool) ----

    /** address → 0-conf INCOMING sats (mempool receives / change not yet mined). */
    private val _pendingIn = MutableStateFlow<Map<String, Long>>(emptyMap())
    val pendingIn: StateFlow<Map<String, Long>> = _pendingIn.asStateFlow()
    /** Our confirmed-UTXO ids being SPENT by an unconfirmed tx (in-flight send). */
    private val _pendingSpentIds = MutableStateFlow<Set<String>>(emptySet())
    val pendingSpentIds: StateFlow<Set<String>> = _pendingSpentIds.asStateFlow()
    /** 0-conf incoming rows for the unified activity list. */
    private val _pendingActivity = MutableStateFlow<List<PendingItem>>(emptyList())
    val pendingActivity: StateFlow<List<PendingItem>> = _pendingActivity.asStateFlow()
    data class PendingItem(val id: String, val incoming: Boolean, val sats: Long, val address: String, val seen: Long)

    fun pendingInTotal(): Long = _pendingIn.value.values.sum()
    fun hasPending(): Boolean = pendingInTotal() > 0 || _pendingSpentIds.value.isNotEmpty()

    /** Mark coins as spent IMMEDIATELY after a successful broadcast, so a second send within the
     *  server's cache window can't reuse them (double-spend → bad-txns-inputs-missingorspent). Ids
     *  are "txid:vout"; cleared naturally once a confirmed refresh drops the spent UTXOs. */
    fun markSpent(ids: Set<String>) { if (ids.isNotEmpty()) _pendingSpentIds.value = _pendingSpentIds.value + ids }

    // ---- Derived totals ----

    /** All UTXOs across every wallet, flattened. */
    fun allUtxos(): List<BlakeApi.Utxo> = _utxos.value.values.flatten()

    /** Confirmed balance = every UTXO's value (server returns confirmed only). */
    fun totalSats(): Long = allUtxos().sumOf { it.value }

    /** Spendable = mature post-fork coinbase only (non-replayable). Excludes coins a mempool
     *  tx is already spending (in-flight send) so the balance drops immediately. */
    fun spendableSats(): Long {
        val t = _tip.value
        val spent = _pendingSpentIds.value
        return allUtxos().filter { BlakeFork.isEffectivelySpendable(it, t) && it.id !in spent }.sumOf { it.value }
    }

    /** Locked = everything not safely spendable (pre-fork/shared or immature coinbase). */
    fun lockedSats(): Long = totalSats() - spendableSats()

    /** Per-wallet confirmed balance (by address). */
    fun balanceForAddress(address: String): Long = _utxos.value[address].orEmpty().sumOf { it.value }

    // ---- Refresh ----

    // Serializes refresh() so the concurrent callers (RootScaffold + WalletScreen seed + 30s
    // backstop + pull-to-refresh + post-send) don't do overlapping read-modify-write on _utxos.
    private val refreshMutex = Mutex()

    /** Re-read UTXOs for every wallet from the server. Preserves last-good on a per-address
     *  failure/warming (never a false zero). Emits a receive event when the total rises. */
    suspend fun refresh(ctx: Context) = refreshMutex.withLock {
        WalletStore.ensureLoaded(ctx)
        val wallets = WalletStore.wallets.value.filter { it.address.isNotBlank() }
        if (wallets.isEmpty()) { _utxos.value = emptyMap(); lastTotal = 0L; return }
        _loading.value = true
        val next = _utxos.value.toMutableMap()
        var tipSeen = _tip.value
        for (w in wallets) {
            // One retry — the per-address endpoint occasionally warms/hiccups; without it a single
            // miss left that address stale until the next refresh.
            val r = BlakeApi.walletUtxos(w.address) ?: BlakeApi.walletUtxos(w.address) ?: continue
            next[w.address] = r.first
            if (r.second > tipSeen) tipSeen = r.second
        }
        _utxos.value = next
        if (tipSeen > 0) _tip.value = tipSeen
        pollMempool(wallets)
        _loading.value = false

        val total = totalSats()
        val prev = lastTotal
        if (prev != null && total > prev) {
            eventSeq += 1
            _receiveEvent.value = ReceiveEvent(eventSeq, total - prev)
        }
        lastTotal = total
    }

    /** Poll the blake2b mempool for every address → 0-conf incoming (receives/change) and
     *  in-flight spends of our own coins. Adds the pending layer over confirmed reads. */
    private suspend fun pollMempool(wallets: List<com.astrolexis.pyblock.data.wallet.VanityWallet>) {
        val ourIds = allUtxos().map { it.id }.toSet()
        val inSats = HashMap<String, Long>()
        val spent = HashSet<String>()
        val items = ArrayList<PendingItem>()
        val seen = HashSet<String>()
        for (w in wallets) {
            for (tx in BlakeApi.walletMempool(w.address)) {
                BlakeApi.spentOutpoints(tx.hex).forEach { if (it in ourIds) spent.add(it) }
                val key = tx.txid + w.address
                if (key in seen) continue
                seen.add(key)
                val sats = BlakeApi.incomingSats(w.address, tx.hex)
                if (sats > 0) {
                    inSats[w.address] = (inSats[w.address] ?: 0) + sats
                    items.add(PendingItem(key, true, sats, w.address, tx.seen ?: (System.currentTimeMillis() / 1000)))
                }
            }
        }
        _pendingIn.value = inSats
        _pendingSpentIds.value = spent
        _pendingActivity.value = items.sortedByDescending { it.seen }
    }

    /** Consume the pending receive event (after the UI plays the effect). */
    fun clearReceiveEvent() { _receiveEvent.value = null }

    // ---- Live stream (WebSocket push — no time-based polling) ----

    /** True while the push stream is connected. */
    private val _live = MutableStateFlow(false)
    val live: StateFlow<Boolean> = _live.asStateFlow()

    private const val STREAM_URL = "wss://pyblock.xyz:8443/wallet_stream?chain=blake2b"
    private val streamClient = OkHttpClient.Builder().build()
    private val streamJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    @Volatile private var socket: WebSocket? = null
    @Volatile private var subscribedAddrs: List<String> = emptyList()
    @Volatile private var reconnectScheduled = false
    private val socketLock = Any()   // guards the check-then-create so we never open two sockets

    @Serializable private data class StreamMsg(
        val type: String? = null,
        val address: String? = null,
        @SerialName("tip_height") val tipHeight: Int? = null,
        val utxos: List<BlakeApi.Utxo>? = null,
    )

    /** Open the live push stream and subscribe to every wallet address. Idempotent. */
    fun startLive(ctx: Context) {
        WalletStore.ensureLoaded(ctx)
        subscribedAddrs = WalletStore.wallets.value.map { it.address }.filter { it.isNotBlank() }
        if (subscribedAddrs.isEmpty()) return
        synchronized(socketLock) {
            if (socket == null) {
                val req = Request.Builder().url(STREAM_URL).build()
                socket = streamClient.newWebSocket(req, listener)
            } else {
                sendSubscribe()
            }
        }
    }

    fun stopLive() {
        socket?.close(1000, null); socket = null; _live.value = false
    }

    private fun sendSubscribe() {
        val ws = socket ?: return
        val body = JSONObject().put("op", "subscribe")
            .put("addresses", org.json.JSONArray(subscribedAddrs)).toString()
        ws.send(body)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) { _live.value = true; sendSubscribe() }
        override fun onMessage(webSocket: WebSocket, text: String) { handleStream(text) }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { _live.value = false; socket = null; scheduleReconnect() }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { _live.value = false; socket = null; scheduleReconnect() }
    }

    /** Server pushes the FULL current UTXO set for a changed address (idempotent) + tip. */
    private fun handleStream(text: String) {
        _live.value = true
        val m = runCatching { streamJson.decodeFromString<StreamMsg>(text) }.getOrNull() ?: return
        if (m.type == "ping") return
        if ((m.tipHeight ?: 0) > 0) _tip.value = m.tipHeight!!
        val addr = m.address ?: return
        val us = m.utxos ?: return
        _utxos.value = _utxos.value.toMutableMap().apply { put(addr, us) }
        // fire the "received" effect the instant a payment lands
        val total = totalSats()
        val prev = lastTotal
        if (prev != null && total > prev) { eventSeq += 1; _receiveEvent.value = ReceiveEvent(eventSeq, total - prev) }
        lastTotal = total
    }

    private fun scheduleReconnect() {
        if (reconnectScheduled || subscribedAddrs.isEmpty()) return
        reconnectScheduled = true
        Thread {
            try { Thread.sleep(3_000) } catch (_: InterruptedException) {}
            reconnectScheduled = false
            synchronized(socketLock) {
                if (socket == null && subscribedAddrs.isNotEmpty()) {
                    val req = Request.Builder().url(STREAM_URL).build()
                    socket = streamClient.newWebSocket(req, listener)
                }
            }
        }.start()
    }
}
