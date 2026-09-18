package com.astrolexis.pyblock.data.blake

import android.content.Context
import com.astrolexis.pyblock.data.wallet.WalletStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    private var lastTotal: Long? = null   // null until the first COMPLETE total (no false ding)
    /** Addresses whose UTXO set has been loaded at least once this session. A total is only
     *  compared to the previous one once EVERY wallet is here; before that a rising total is the
     *  rest of the wallets arriving (a warming miss, or the stream landing before the first
     *  refresh finished), not a payment. That was the "RECEIVED on every open" bug. */
    private val loaded = HashSet<String>()
    /** The wallet list the current baseline was taken against; a new or removed address invalidates it. */
    private var baselineAddresses: List<String> = emptyList()

    /** Emit a receive event only for a rise between two COMPLETE totals over the SAME addresses. */
    @Synchronized private fun detectReceive(wallets: List<String>) {
        if (!wallets.all { it in loaded }) return
        if (wallets != baselineAddresses) { baselineAddresses = wallets; lastTotal = totalSats(); return }
        val total = totalSats()
        val prev = lastTotal
        if (prev != null && total > prev) { eventSeq += 1; _receiveEvent.value = ReceiveEvent(eventSeq, total - prev) }
        lastTotal = total
    }
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
    /** Value of our own coins that an in-flight (0-conf) send is spending. */
    fun pendingOutTotal(): Long { val s = _pendingSpentIds.value; return allUtxos().filter { it.id in s }.sumOf { it.value } }
    fun hasPending(): Boolean = pendingInTotal() > 0 || _pendingSpentIds.value.isNotEmpty()

    /** Mark coins as spent IMMEDIATELY after a successful broadcast, so a second send within the
     *  server's cache window can't reuse them (double-spend → bad-txns-inputs-missingorspent). Ids
     *  are "txid:vout"; cleared naturally once a confirmed refresh drops the spent UTXOs. */
    fun markSpent(ids: Set<String>, txid: String? = null) {
        if (ids.isEmpty()) return
        val now = System.currentTimeMillis()
        synchronized(localSpent) { ids.forEach { localSpent[it] = LocalMark(now, txid) } }
        _pendingSpentIds.value = _pendingSpentIds.value + ids
        persistInFlight()
    }
    @Serializable data class LocalMark(val at: Long, val txid: String?)
    /** A send we broadcast, kept until the node confirms it or says it never saw it. `leaving` is
     *  what actually goes away, `coming` what pays our own addresses (change, or the whole amount
     *  when consolidating to ourselves). Without this the balance read a bare 0 between the node
     *  dropping the spent coin and the block landing — the "balance is messed up" report. */
    @Serializable data class InFlightSend(val txid: String, val at: Long, val leaving: Long, val coming: Long)
    @Serializable private data class InFlightFile2(val sends: List<InFlightSend>)
    private val _inFlight = MutableStateFlow<List<InFlightSend>>(emptyList())
    val inFlight: StateFlow<List<InFlightSend>> = _inFlight.asStateFlow()
    fun noteSend(txid: String, leaving: Long, coming: Long) {
        if (txid.isBlank()) return
        _inFlight.value = _inFlight.value.filter { it.txid != txid } + InFlightSend(txid, System.currentTimeMillis(), leaving, coming)
        persistInFlight()
    }
    private fun dropSend(txid: String) {
        if (_inFlight.value.none { it.txid == txid }) return
        _inFlight.value = _inFlight.value.filter { it.txid != txid }
        persistInFlight()
    }
    @Serializable private data class InFlightFile(val marks: Map<String, LocalMark>)
    /** The marks outlive the process. They used to live only in memory, so a send followed by the
     *  app being killed came back with the coin unlocked, no node asked — the "reverts to locked"
     *  report, in 10–15 minutes rather than at any TTL. A relaunch reloads them; the node decides. */
    private var inFlightPrefs: android.content.SharedPreferences? = null
    private fun persistInFlight() {
        val p = inFlightPrefs ?: return
        val snap = synchronized(localSpent) { HashMap(localSpent) }
        p.edit()
            .putString("marks", Json.encodeToString(InFlightFile.serializer(), InFlightFile(snap)))
            .putString("sends", Json.encodeToString(InFlightFile2.serializer(), InFlightFile2(_inFlight.value)))
            .apply()
    }
    private fun loadInFlight(ctx: Context) {
        if (inFlightPrefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("blake_inflight", Context.MODE_PRIVATE); inFlightPrefs = p
        p.getString("sends", null)?.let { raw2 ->
            runCatching { Json.decodeFromString(InFlightFile2.serializer(), raw2).sends }.getOrNull()?.let { list ->
                val now2 = System.currentTimeMillis()
                _inFlight.value = list.filter { now2 - it.at < LOCAL_SPENT_HARD_CAP_MS }
            }
        }
        val raw = p.getString("marks", null) ?: return
        val marks = runCatching { Json.decodeFromString(InFlightFile.serializer(), raw).marks }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        val live = marks.filterValues { now - it.at < LOCAL_SPENT_HARD_CAP_MS }
        synchronized(localSpent) { localSpent.putAll(live) }
        _pendingSpentIds.value = _pendingSpentIds.value + live.keys
    }
    /** Last word from the node per txid, so the poll asks each one at most once a minute. */
    private val txChecked = HashMap<String, Pair<Long, BlakeApi.TxStatus>>()
    /** Coins WE broadcast a spend of → broadcast time. The server's mempool view lags the push
     *  (index cache window; it may only list txs paying an address, not spending from it), so a
     *  poll right after a send used to wipe the in-flight mark and the "sending · in mempool"
     *  line vanished. A local mark holds until the coin leaves the confirmed set (mined) or
     *  [LOCAL_SPENT_TTL_MS] passes without the node ever showing the tx. Mirrors iOS. */
    private val localSpent = HashMap<String, LocalMark>()
    /** Only for a mark without a txid (a resend the node said was already pending — we never got the
     *  first txid). A mark WITH a txid is released by the node's answer, never by a clock. */
    private const val LOCAL_SPENT_TTL_MS = 30L * 60_000
    private const val LOCAL_SPENT_HARD_CAP_MS = 24L * 3_600_000

    // ---- Derived totals ----

    /** All UTXOs across every wallet, flattened. */
    fun allUtxos(): List<BlakeApi.Utxo> = _utxos.value.values.flatten()

    /** Cached output script per address (BDK parses both forms; an unparsable address gives []). */
    private val scriptCache = HashMap<String, ByteArray>()
    @Synchronized fun scriptFor(address: String): ByteArray = scriptCache.getOrPut(address) {
        runCatching { org.bitcoindevkit.Address(address, org.bitcoindevkit.Network.BITCOIN).scriptPubkey().toBytes() }.getOrDefault(ByteArray(0))
    }

    /** Confirmed balance = every UTXO's value (server returns confirmed only). */
    fun totalSats(): Long = allUtxos().sumOf { it.value }

    /** Spendable = mature post-fork coinbase only (non-replayable). Excludes coins a mempool
     *  tx is already spending (in-flight send) so the balance drops immediately. */
    fun spendableSats(): Long {
        val t = _tip.value
        val spent = _pendingSpentIds.value
        return allUtxos().filter { BlakeFork.isEffectivelySpendable(it, t) && it.id !in spent && !it.spentInMempool }.sumOf { it.value }
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
            loaded += w.address
            if (r.second > tipSeen) tipSeen = r.second
        }
        val pendingBefore = pendingInTotal(); val totalBefore = lastTotal ?: totalSats()
        _utxos.value = next
        if (tipSeen > 0) _tip.value = tipSeen
        pollMempool(wallets)
        _loading.value = false
        detectConfirmed(pendingBefore, totalBefore)
        detectMatured()

        detectReceive(wallets.map { it.address })
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
                // By script, not by address: only the legacy form could be rebuilt from a base58
                // string, so a bech32 wallet never saw an incoming 0-conf payment at all.
                val sats = BlakeApi.incomingSatsToScript(scriptFor(w.address), tx.hex)
                if (sats > 0) {
                    inSats[w.address] = (inSats[w.address] ?: 0) + sats
                    items.add(PendingItem(key, true, sats, w.address, tx.seen ?: (System.currentTimeMillis() / 1000)))
                }
            }
        }
        // Local marks: drop the ones whose coin is gone (mined). For the rest, ask the node about the
        // txid instead of trusting a clock: a send that sits in the mempool for an hour stays in
        // flight; a send the node never saw is given back — and said so — instead of quietly
        // reappearing 30 minutes later as if nothing had happened (that read as "sent back").
        val now = System.currentTimeMillis()
        // Prune to coins we still hold — only once the confirmed set has actually loaded. On a
        // fresh launch it is empty for a moment, and pruning against it dropped every mark.
        if (ourIds.isNotEmpty()) synchronized(localSpent) { localSpent.entries.removeAll { it.key !in ourIds } }
        val lost = HashMap<String, Long>()   // txid → sats coming back
        val txids = synchronized(localSpent) { localSpent.values.mapNotNull { it.txid }.toSet() }
        for (txid in txids) {
            val marks = synchronized(localSpent) { localSpent.filterValues { it.txid == txid } }
            val oldest = marks.values.minOfOrNull { it.at } ?: continue
            if (now - oldest < 90_000) continue
            var st = synchronized(txChecked) { txChecked[txid] }
            if (st == null || now - st.first > 60_000) BlakeApi.txStatus(txid)?.let { fresh -> st = now to fresh; synchronized(txChecked) { txChecked[txid] = st!! } }
            val status = st?.second ?: continue
            if (!status.inMempool) dropSend(txid)   // confirmed → the coins are in the set again; unknown → gone
            if (status.unknown) {
                lost[txid] = allUtxos().filter { it.id in marks.keys }.sumOf { it.value }
                synchronized(localSpent) { marks.keys.forEach { localSpent.remove(it) } }
            }
            // mempool / confirmed: keep the mark, however long it takes.
        }
        val local = synchronized(localSpent) {
            localSpent.entries.removeAll { (_, m) -> if (m.txid != null) now - m.at >= LOCAL_SPENT_HARD_CAP_MS else now - m.at >= LOCAL_SPENT_TTL_MS }
            localSpent.keys.toSet()
        }
        // A send whose marks are gone (its coins were mined) still needs its own answer before the
        // pending line disappears, so ask about any txid we are still showing.
        for (s in _inFlight.value) {
            if (now - s.at < 90_000 || synchronized(localSpent) { localSpent.values.any { it.txid == s.txid } }) continue
            var st = synchronized(txChecked) { txChecked[s.txid] }
            if (st == null || now - st.first > 60_000) BlakeApi.txStatus(s.txid)?.let { fresh -> st = now to fresh; synchronized(txChecked) { txChecked[s.txid] = st!! } }
            val status = st?.second ?: continue
            if (!status.inMempool) dropSend(s.txid)
        }
        persistInFlight()
        lost.values.forEach { sats -> com.astrolexis.pyblock.ui.blake.WalletEvents.post(com.astrolexis.pyblock.ui.blake.WalletEvents.Kind.SendLost(sats)) }
        _pendingIn.value = inSats
        // The node's own word: a tx in its mempool already spends this coin. Our marks can be gone
        // (a reinstall, another device), so this is what stops us offering it a second time.
        _pendingSpentIds.value = spent + local + allUtxos().filter { it.spentInMempool }.map { it.id }
        _pendingActivity.value = items.sortedByDescending { it.seen }
    }

    /** Mempool-only poll while the live stream is up. The push stream carries CONFIRMED utxo
     *  sets; 0-conf state only comes from `wallet_mempool`, so without this the pending lines
     *  only moved on a pull-to-refresh. Cheap (one small GET per address), 15 s. */
    private val pollScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private fun startMempoolPoll(ctx: Context) {
        if (pollJob?.isActive == true) return
        pollJob = pollScope.launch {
            while (isActive) {
                delay(15_000)
                if (refreshMutex.isLocked) continue          // a full refresh polls anyway
                val wallets = WalletStore.wallets.value.filter { it.address.isNotBlank() }
                if (wallets.isEmpty()) continue
                val pendingBefore = pendingInTotal(); val totalBefore = lastTotal ?: totalSats()
                pollMempool(wallets)
                detectConfirmed(pendingBefore, totalBefore)
            }
        }
    }
    private fun stopMempoolPoll() { pollJob?.cancel(); pollJob = null }

    /** Consume the pending receive event (after the UI plays the effect). */
    fun clearReceiveEvent() { _receiveEvent.value = null }
    /** A 0-conf incoming amount was mined (it left pendingIn while the confirmed total held). */
    private val _confirmedEvent = MutableStateFlow<ReceiveEvent?>(null)
    val confirmedEvent: StateFlow<ReceiveEvent?> = _confirmedEvent.asStateFlow()
    fun clearConfirmedEvent() { _confirmedEvent.value = null }
    /** Mined coins crossed the 100-block line and became spendable. */
    private val _maturedEvent = MutableStateFlow<ReceiveEvent?>(null)
    val maturedEvent: StateFlow<ReceiveEvent?> = _maturedEvent.asStateFlow()
    fun clearMaturedEvent() { _maturedEvent.value = null }
    private var prevSpendableIds: Set<String>? = null

    @Synchronized private fun detectMatured() {
        val tip = _tip.value
        val now = allUtxos().filter { it.coinbase && BlakeFork.isSpendable(it, tip) }.map { it.id }.toSet()
        val prev = prevSpendableIds; prevSpendableIds = now
        if (prev == null) return
        val fresh = now - prev
        if (fresh.isEmpty()) return
        val sats = allUtxos().filter { it.id in fresh }.sumOf { it.value }
        if (sats > 0) { eventSeq += 1; _maturedEvent.value = ReceiveEvent(eventSeq, sats) }
    }
    private fun detectConfirmed(pendingBefore: Long, totalBefore: Long) {
        val after = pendingInTotal()
        if (pendingBefore > after && totalSats() >= totalBefore) { eventSeq += 1; _confirmedEvent.value = ReceiveEvent(eventSeq, pendingBefore - after) }
    }

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
        loadInFlight(ctx)
        ForkNativeStore.init(ctx)
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
        startMempoolPoll(ctx)
    }

    fun stopLive() {
        stopMempoolPoll()
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
        loaded += addr
        // fire the "received" effect the instant a payment lands — but only against a complete baseline
        detectReceive(WalletStore.wallets.value.filter { it.address.isNotBlank() }.map { it.address })
        detectMatured()
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
