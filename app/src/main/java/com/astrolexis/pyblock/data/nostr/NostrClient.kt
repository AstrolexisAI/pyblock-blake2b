package com.astrolexis.pyblock.data.nostr

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.astrolexis.pyblock.data.crypto.PaymentCode
import com.astrolexis.pyblock.data.crypto.PaynymClaims
import com.astrolexis.pyblock.data.crypto.PaynymName
import com.astrolexis.pyblock.data.crypto.PaynymNotifications
import com.astrolexis.pyblock.data.model.Utxo
import com.astrolexis.pyblock.data.wallet.VanityWallet
import com.astrolexis.pyblock.data.wallet.WalletStore
import com.astrolexis.pyblock.data.wallet.WalletSyncManager
import androidx.lifecycle.viewModelScope
import com.astrolexis.pyblock.data.net.ApiClient
import com.astrolexis.pyblock.data.net.ConfirmedUtxos
import com.astrolexis.pyblock.data.net.ProRepo
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** One decrypted direct message. */
data class DMMessage(
    val id: String,
    val peer: String,
    val mine: Boolean,
    val text: String,
    val createdAt: Long,
)

data class NostrUiState(
    val messages: List<NostrEvent> = emptyList(),
    val whaleMessages: List<NostrEvent> = emptyList(),       // Whale Lounge (whale-only channel)
    val connected: Boolean = false,
    val profiles: Map<String, String> = emptyMap(),          // pubkey → display name
    val peerAddresses: Map<String, String> = emptyMap(),     // pubkey → btc receive address
    val peerPaynyms: Map<String, String> = emptyMap(),       // pubkey → BIP-47 payment code (PM8T…)
    val peerTiers: Map<String, String> = emptyMap(),         // pubkey → "whale"/"pro" (subscription flair)
    val peerColors: Map<String, String> = emptyMap(),        // pubkey → CHAT FLAIR palette key
    // Relay refusal of something WE published (e.g. lounge post without the
    // entitlement). The optimistic echo gets rolled back and the UI shows this.
    val lastRejection: String? = null,
    val readUpTo: Map<String, Long> = emptyMap(),            // peer → created_at they've read up to
    val myReadUpTo: Map<String, Long> = emptyMap(),          // peer → created_at I've read up to (drives the CHAT badge)
    val conversations: Map<String, List<DMMessage>> = emptyMap(),
    // Moderation (App Store / store-policy parity): blocked users' content is
    // hidden instantly; reported message ids are hidden from the feed.
    val blockedUsers: Set<String> = emptySet(),
    val reportedIds: Set<String> = emptySet(),
    // NIP-25 reactions on channel messages: messageId → emoji → reactor pubkeys.
    val reactions: Map<String, Map<String, Set<String>>> = emptyMap(),
)

/** Reactions on a message as (emoji, count, mine), most-reacted first. */
fun NostrUiState.reactionSummary(messageId: String, myPubkey: String): List<Triple<String, Int, Boolean>> =
    (reactions[messageId] ?: emptyMap())
        .map { (emoji, set) -> Triple(emoji, set.size, set.contains(myPubkey)) }
        .sortedByDescending { it.second }

/** DM peers with an incoming message newer than what I've read — drives the CHAT tab badge. */
fun NostrUiState.unreadDmCount(): Int =
    conversations.keys.count { peer ->
        val last = conversations[peer]?.lastOrNull { !it.mine } ?: return@count false
        (myReadUpTo[peer] ?: 0L) < last.createdAt
    }

/**
 * Nostr relay client for the PyBLØCK community chat + encrypted DMs. Connects to
 * the sovereign relay, subscribes to the dedicated NIP-28 channel (kind 42) and
 * NIP-44 DMs (kind 4). Mirrors the iOS `NostrClient`.
 */
class NostrClient(app: Application) : AndroidViewModel(app) {
    private companion object {
        const val SWEEP_TTL_MS = 45_000L               // coalesce rapid-fire sweep triggers
        const val WALLET_SWEEP_MS = 30 * 60_000L       // saved-wallet server-assist cadence
    }

    private val _state = MutableStateFlow(NostrUiState())
    val state: StateFlow<NostrUiState> = _state.asStateFlow()

    // Runs native secp256k1 + Keystore at VM construction (composition). Guard it —
    // an UnsatisfiedLinkError / Keystore failure here must never crash the chat.
    val myPubkey: String = runCatching { Nostr.pubkeyHex(app) }.getOrDefault("")

    // Sovereign: ONLY PyBLØCK's own relay (strfry, channel-only + DM write policy).
    private val relays = listOf("wss://nostr.pyblock.xyz:8443")

    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val sockets = mutableListOf<WebSocket>()
    // Mutated from both the OkHttp reader thread (handle) and the main thread
    // (post/sendDM) — must be concurrency-safe or buckets corrupt (dup/lost dedup,
    // CME on the reader thread killing the receive pump).
    private val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val profileAuthors = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // PayNym sweep state. sweepParent scopes ALL utxo-scanning work to the
    // foreground (cancelled at disconnect/ON_STOP); the fields survive connect
    // cycles so quick app-switching can't duplicate expensive server scans.
    private var paynymSweepJob: Job? = null
    private var sweepParent: CompletableJob? = null
    private val sweepMutex = Mutex()                 // single-flight: one sweep at a time
    @Volatile private var lastSweepDoneMs = 0L
    @Volatile private var lastWalletSweepMs = 0L
    @Volatile private var sweepFailStreak = 0
    @Volatile private var lastConfirmedUtxos: Map<String, List<Utxo>> = emptyMap()
    // Peers whose DMs the user actually opened — their windows join the sweep.
    private val interestPeers = java.util.Collections.synchronizedSet(HashSet<String>())

    private val ctx get() = getApplication<Application>()

    init {
        loadModeration()
        // Collaborative Send (PayJoin) transport: outbound DM + name lookup. Inbound
        // pj-* DMs are routed to the coordinator from the kind-4 handler below.
        PayJoinCoordinator.attach(ctx, object : PayJoinCoordinator.Transport {
            override fun sendDM(peer: String, content: String) = this@NostrClient.sendDM(peer, content)
            override fun nameFor(pubkey: String): String = name(pubkey)
        })
    }

    /** Human name for a pubkey: their profile name, else a short handle. */
    fun name(for_: String): String =
        _state.value.profiles[for_]?.takeIf { it.isNotEmpty() } ?: "@${for_.take(8)}"

    /** Peers with at least one message, most-recent first. */
    fun dmPeers(): List<String> =
        _state.value.conversations.keys.sortedByDescending {
            _state.value.conversations[it]?.lastOrNull()?.createdAt ?: 0
        }

    // MARK: Lifecycle

    fun connect() {
        if (sockets.isNotEmpty()) return
        for (r in relays) {
            val req = Request.Builder().url(r).build()
            sockets.add(http.newWebSocket(req, Listener()))
        }
        Nostr.displayName(ctx)?.let { name -> _state.update { it.copy(profiles = it.profiles + (myPubkey to name)) } }
        sweepParent = SupervisorJob()
        startPaynymSweep()
        startMempoolPump()
    }

    fun disconnect() {
        for (ws in sockets) ws.close(1000, null)
        sockets.clear()
        paynymSweepJob?.cancel(); paynymSweepJob = null
        sweepParent?.cancel(); sweepParent = null    // stops one-shot scans too, not just the loop
        _state.update { it.copy(connected = false) }
    }

    /**
     * Proactively detect incoming PayNym payments WITHOUT the user opening the chat.
     * A novice only ever looks at their WALLET — so every ~minute while connected we
     * sweep each known sender's look-ahead addresses (peer codes from Nostr kind-0
     * profiles) plus any external BIP-47 notification txs, and claim what's funded.
     * This is what makes a received payment appear on its own.
     */
    private fun startPaynymSweep() {
        paynymSweepJob?.cancel()
        val parent = sweepParent ?: return
        paynymSweepJob = viewModelScope.launch(parent) {
            delay(8_000)   // let peers' kind-0 profiles (their payment codes) arrive first
            while (isActive) {
                runCatching { sweepPaynymsOnce() }
                // Failure-aware backoff (1→16 min) + jitter: a saturated server must
                // see FEWER requests, and clients must not synchronize their scans.
                val base = if (sweepFailStreak > 0)
                    (60_000L shl (sweepFailStreak - 1).coerceAtMost(4)) else 60_000L
                delay(base.coerceAtMost(16 * 60_000L) + kotlin.random.Random.nextLong(0, 30_000))
            }
        }
    }

    /** Cheap 0-conf pump: wallet_mempool (fast endpoint) per saved wallet every
     *  ~30 s, injecting any raw txs into the wallet even with its node closed —
     *  incoming sats show in seconds instead of waiting for a CBF scan or the
     *  confirmed sweep. Running nodes poll their own mempool already; skipped. */
    private fun startMempoolPump() {
        val parent = sweepParent ?: return
        viewModelScope.launch(parent) {
            delay(4_000)
            while (isActive) {
                runCatching { pumpWalletMempool() }
                delay(30_000)
            }
        }
    }

    private suspend fun pumpWalletMempool() {
        WalletStore.ensureLoaded(ctx)
        for (meta in WalletStore.wallets.value) {
            val node = WalletSyncManager.getNode(ctx, meta)
            if (node.running) continue           // its own 20 s refreshMempool covers it
            val resp = try { ApiClient.api.walletMempool(meta.address) } catch (e: Exception) { continue }
            if (resp.txs.isEmpty()) continue
            node.seedMempool(resp.txs.map { it.hex to it.seen })
        }
        // Also probe our BIP-47 notification address on the fast endpoint: a cold PayNym
        // sender announcing their code (iOS notification tx) must be discovered in ~30 s,
        // not on the slow 1–16 min sweep backoff or only when RECEIVE is opened. When a
        // notification tx appears, run the full unblind+claim pass. (iOS parity.)
        val notifAddr = com.astrolexis.pyblock.data.crypto.PaymentCode.notificationAddress(ctx)
        if (notifAddr != null) {
            val resp = try { ApiClient.api.walletMempool(notifAddr) } catch (e: Exception) { null }
            if (resp != null && resp.txs.isNotEmpty()) runCatching { PaynymNotifications.scanWith(ctx, emptyMap()) }
        }
    }

    /** One sweep pass. wallet_utxos is EXPENSIVE server-side (UTXO-set scan), so
     *  everything is batched into ONE call: each interacted-with peer's look-ahead
     *  window + our notification address + every external sender's window (+ saved
     *  wallet addresses at a slow cadence — CBF already tracks them). Claims and
     *  balance-credits work off the shared result. Single-flight + TTL: concurrent
     *  or rapid-fire callers coalesce onto the last fresh result. */
    private suspend fun sweepPaynymsOnce() {
        sweepMutex.withLock {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastSweepDoneMs < SWEEP_TTL_MS) return    // a fresh sweep already covered this
            // Only peers the user interacts with — NOT every channel member whose
            // kind-0 profile advertises a code (that set is unbounded).
            val convs = _state.value.conversations.keys
            val peers = _state.value.peerPaynyms.filterKeys { it in convs || it in interestPeers }
            val peerCands = peers.mapValues { (_, code) -> PaymentCode.lookaheadAddresses(ctx, code, 8) }
            val extCands = PaynymNotifications.candidates(ctx)
            val notifAddr = PaymentCode.notificationAddress(ctx)
            WalletStore.ensureLoaded(ctx)
            val candidates = buildList {
                addAll(peerCands.values.flatten().map { it.second })
                addAll(extCands.values.flatten().map { it.second })
                notifAddr?.let { add(it) }
            }
            val walletAddrs = WalletStore.wallets.value.map { it.address }
            // Wallet addresses ride along whenever they don't add a chunk — the
            // server cost is per CALL, not per address. Past one chunk, they drop
            // to a slow cadence (CBF tracks them anyway).
            val includeWallets = candidates.size + walletAddrs.size <= 50 ||
                now - lastWalletSweepMs > WALLET_SWEEP_MS
            val all = if (includeWallets) candidates + walletAddrs else candidates
            android.util.Log.i("PyBLOCKpaynym",
                "sweep: ${peers.size} peer(s), ${extCands.size} ext sender(s), ${all.size} addr(s), wallets=$includeWallets")
            val batch = ConfirmedUtxos.fetch(all)
            if (batch.failed) sweepFailStreak++ else {
                sweepFailStreak = 0
                lastSweepDoneMs = android.os.SystemClock.elapsedRealtime()
                if (includeWallets) lastWalletSweepMs = now
                // Full-coverage sweep REPLACES the map (clears stale/spent entries);
                // a partial one MERGES so unqueried wallets/windows keep their last
                // known UTXOs — critical after a wallet-DB wipe (start()'s
                // self-heal), which the retained entries re-seed on the next pass.
                lastConfirmedUtxos = if (includeWallets) batch.byAddress
                                     else lastConfirmedUtxos + batch.byAddress
            }
            val confirmed = lastConfirmedUtxos
            // Peer-code claims: full window vs confirmed; 0-conf mempool check only
            // at the frontier index (BIP-47 fills indices in order, so the next
            // expected one suffices — no per-address fan-out over the whole window).
            PaynymClaims.mutex.withLock {
                for ((peer, cands) in peerCands) {
                    val code = peers[peer] ?: continue
                    val frontier = PaymentCode.receivedCount(ctx, code)
                    val top = cands.filter { (i, a) ->
                        confirmed[a].orEmpty().isNotEmpty() || (i == frontier && mempoolFunded(a))
                    }.maxOfOrNull { it.first }
                    if (top != null) claimIncomingPaynym(peer, upTo = top + 1)
                }
            }
            // External-sender discovery + claims (takes the claim lock itself).
            PaynymNotifications.scanWith(ctx, confirmed)
            // Server-assisted instant balances for saved wallets (incl. just-claimed ones).
            WalletSyncManager.seedConfirmed(ctx, confirmed)
        }
    }

    private suspend fun mempoolFunded(address: String): Boolean =
        try { ApiClient.api.walletMempool(address).txs.isNotEmpty() } catch (e: Exception) { false }

    override fun onCleared() { disconnect() }

    fun setName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        Nostr.setDisplayName(ctx, trimmed)
        _state.update { it.copy(profiles = it.profiles + (myPubkey to trimmed)) }
        publishMetadata()
    }

    // MARK: Subscriptions

    private fun subscribe(ws: WebSocket) {
        // Both NIP-28 channels (community + Whale Lounge) — routed apart on receive.
        val channel = JSONObject().put("kinds", JSONArray().put(42))
            .put("#e", JSONArray().put(Nostr.channelId).put(Nostr.whaleChannelId)).put("limit", 100)
        ws.send(JSONArray().put("REQ").put("pyblock-chat").put(channel).toString())
        // Encrypted DMs (kind 4): to me + from me (history/echo).
        val toMe = JSONObject().put("kinds", JSONArray().put(4))
            .put("#p", JSONArray().put(myPubkey)).put("limit", 200)
        val fromMe = JSONObject().put("kinds", JSONArray().put(4))
            .put("authors", JSONArray().put(myPubkey)).put("limit", 200)
        ws.send(JSONArray().put("REQ").put("pyblock-dms").put(toMe).put(fromMe).toString())
        // Reactions (kind 7). The relay is app-only, so every reaction targets one
        // of our channel messages — aggregate by its "e" tag on receive.
        val reacts = JSONObject().put("kinds", JSONArray().put(7)).put("limit", 500)
        ws.send(JSONArray().put("REQ").put("pyblock-reacts").put(reacts).toString())
    }

    private fun publishMetadata() {
        // Privacy (audit HIGH): a chat READER must never auto-leak a receive
        // identity. We NEVER auto-broadcast the raw reused on-chain address, and
        // only advertise the BIP-47 PayNym (fresh unlinkable address per payment)
        // when the user opted into "let people pay me in chat". Default: name only.
        val name = Nostr.displayName(ctx) ?: PaynymName.mine(ctx)
        val myPaynym = if (Nostr.shareReceiveInChat(ctx)) PaymentCode.myCode(ctx) else null
        val ev = Nostr.metadataEvent(ctx, name, null, myPaynym,
            com.astrolexis.pyblock.data.store.EntitlementsStore.tierTag,
            color = advertisedColor(), createdAt = now()) ?: return
        broadcast(ev)
    }

    /// Never advertise a color the user isn't entitled to anymore (lapsed sub).
    private fun advertisedColor(): String? =
        if (com.astrolexis.pyblock.data.store.EntitlementsStore.isPro) Nostr.flairColor(ctx) else null

    /** Re-advertise the kind-0 profile (e.g. after picking a flair color). */
    fun republishProfile() { if (sockets.isNotEmpty()) publishMetadata() }

    fun clearRejection() { _state.update { it.copy(lastRejection = null) } }

    /** Surface a transient banner (e.g. image-upload failed); auto-clears after 5s. */
    fun setRejection(msg: String) { _state.update { it.copy(lastRejection = msg) } }

    /** A peer's shared receive address (from their profile), if any. */
    fun addressFor(pubkey: String): String? = _state.value.peerAddresses[pubkey]

    /** Subscription tier flair ("whale"/"pro") for a pubkey — own from the verified
     *  entitlement, peers from their advertised profile. */
    fun tierFor(pubkey: String): String? =
        if (pubkey == myPubkey) com.astrolexis.pyblock.data.store.EntitlementsStore.tierTag
        else _state.value.peerTiers[pubkey]

    /** A peer's BIP-47 payment code (from their profile), if they advertise one. */
    fun paynymFor(pubkey: String): String? = _state.value.peerPaynyms[pubkey]

    /** CHAT FLAIR name color key — own from the verified local entitlement,
     *  peers from their advertised profile. */
    fun colorFor(pubkey: String): String? =
        if (pubkey == myPubkey) advertisedColor() else _state.value.peerColors[pubkey]

    private fun requestProfile(pubkey: String) {
        if (!profileAuthors.add(pubkey)) return
        val filter = JSONObject().put("kinds", JSONArray().put(0))
            .put("authors", JSONArray(profileAuthors.toList()))
        val msg = JSONArray().put("REQ").put("profiles").put(filter).toString()
        for (ws in sockets) ws.send(msg)
    }

    // MARK: Publish

    fun post(content: String, replyTo: NostrEvent? = null, toWhaleLounge: Boolean = false) {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return
        // Lounge posting is a Whale perk — the relay's write policy enforces
        // this too, this is just the client-side seatbelt.
        if (toWhaleLounge && !com.astrolexis.pyblock.data.store.EntitlementsStore.isWhale) return
        _state.update { it.copy(lastRejection = null) }
        val channel = if (toWhaleLounge) Nostr.whaleChannelId else Nostr.channelId
        val tags = mutableListOf(listOf("e", channel, "", "root"))
        if (replyTo != null) {                       // NIP-10 reply markers
            tags.add(listOf("e", replyTo.id, "", "reply"))
            tags.add(listOf("p", replyTo.pubkey))
        }
        val ev = Nostr.makeEvent(ctx, 42, trimmed, tags, now()) ?: return
        if (seen.add(ev.id)) insertMessage(ev)   // optimistic echo
        broadcast(ev)
    }

    /** Reply parent id (NIP-10): an "e" tag explicitly marked "reply". */
    fun replyParentId(ev: NostrEvent): String? =
        ev.tags.firstOrNull { it.size >= 4 && it[0] == "e" && it[3] == "reply" }?.getOrNull(1)

    /** A known channel message by id (either stream) — for reply previews. */
    fun message(id: String): NostrEvent? =
        _state.value.messages.firstOrNull { it.id == id } ?: _state.value.whaleMessages.firstOrNull { it.id == id }

    // MARK: Reactions (NIP-25 kind 7)

    private val pendingReactions = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()

    /** React to a channel message with an emoji. Idempotent per (message, emoji). */
    fun react(messageId: String, author: String, emoji: String) {
        if (_state.value.reactions[messageId]?.get(emoji)?.contains(myPubkey) == true) return
        val ev = Nostr.makeEvent(ctx, 7, emoji,
            listOf(listOf("e", messageId), listOf("p", author)), now()) ?: return
        seen.add(ev.id)
        pendingReactions[ev.id] = messageId to emoji
        addReactionLocal(messageId, emoji, myPubkey)   // optimistic
        broadcast(ev)
    }

    private fun addReaction(ev: NostrEvent) {
        if (!seen.add(ev.id) || isBlocked(ev.pubkey)) return
        val target = ev.tags.lastOrNull { it.firstOrNull() == "e" }?.getOrNull(1) ?: return
        val emoji = if (ev.content.isBlank() || ev.content == "+") "👍" else ev.content
        addReactionLocal(target, emoji, ev.pubkey)
    }

    private fun addReactionLocal(messageId: String, emoji: String, pubkey: String) {
        _state.update { s ->
            val forMsg = s.reactions[messageId] ?: emptyMap()
            val set = (forMsg[emoji] ?: emptySet()) + pubkey
            s.copy(reactions = s.reactions + (messageId to (forMsg + (emoji to set))))
        }
    }

    fun sendDM(peer: String, content: String) {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return
        val t = now()
        val ev = Nostr.makeDMEvent(ctx, peer, trimmed, t) ?: return
        seen.add(ev.id)
        // Read markers ride as DMs but aren't shown as bubbles.
        if (!com.astrolexis.pyblock.data.util.PaymentUri.isReadMarker(trimmed)) {
            insertDM(DMMessage(ev.id, peer, true, trimmed, t))   // optimistic echo
        }
        broadcast(ev)
    }

    private val lastSentRead = HashMap<String, Long>()

    /** Acknowledge that the newest received message in this conversation was read. */
    fun markRead(peer: String) {
        val ts = _state.value.conversations[peer]?.lastOrNull { !it.mine }?.createdAt ?: return
        if ((lastSentRead[peer] ?: 0) >= ts) return
        lastSentRead[peer] = ts
        // Publish into state so the CHAT unread badge clears reactively.
        _state.update { it.copy(myReadUpTo = it.myReadUpTo + (peer to ts)) }
        sendDM(peer, "pyblock:read?ts=$ts")
    }

    /** Import any not-yet-claimed incoming PayNym stealth keys from [peer] as
     *  spendable single-key wallets. Reconciles against the number of "paid"
     *  receipts, so it's robust to a receipt arriving before the peer's payment
     *  code (profile) — safe from either trigger, idempotent, gap-guarded. */
    private fun claimIncomingPaynym(peer: String, upTo: Int? = null) {
        val code = _state.value.peerPaynyms[peer] ?: return
        val receipts = _state.value.conversations[peer].orEmpty()
            .count { !it.mine && it.text.startsWith("pyblock:paid?") }
        val want = maxOf(receipts, upTo ?: 0)
        WalletStore.ensureLoaded(ctx)
        var guard = 0
        while (PaymentCode.receivedCount(ctx, code) < want && guard < 50) {
            guard++
            val k = PaymentCode.nextReceiveKey(ctx, code) ?: break
            if (WalletStore.wallets.value.any { it.address == k.address }) {
                PaymentCode.didReceive(ctx, code, k.index); continue      // already have it; advance
            }
            val w = VanityWallet(java.util.UUID.randomUUID().toString(),
                "PayNym ← ${name(peer)}", k.address, true, PaymentCode.RECEIVE_BIRTHDAY)
            if (!WalletStore.add(ctx, w, k.wif)) break
            PaymentCode.didReceive(ctx, code, k.index)
        }
    }

    /** Look-ahead robustness on DM open: check [peer]'s window against the LAST
     *  batched sweep result (no private wallet_utxos scan — that call is too
     *  expensive per-conversation) plus a single frontier-index 0-conf probe. If a
     *  payment whose `pyblock:paid` DM never arrived is found, claim keys up to it.
     *  Registers the peer so future sweeps cover their window, and falls back to
     *  the shared TTL-guarded sweep when the cache had nothing. */
    suspend fun scanPaynymLookahead(peer: String, gap: Int = 8) {
        val code = _state.value.peerPaynyms[peer] ?: return
        interestPeers.add(peer)
        val cands = PaymentCode.lookaheadAddresses(ctx, code, gap)
        val confirmed = lastConfirmedUtxos
        val frontier = PaymentCode.receivedCount(ctx, code)
        val top = cands.filter { (i, a) ->
            confirmed[a].orEmpty().isNotEmpty() || (i == frontier && mempoolFunded(a))
        }.maxOfOrNull { it.first }
        if (top != null) PaynymClaims.mutex.withLock { claimIncomingPaynym(peer, upTo = top + 1) }
        else scanAllPaynymLookahead()   // cache had nothing for this peer → shared sweep picks it up
    }

    /** Full sweep on demand (DM list open) — coalesces onto the background sweep
     *  (single-flight + TTL) and dies with it at ON_STOP. */
    fun scanAllPaynymLookahead() {
        val parent = sweepParent ?: return
        viewModelScope.launch(parent) { runCatching { sweepPaynymsOnce() } }
    }

    private fun broadcast(ev: NostrEvent) {
        val msg = JSONArray().put("EVENT").put(eventJson(ev)).toString()
        for (ws in sockets) ws.send(msg)
    }

    // MARK: Receive

    private inner class Listener : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            subscribe(ws)
            _state.update { it.copy(connected = true) }
            publishMetadata()
        }
        override fun onMessage(ws: WebSocket, text: String) = handle(text)
        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            _state.update { it.copy(connected = false) }
        }
        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            _state.update { it.copy(connected = false) }
        }
    }

    private fun handle(text: String) {
        val arr = try { JSONArray(text) } catch (e: Exception) { return }
        // Relay refused one of our events → roll back the optimistic echo and
        // surface the reason, instead of lying that it was sent.
        if (arr.optString(0) == "OK" && arr.length() >= 3 && !arr.optBoolean(2, true)) {
            val evId = arr.optString(1)
            // A rejected reaction: keep the optimistic chip so the user sees their
            // own reaction, no scary banner. (Relay allows kind-7 now; belt-and-braces.)
            if (pendingReactions.remove(evId) != null) return
            val reason = arr.optString(3).ifEmpty { "rejected by relay" }
            seen.remove(evId)
            _state.update { s ->
                s.copy(
                    messages = s.messages.filter { it.id != evId },
                    whaleMessages = s.whaleMessages.filter { it.id != evId },
                    conversations = s.conversations.mapValues { (_, list) -> list.filter { it.id != evId } },
                    lastRejection = reason,
                )
            }
            return
        }
        if (arr.length() < 3 || arr.optString(0) != "EVENT") return
        val ev = decode(arr.optJSONObject(2) ?: return) ?: return
        when (ev.kind) {
            0 -> {
                parseName(ev.content)?.let { n ->
                    if (n.isNotEmpty()) _state.update { it.copy(profiles = it.profiles + (ev.pubkey to n)) }
                }
                parseBtc(ev.content)?.let { addr ->
                    _state.update { it.copy(peerAddresses = it.peerAddresses + (ev.pubkey to addr)) }
                }
                parseTier(ev.content)?.let { t ->
                    _state.update { it.copy(peerTiers = it.peerTiers + (ev.pubkey to t)) }
                }
                parseColor(ev.content)?.let { c ->
                    _state.update { it.copy(peerColors = it.peerColors + (ev.pubkey to c)) }
                }
                parsePaynym(ev.content)?.let { pc ->
                    val isNew = _state.value.peerPaynyms[ev.pubkey] != pc
                    _state.update { it.copy(peerPaynyms = it.peerPaynyms + (ev.pubkey to pc)) }
                    // The code may arrive after a receipt already did — reconcile now.
                    // Off the WS reader thread + under the claim lock: claims are
                    // check-then-act over shared state and must never interleave.
                    if (isNew) viewModelScope.launch {
                        PaynymClaims.mutex.withLock { claimIncomingPaynym(ev.pubkey) }
                    }
                }
            }
            42 -> if (seen.add(ev.id)) { insertMessage(ev); requestProfile(ev.pubkey) }
            7 -> addReaction(ev)
            // Decrypt FIRST (guarded — secretKey() can throw on a transient Keystore
            // failure) and mark the event seen only after a successful decrypt, so a
            // transient blip doesn't silently drop the DM/receipt for the session.
            4 -> runCatching { Nostr.decryptDM(ctx, ev, myPubkey) }.getOrNull()?.let { (peer, textDec) ->
                if (!seen.add(ev.id)) return@let
                // Collaborative Send (PayJoin) messages ride as encrypted DMs but are
                // orchestration, not chat — route them and never show a bubble.
                if (PayJoin.looksLikePayJoin(textDec)) {
                    PayJoinCoordinator.handleIncoming(peer, ev.pubkey == myPubkey, textDec)
                    return@let
                }
                // Read receipts ride as encrypted DMs but aren't shown as bubbles.
                if (com.astrolexis.pyblock.data.util.PaymentUri.isReadMarker(textDec)) {
                    if (ev.pubkey != myPubkey) {
                        com.astrolexis.pyblock.data.util.PaymentUri.readMarkerTs(textDec)?.let { ts ->
                            _state.update { s ->
                                s.copy(readUpTo = s.readUpTo + (peer to maxOf(s.readUpTo[peer] ?: 0, ts)))
                            }
                        }
                    }
                } else {
                    val mine = ev.pubkey == myPubkey
                    insertDM(DMMessage(ev.id, peer, mine, textDec, ev.created_at))
                    // Receipt-triggered PayNym receive: an incoming "paid" notice
                    // means a fresh stealth address of mine was funded — import it.
                    if (!mine && textDec.startsWith("pyblock:paid?") && _state.value.peerPaynyms[peer] != null) {
                        viewModelScope.launch { PaynymClaims.mutex.withLock { claimIncomingPaynym(peer) } }
                    }
                    requestProfile(peer)
                }
            }
        }
    }

    /** Which NIP-28 channel a kind-42 event belongs to (by its root e-tag). */
    private fun isWhaleEvent(ev: NostrEvent): Boolean =
        ev.tags.any { it.size >= 2 && it[0] == "e" && it[1] == Nostr.whaleChannelId }

    // MARK: Moderation (store-policy parity) — block / report

    private fun modPrefs() = ctx.getSharedPreferences("pyblock_moderation", android.content.Context.MODE_PRIVATE)

    /** Load persisted block/report sets into state (call at init). */
    fun loadModeration() {
        val p = modPrefs()
        _state.update {
            it.copy(
                blockedUsers = p.getStringSet("blocked", emptySet())?.toSet() ?: emptySet(),
                reportedIds = p.getStringSet("reported", emptySet())?.toSet() ?: emptySet(),
            )
        }
    }

    fun isBlocked(pubkey: String): Boolean = _state.value.blockedUsers.contains(pubkey)

    /** Block a user: hide all their content now + persist + notify the developer. */
    fun blockUser(pubkey: String) {
        if (pubkey == myPubkey) return
        val next = _state.value.blockedUsers + pubkey
        modPrefs().edit().putStringSet("blocked", next).apply()
        _state.update { s ->
            s.copy(
                blockedUsers = next,
                messages = s.messages.filter { it.pubkey != pubkey },
                whaleMessages = s.whaleMessages.filter { it.pubkey != pubkey },
                conversations = s.conversations - pubkey,
            )
        }
        viewModelScope.launch { runCatching { ProRepo.moderationReport("block", pubkey, null) } }
    }

    fun unblockUser(pubkey: String) {
        val next = _state.value.blockedUsers - pubkey
        modPrefs().edit().putStringSet("blocked", next).apply()
        _state.update { it.copy(blockedUsers = next) }
    }

    /** Report a message: hide it + persist + notify the developer. */
    fun reportMessage(id: String, pubkey: String) {
        val next = _state.value.reportedIds + id
        modPrefs().edit().putStringSet("reported", next).apply()
        _state.update { s ->
            s.copy(
                reportedIds = next,
                messages = s.messages.filter { it.id != id },
                whaleMessages = s.whaleMessages.filter { it.id != id },
                conversations = s.conversations.mapValues { (_, l) -> l.filter { it.id != id } },
            )
        }
        viewModelScope.launch { runCatching { ProRepo.moderationReport("report", pubkey, id) } }
    }

    private fun insertMessage(ev: NostrEvent) {
        // Moderation: never surface blocked users or reported messages.
        val s0 = _state.value
        if (s0.blockedUsers.contains(ev.pubkey) || s0.reportedIds.contains(ev.id)) return
        _state.update { s ->
            if (isWhaleEvent(ev)) {
                val list = (s.whaleMessages + ev).sortedBy { it.created_at }.let {
                    if (it.size > 500) it.drop(it.size - 500) else it
                }
                s.copy(whaleMessages = list)
            } else {
                val list = (s.messages + ev).sortedBy { it.created_at }.let {
                    if (it.size > 500) it.drop(it.size - 500) else it
                }
                s.copy(messages = list)
            }
        }
    }

    private fun insertDM(m: DMMessage) {
        // Moderation: drop DMs from blocked users / reported messages.
        val s0 = _state.value
        if (!m.mine && s0.blockedUsers.contains(m.peer)) return
        if (s0.reportedIds.contains(m.id)) return
        _state.update { s ->
            val existing = s.conversations[m.peer] ?: emptyList()
            if (existing.any { it.id == m.id }) return@update s
            val list = (existing + m).sortedBy { it.createdAt }
            s.copy(conversations = s.conversations + (m.peer to list))
        }
    }

    // MARK: JSON

    private fun eventJson(ev: NostrEvent): JSONObject {
        val tags = JSONArray()
        for (t in ev.tags) { val a = JSONArray(); for (s in t) a.put(s); tags.put(a) }
        return JSONObject().put("id", ev.id).put("pubkey", ev.pubkey)
            .put("created_at", ev.created_at).put("kind", ev.kind)
            .put("tags", tags).put("content", ev.content).put("sig", ev.sig)
    }

    private fun decode(d: JSONObject): NostrEvent? = try {
        val tagsArr = d.optJSONArray("tags") ?: JSONArray()
        val tags = ArrayList<List<String>>()
        for (i in 0 until tagsArr.length()) {
            val ta = tagsArr.optJSONArray(i) ?: continue
            tags.add((0 until ta.length()).map { ta.optString(it) })
        }
        NostrEvent(
            id = d.getString("id"), pubkey = d.getString("pubkey"),
            created_at = d.getLong("created_at"), kind = d.getInt("kind"),
            tags = tags, content = d.getString("content"), sig = d.optString("sig"),
        )
    } catch (e: Exception) { null }

    private fun parseName(content: String): String? = try {
        val o = JSONObject(content)
        (o.optString("display_name").takeIf { it.isNotEmpty() }) ?: o.optString("name").takeIf { it.isNotEmpty() }
    } catch (e: Exception) { null }

    private fun parseBtc(content: String): String? = try {
        JSONObject(content).optString("btc").takeIf { it.isNotEmpty() }
    } catch (e: Exception) { null }

    private fun parsePaynym(content: String): String? = try {
        JSONObject(content).optString("paynym").takeIf { it.startsWith("PM") }
    } catch (e: Exception) { null }

    private fun parseTier(content: String): String? = try {
        JSONObject(content).optString("tier").takeIf { it.isNotEmpty() }
    } catch (e: Exception) { null }

    private fun parseColor(content: String): String? = try {
        JSONObject(content).optString("color").takeIf { it.isNotEmpty() }
    } catch (e: Exception) { null }

    private fun now(): Long = System.currentTimeMillis() / 1000
}
