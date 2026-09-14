package com.astrolexis.pyblock.data.nostr

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.astrolexis.pyblock.data.crypto.PaymentCode
import com.astrolexis.pyblock.data.crypto.PaynymClaims
import com.astrolexis.pyblock.data.crypto.PaynymName
import com.astrolexis.pyblock.data.crypto.PaynymBook
import com.astrolexis.pyblock.data.crypto.PaynymNotifications
import com.astrolexis.pyblock.data.model.Utxo
import com.astrolexis.pyblock.data.wallet.VanityWallet
import com.astrolexis.pyblock.data.wallet.WalletStore
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
    val peerForges: Map<String, String> = emptyMap(),        // pubkey → how their runes are drawn
    val peerSigils: Map<String, String> = emptyMap(),        // pubkey → bindrune, rune names comma-separated
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
    /** Text of a message the relay refused, for the composer to put back. */
    val rejectedDraft: String? = null,
    /** pubkey → runes the server certifies they earned mining. */
    val marks: Map<String, List<com.astrolexis.pyblock.data.blake.BlakeApi.Mark>> = emptyMap(),
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
    @Volatile private var sweepFailStreak = 0
    @Volatile private var lastConfirmedUtxos: Map<String, List<Utxo>> = emptyMap()
    // Peers whose DMs the user actually opened — their windows join the sweep.
    private val interestPeers = java.util.Collections.synchronizedSet(HashSet<String>())

    // Backfill.
    //
    // On connect the relay replays history: channel messages, hundreds of reactions and DMs, one
    // frame each. Every one of those used to rebuild the whole UI state with .copy(), so opening
    // the chat recomposed the list hundreds of times in a second — and because events arrive
    // newest-first, the LAST message changed identity on nearly every one. The screen scrolls to
    // the last message, so it chased a moving target through the entire replay. That is the scroll
    // "behaving oddly when messages load". The replay is now collected aside and applied once.
    private val backfillSubs = java.util.Collections.synchronizedSet(HashSet<String>())
    @Volatile private var backfilling = false
    private val pendingMessages = java.util.Collections.synchronizedList(ArrayList<NostrEvent>())
    private val pendingDMs = java.util.Collections.synchronizedList(ArrayList<DMMessage>())
    private var backfillDeadline: Job? = null

    // Reconnect. Nothing used to bring the socket back: onFailure/onClosed dropped it and that was
    // that, so a relay restart or a spell in the background left the chat dead until the user
    // happened to leave the tab and return.
    @Volatile private var wantsConnection = false
    @Volatile private var reconnectAttempt = 0
    private var reconnectJob: Job? = null

    private val ctx get() = getApplication<Application>()

    /** Write the archive, coalesced — a backfill touches conversations hundreds of times. */
    private var archiveJob: Job? = null
    private fun scheduleArchive() {
        if (archiveJob != null) return
        archiveJob = viewModelScope.launch {
            delay(800)
            archiveJob = null
            val snapshot = _state.value.conversations
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { DMArchive.save(ctx, snapshot) }
        }
    }

    /** Newest DM we hold, so the subscription can ask only for what came after it. */
    private fun newestDMTimestamp(): Long =
        _state.value.conversations.values.mapNotNull { it.lastOrNull()?.createdAt }.maxOrNull() ?: 0L

    init {
        loadModeration()
        // Conversations from disk, before anything asks the relay. Everything already stored must
        // not be re-inserted when the replay brings it back.
        val stored = DMArchive.load(ctx)
        if (stored.isNotEmpty()) {
            for (list in stored.values) for (m in list) seen.add(m.id)
            _state.update { it.copy(conversations = stored) }
        }
        // Collaborative Send (PayJoin) transport: outbound DM + name lookup. Inbound
        // pj-* DMs are routed to the coordinator from the kind-4 handler below.
        PayJoinCoordinator.attach(ctx, object : PayJoinCoordinator.Transport {
            override fun sendDM(peer: String, content: String) = this@NostrClient.sendDM(peer, content)
            override fun nameFor(pubkey: String): String = name(pubkey)
        })
    }

    /**
     * Is this event worth looking at at all?
     *
     * One relay stands between this app and everyone else in the room, so an event is a claim until
     * its signature says otherwise. And created_at is written by whoever sent it: it is the sort
     * key, so one message dated next year sits at the bottom of everybody's room forever.
     */
    private val verifiedIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private fun accept(ev: NostrEvent): Boolean {
        val now = System.currentTimeMillis() / 1000
        if (ev.created_at > now + 900) return false      // 15 min of clock skew, no more
        if (verifiedIds.contains(ev.id)) return true
        if (!Nostr.verify(ev)) return false
        if (verifiedIds.size > 4_000) verifiedIds.clear()
        verifiedIds.add(ev.id)
        return true
    }

    /** Learn that `peer` pays and receives under `code`. Every path that discovers a code — the
     *  public profile, a private hello, a receipt — goes through here, and the person becomes a
     *  contact by themselves, under their chat name. Nobody adds anyone by hand. */
    private fun rememberPeerCode(peer: String, code: String) {
        if (!code.startsWith("PM") || PaymentCode.decode(code) == null) return
        _state.update { s -> s.copy(peerPaynyms = s.peerPaynyms + (peer to code)) }
        PaynymBook.autoLabel(ctx, code, _state.value.profiles[peer], nostr = peer)
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
        wantsConnection = true
        openSockets()
    }

    private fun openSockets() {
        if (!wantsConnection || sockets.isNotEmpty()) return
        reconnectJob?.cancel(); reconnectJob = null
        for (r in relays) {
            val req = Request.Builder().url(r).build()
            sockets.add(http.newWebSocket(req, Listener()))
        }
        Nostr.displayName(ctx)?.let { name -> _state.update { it.copy(profiles = it.profiles + (myPubkey to name)) } }
        if (sweepParent == null) sweepParent = SupervisorJob()
        startPaynymSweep()
        startMempoolPump()
    }

    fun disconnect() {
        wantsConnection = false
        reconnectJob?.cancel(); reconnectJob = null
        closeSockets()
        paynymSweepJob?.cancel(); paynymSweepJob = null
        sweepParent?.cancel(); sweepParent = null    // stops one-shot scans too, not just the loop
    }

    private fun closeSockets() {
        for (ws in sockets) ws.close(1000, null)
        sockets.clear()
        _state.update { it.copy(connected = false) }
    }

    /**
     * Back off so a relay that is down isn't hammered, but recover fast from a blip. Capped at 30
     * seconds, with jitter so every phone in the room doesn't retry on the same tick.
     */
    private fun scheduleReconnect() {
        if (!wantsConnection || reconnectJob != null) return
        val step = minOf(30_000L, 1_000L shl minOf(reconnectAttempt, 5))
        reconnectAttempt++
        val delayMs = step + (0..1_500).random()
        reconnectJob = viewModelScope.launch {
            delay(delayMs)
            reconnectJob = null
            openSockets()
        }
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
        // The saved wallets used to be pumped here too, into the BDK node's mempool view. That node
        // is Bitcoin mainnet (BdkNode builds it on Network.BITCOIN and dials a :8333 peer) and none
        // of its screens are reachable in this app, so the pump asked the Bitcoin mempool about
        // BLAKE2b addresses every 30 seconds and fed the answer to a wallet nobody can see. Worse
        // than useless: with so much of this chain's traffic replayed from Bitcoin, those addresses
        // really do have coins over there, and a hit fires a "received" notification for a payment
        // that was never made here. Real 0-conf comes from BlakeBalanceStore.pollMempool, on the
        // right chain.
        //
        // What is kept is the BIP-47 notification address on the fast endpoint: a cold PayNym
        // sender announcing their code (iOS notification tx) must be discovered in ~30 s,
        // not on the slow 1–16 min sweep backoff or only when RECEIVE is opened. When a
        // notification tx appears, run the full unblind+claim pass. (iOS parity.)
        val notifAddr = PaymentCode.notificationAddress(ctx)
        if (notifAddr != null) {
            val resp = try { ApiClient.api.walletMempool(notifAddr, PaynymNotifications.CHAIN) } catch (e: Exception) { null }
            if (resp != null && resp.txs.isNotEmpty()) runCatching { PaynymNotifications.sweep(ctx) }
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
            // Widen to both derivation schemes from index 0 while the one-time post-upgrade sweep
            // is still pending, so a coin paid under the pre-0.2.4 scheme surfaces on its own.
            val wide = PaynymNotifications.fullSweepPending(ctx)
            val peerCands = peers.mapValues { (_, code) ->
                PaymentCode.candidates(ctx, code, PaynymNotifications.GAP, wide)
            }
            val extCands = PaynymNotifications.candidates(ctx)
            val notifAddr = PaymentCode.notificationAddress(ctx)
            WalletStore.ensureLoaded(ctx)
            val candidates = buildList {
                addAll(peerCands.values.flatten().map { it.address })
                addAll(extCands.values.flatten().map { it.address })
                notifAddr?.let { add(it) }
            }
            // PayNym windows are asked of THIS app's chain. They used to ride in one call with the
            // saved wallet addresses, whose answer went to the Bitcoin BDK node — so the call went
            // out with no chain at all, was served by the Bitcoin node, and every BLAKE2b payment to
            // a derived address came back unfunded and was never claimed. The wallet half is gone:
            // that node is unreachable here and BlakeBalanceStore owns the real balance. What is
            // left is one question, asked of one chain.
            android.util.Log.i("PyBLOCKpaynym",
                "sweep: ${peers.size} peer(s), ${extCands.size} ext sender(s), ${candidates.size} addr(s)")
            val batch = ConfirmedUtxos.fetch(candidates, PaynymNotifications.CHAIN)
            if (batch.failed) sweepFailStreak++ else {
                sweepFailStreak = 0
                lastSweepDoneMs = android.os.SystemClock.elapsedRealtime()
                // The batch covers every candidate window in full, so it REPLACES the map and
                // stale/spent entries go with it.
                lastConfirmedUtxos = batch.byAddress
            }
            val confirmed = lastConfirmedUtxos
            // Peer-code claims: full window vs confirmed; the 0-conf mempool check stays near the
            // frontier (BIP-47 fills indices in order) — no per-address fan-out over the window.
            PaynymClaims.mutex.withLock {
                for ((peer, cands) in peerCands) {
                    val code = peers[peer] ?: continue
                    val frontier = PaymentCode.receivedCount(ctx, code)
                    val top = cands.filter {
                        it.scheme == PaymentCode.Scheme.BIP47 &&
                            (confirmed[it.address].orEmpty().isNotEmpty() ||
                                (nearFrontier(it.index, frontier) && mempoolFunded(it.address)))
                    }.maxOfOrNull { it.index }
                    if (top != null) claimIncomingPaynym(peer, upTo = top + 1)
                    // Legacy-scheme hits are imported directly. The frontier counts spec-scheme
                    // receipts, so claiming "up to" a legacy index would mint spec wallets for
                    // indices nobody paid and push the frontier past coins still unclaimed.
                    for (c in cands) {
                        if (c.scheme != PaymentCode.Scheme.LEGACY) continue
                        if (confirmed[c.address].orEmpty().isEmpty()) continue
                        if (WalletStore.wallets.value.any { it.address == c.address }) continue
                        val k = PaymentCode.receiveKeyAt(ctx, code, c.index, c.scheme) ?: continue
                        val w = VanityWallet(java.util.UUID.randomUUID().toString(),
                            "from ${name(peer)} (legacy)", k.address, true, PaymentCode.RECEIVE_BIRTHDAY)
                        WalletStore.add(ctx, w, k.wif)
                    }
                }
            }
            // External-sender discovery + claims (takes the claim lock itself, fetches its own chain).
            PaynymNotifications.sweep(ctx)
        }
    }

    /** Indices worth a 0-conf probe: the next one expected, plus a few — a sender who paid twice
     *  before we last swept, or who reinstalled, is already ahead of our counter. */
    private fun nearFrontier(index: Int, frontier: Int): Boolean =
        index >= frontier && index < frontier + 5

    /** An unconfirmed tx that PAYS this address. `wallet_mempool` answers for every tx that
     *  touches it, spends included, so a non-empty list is not by itself an incoming payment. */
    private suspend fun mempoolFunded(address: String): Boolean =
        try {
            ApiClient.api.walletMempool(address, PaynymNotifications.CHAIN).txs.any {
                com.astrolexis.pyblock.data.wallet.MempoolParse.incomingSats(address, it.hex) > 0L
            }
        } catch (e: Exception) { false }

    override fun onCleared() { disconnect() }

    fun setName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        Nostr.setDisplayName(ctx, trimmed)
        _state.update { it.copy(profiles = it.profiles + (myPubkey to trimmed)) }
        publishMetadata()
    }

    // MARK: Backfill

    private fun beginBackfill(subs: List<String>) {
        backfilling = true
        backfillSubs.clear(); backfillSubs.addAll(subs)
        backfillDeadline?.cancel()
        // A relay that never sends EOSE must not leave the chat empty forever.
        backfillDeadline = viewModelScope.launch {
            delay(4_000)
            endBackfill()
        }
    }

    private fun backfillEOSE(sub: String) {
        backfillSubs.remove(sub)
        if (backfillSubs.isEmpty()) endBackfill()
    }

    /** Apply everything collected during the replay as ONE state change. */
    private fun endBackfill() {
        if (!backfilling) return
        backfilling = false
        backfillDeadline?.cancel(); backfillDeadline = null

        val msgs = synchronized(pendingMessages) { pendingMessages.toList().also { pendingMessages.clear() } }
        val dms = synchronized(pendingDMs) { pendingDMs.toList().also { pendingDMs.clear() } }
        if (msgs.isEmpty() && dms.isEmpty()) return

        _state.update { s ->
            var community = s.messages
            var whale = s.whaleMessages
            if (msgs.isNotEmpty()) {
                val (w, c) = msgs.partition { isWhaleEvent(it) }
                if (c.isNotEmpty()) community = (community + c).sortedWith(EVENT_ORDER).takeLastCapped()
                if (w.isNotEmpty()) whale = (whale + w).sortedWith(EVENT_ORDER).takeLastCapped()
            }
            var convs = s.conversations
            if (dms.isNotEmpty()) {
                val grouped = dms.groupBy { it.peer }
                val next = convs.toMutableMap()
                for ((peer, list) in grouped) {
                    val merged = ((next[peer] ?: emptyList()) + list)
                        .distinctBy { it.id }
                        .sortedBy { it.createdAt }
                    next[peer] = if (merged.size > DMArchive.PER_PEER_LIMIT)
                        merged.drop(merged.size - DMArchive.PER_PEER_LIMIT) else merged
                }
                convs = next
            }
            s.copy(messages = community, whaleMessages = whale, conversations = convs)
        }
        if (dms.isNotEmpty()) scheduleArchive()
        val authors = _state.value.messages.map { it.pubkey }.toSet()
        requestProfiles(authors)
        refreshMarks(authors)
    }

    private fun List<NostrEvent>.takeLastCapped(): List<NostrEvent> =
        if (size > 500) drop(size - 500) else this

    // MARK: Subscriptions

    private fun subscribe(ws: WebSocket) {
        // Both NIP-28 channels (community + Whale Lounge) — routed apart on receive.
        val channel = JSONObject().put("kinds", JSONArray().put(42))
            .put("#e", JSONArray().put(Nostr.channelId).put(Nostr.whaleChannelId)).put("limit", 100)
        ws.send(JSONArray().put("REQ").put("pyblock-chat").put(channel).toString())
        // Encrypted DMs (kind 4): to me + from me (history/echo).
        // Only what we don't already have on disk, with an hour of overlap for clock skew — the
        // duplicate check drops anything that comes back twice. Asking for the last 200 every time
        // meant re-decrypting a conversation the app already knew by heart, on every launch, and it
        // is why the relay could never be allowed to forget a DM.
        val since = newestDMTimestamp()
        val toMe = JSONObject().put("kinds", JSONArray().put(4))
            .put("#p", JSONArray().put(myPubkey)).put("limit", 200)
        val fromMe = JSONObject().put("kinds", JSONArray().put(4))
            .put("authors", JSONArray().put(myPubkey)).put("limit", 200)
        if (since > 0) {
            toMe.put("since", since - 3600)
            fromMe.put("since", since - 3600)
        }
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
        val forge = advertisedForge(); val sigil = advertisedSigil()
        val ev = Nostr.metadataEvent(ctx, name, null, myPaynym,
            com.astrolexis.pyblock.data.store.EntitlementsStore.tierTag,
            color = advertisedColor(), forge = forge, sigil = sigil, createdAt = now()) ?: return
        broadcast(ev)
        // Apply to ourselves at once: the room only learns a profile from the relay and never
        // re-asks for one it already has, so a change reached everyone's screen but the owner's.
        _state.update { s -> s.copy(
            profiles = s.profiles + (myPubkey to name),
            peerColors = advertisedColor()?.let { s.peerColors + (myPubkey to it) } ?: (s.peerColors - myPubkey),
            peerForges = s.peerForges + (myPubkey to forge),
            peerSigils = if (sigil.isEmpty()) s.peerSigils - myPubkey else s.peerSigils + (myPubkey to sigil)) }
    }

    /** Never advertise a forge or a sigil the user isn't entitled to anymore (lapsed sub). */
    private fun advertisedForge(): String {
        val f = Nostr.forge(ctx)
        return if (f == "tempered" && !com.astrolexis.pyblock.data.store.EntitlementsStore.isWhale) "cast" else f
    }
    private fun advertisedSigil(): String {
        val limit = if (com.astrolexis.pyblock.data.store.EntitlementsStore.isWhale) 4
            else if (com.astrolexis.pyblock.data.store.EntitlementsStore.isPro) 3 else 0
        return Nostr.sigil(ctx).split(",").filter { it.isNotBlank() }.take(limit).joinToString(",")
    }
    fun setForge(v: String) { Nostr.setForge(ctx, v); publishMetadata() }
    fun setSigil(runes: List<String>) { Nostr.setSigil(ctx, runes.joinToString(",")); publishMetadata() }
    fun setColor(v: String) { Nostr.setFlairColor(ctx, v); publishMetadata() }
    fun forgeFor(pubkey: String): String = _state.value.peerForges[pubkey] ?: "cast"
    fun sigilFor(pubkey: String): String = _state.value.peerSigils[pubkey] ?: ""

    /// Never advertise a color the user isn't entitled to anymore (lapsed sub).
    private fun advertisedColor(): String? =
        if (com.astrolexis.pyblock.data.store.EntitlementsStore.isPro) Nostr.flairColor(ctx) else null

    /** Re-advertise the kind-0 profile (e.g. after picking a flair color). */
    fun republishProfile() { if (sockets.isNotEmpty()) publishMetadata() }

    fun clearRejection() { _state.update { it.copy(lastRejection = null) } }
    fun consumeRejectedDraft() { _state.update { it.copy(rejectedDraft = null) } }

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
        sendProfileRequest()
    }

    /** Everyone at once. After a backfill this used to re-send the growing REQ once per author. */
    private fun requestProfiles(pubkeys: Collection<String>) {
        if (!profileAuthors.addAll(pubkeys)) return
        sendProfileRequest()
    }

    /** Marks for a set of authors, at most once an hour each. One call for the batch. */
    private val marksFetchedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private fun refreshMarks(pubkeys: Collection<String>) {
        val now = System.currentTimeMillis()
        val stale = pubkeys.filter { now - (marksFetchedAt[it] ?: 0L) > 3_600_000L }
        if (stale.isEmpty()) return
        for (pk in stale) marksFetchedAt[pk] = now
        viewModelScope.launch {
            val got = runCatching { com.astrolexis.pyblock.data.blake.BlakeApi.marks(stale) }.getOrDefault(emptyMap())
            if (got.isNotEmpty()) _state.update { s -> s.copy(marks = s.marks + got) }
        }
    }

    private fun sendProfileRequest() {
        val filter = JSONObject().put("kinds", JSONArray().put(0))
            .put("authors", JSONArray(profileAuthors.toList().takeLast(200)))
        val msg = JSONArray().put("REQ").put("profiles").put(filter).toString()
        for (ws in sockets) ws.send(msg)
    }

    // MARK: Publish

    fun post(content: String, replyTo: NostrEvent? = null, toWhaleLounge: Boolean = false) {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return
        // No length limit at all meant one person could post pages of text everyone had to scroll past.
        if (trimmed.length > 2_000) { setRejection("that message is too long — keep it under 2,000 characters"); return }
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
        // "Emoji" was whatever arrived: a 500-character reaction destroyed the bubble for everyone
        // who loaded it.
        val raw = ev.content.trim()
        val emoji = if (raw.isBlank() || raw == "+") "👍" else raw
        if (emoji.length > 8 || emoji.any { it == '\n' || it == '\r' }) return
        addReactionLocal(target, emoji, ev.pubkey)
    }

    private fun addReactionLocal(messageId: String, emoji: String, pubkey: String) {
        _state.update { s ->
            val forMsg = s.reactions[messageId] ?: emptyMap()
            val set = (forMsg[emoji] ?: emptySet()) + pubkey
            s.copy(reactions = s.reactions + (messageId to (forMsg + (emoji to set))))
        }
    }

    /**
     * Hand a peer our payment code, privately, the first time we write to them.
     *
     * This is what BIP-47's on-chain notification does, done over the encrypted DM instead: the
     * other side learns the code it needs to pay us and to find what we pay them, without a
     * transaction and without anyone having to "add a contact". Until now that code only moved
     * through the public profile, and only if the user had opted in — which is why a payment could
     * arrive and be undiscoverable. Once per peer; a control message, never shown. Mirrors iOS.
     */
    private fun introducedPrefs() = ctx.getSharedPreferences("pyblock_paynym_intro", android.content.Context.MODE_PRIVATE)
    private fun introduce(peer: String) {
        if (!Nostr.shareReceiveInChat(ctx)) return
        val p = introducedPrefs()
        if (p.getBoolean(peer, false)) return
        val code = runCatching { PaymentCode.myCode(ctx) }.getOrNull() ?: return
        if (!code.startsWith("PM")) return
        p.edit().putBoolean(peer, true).apply()
        sendDM(peer, "pyblock:hello?code=$code")
    }

    fun sendDM(peer: String, content: String) {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return
        if (!trimmed.startsWith("pyblock:")) introduce(peer)   // a real message: say who we are first
        val t = now()
        val ev = Nostr.makeDMEvent(ctx, peer, trimmed, t) ?: return
        seen.add(ev.id)
        // Control messages ride as DMs but aren't shown as bubbles.
        if (!com.astrolexis.pyblock.data.util.PaymentUri.isControl(trimmed)) {
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
                "from ${name(peer)}", k.address, true, PaymentCode.RECEIVE_BIRTHDAY)
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
    suspend fun scanPaynymLookahead(peer: String, gap: Int = PaynymNotifications.GAP) {
        val code = _state.value.peerPaynyms[peer] ?: return
        interestPeers.add(peer)
        val cands = PaymentCode.candidates(ctx, code, gap, full = false)
        val confirmed = lastConfirmedUtxos
        val frontier = PaymentCode.receivedCount(ctx, code)
        val top = cands.filter {
            confirmed[it.address].orEmpty().isNotEmpty() ||
                (nearFrontier(it.index, frontier) && mempoolFunded(it.address))
        }.maxOfOrNull { it.index }
        if (top != null) PaynymClaims.mutex.withLock { claimIncomingPaynym(peer, upTo = top + 1) }
        else scanAllPaynymLookahead()   // cache had nothing for this peer → shared sweep picks it up
    }

    /** Full sweep on demand (DM list open) — coalesces onto the background sweep
     *  (single-flight + TTL) and dies with it at ON_STOP. */
    fun scanAllPaynymLookahead() {
        val parent = sweepParent ?: return
        viewModelScope.launch(parent) { runCatching { sweepPaynymsOnce() } }
    }

    // Events awaiting a live socket. Without this, a message posted while the socket was down/
    // reconnecting was silently dropped (only the optimistic echo showed, so it looked "sent" to
    // you but never reached the relay). Queued events flush on the next open.
    private val outbox = mutableListOf<NostrEvent>()

    private fun broadcast(ev: NostrEvent) {
        outbox.add(ev)
        flushOutbox()
    }

    /** Send everything queued. If there's no live socket, (re)connect — onOpen flushes again. */
    private fun flushOutbox() {
        val ws = sockets.firstOrNull()
        if (ws == null) { connect(); return }
        val pending = outbox.toList(); outbox.clear()
        for (ev in pending) {
            // send() returns false when the socket is closing or its queue is full. The result used
            // to be ignored, so a message handed to a dying socket was gone from both the socket
            // and the queue while the optimistic echo showed it as sent.
            if (!ws.send(JSONArray().put("EVENT").put(eventJson(ev)).toString())) {
                outbox.add(ev)
                scheduleReconnect()
            }
        }
    }

    // MARK: Receive

    private inner class Listener : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            reconnectAttempt = 0
            beginBackfill(listOf("pyblock-chat", "pyblock-dms", "pyblock-reacts"))
            subscribe(ws)
            _state.update { it.copy(connected = true) }
            publishMetadata()
            flushOutbox()          // deliver anything queued while we were disconnected
        }
        override fun onMessage(ws: WebSocket, text: String) = handle(text)
        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            sockets.remove(ws)   // drop the dead socket so connect() can re-open (else sends are lost)
            _state.update { it.copy(connected = false) }
            scheduleReconnect()
        }
        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            sockets.remove(ws)
            _state.update { it.copy(connected = false) }
            scheduleReconnect()
        }
    }

    private fun handle(text: String) {
        val arr = try { JSONArray(text) } catch (e: Exception) { return }
        if (arr.optString(0) == "EOSE") { backfillEOSE(arr.optString(1)); return }
        // Relay refused one of our events → roll back the optimistic echo and
        // surface the reason, instead of lying that it was sent.
        if (arr.optString(0) == "OK" && arr.length() >= 3 && !arr.optBoolean(2, true)) {
            val evId = arr.optString(1)
            // A rejected reaction: keep the optimistic chip so the user sees their
            // own reaction, no scary banner. (Relay allows kind-7 now; belt-and-braces.)
            if (pendingReactions.remove(evId) != null) return
            val reason = arr.optString(3).ifEmpty { "rejected by relay" }
            seen.remove(evId); verifiedIds.remove(evId)
            _state.update { s ->
                // A refused room message used to take the user's typed text with it. Keep the
                // words so the composer can put them back.
                val refused = (s.messages + s.whaleMessages).firstOrNull { it.id == evId }?.content
                s.copy(
                    messages = s.messages.filter { it.id != evId },
                    whaleMessages = s.whaleMessages.filter { it.id != evId },
                    conversations = s.conversations.mapValues { (_, list) -> list.filter { it.id != evId } },
                    lastRejection = reason,
                    rejectedDraft = refused ?: s.rejectedDraft,
                )
            }
            return
        }
        if (arr.length() < 3 || arr.optString(0) != "EVENT") return
        val ev = decode(arr.optJSONObject(2) ?: return) ?: return
        if (!accept(ev)) return
        when (ev.kind) {
            0 -> {
                parseName(ev.content)?.let { n ->
                    if (n.isNotEmpty()) {
                        _state.update { it.copy(profiles = it.profiles + (ev.pubkey to n)) }
                        _state.value.peerPaynyms[ev.pubkey]?.let { PaynymBook.autoLabel(ctx, it, n, nostr = ev.pubkey) }
                    }
                }
                parseBtc(ev.content)?.let { addr ->
                    _state.update { it.copy(peerAddresses = it.peerAddresses + (ev.pubkey to addr)) }
                }
                parseTier(ev.content)?.let { t ->
                    _state.update { it.copy(peerTiers = it.peerTiers + (ev.pubkey to t)) }
                }
                jsonField(ev.content, "forge")?.let { f -> _state.update { it.copy(peerForges = it.peerForges + (ev.pubkey to f)) } }
                jsonField(ev.content, "sigil")?.let { g -> _state.update { it.copy(peerSigils = it.peerSigils + (ev.pubkey to g)) } }
                parseColor(ev.content)?.let { c ->
                    _state.update { it.copy(peerColors = it.peerColors + (ev.pubkey to c)) }
                }
                parsePaynym(ev.content)?.let { pc ->
                    val isNew = _state.value.peerPaynyms[ev.pubkey] != pc
                    rememberPeerCode(ev.pubkey, pc)
                    // The code may arrive after a receipt already did — reconcile now.
                    // Off the WS reader thread + under the claim lock: claims are
                    // check-then-act over shared state and must never interleave.
                    if (isNew) viewModelScope.launch {
                        PaynymClaims.mutex.withLock { claimIncomingPaynym(ev.pubkey) }
                    }
                }
            }
            42 -> if (seen.add(ev.id)) {
                if (backfilling) pendingMessages.add(ev)
                else { insertMessage(ev); requestProfile(ev.pubkey); refreshMarks(listOf(ev.pubkey)) }
            }
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
                // A peer handing us their payment code: remember it, look for anything they may
                // already have paid us, and never show it.
                if (com.astrolexis.pyblock.data.util.PaymentUri.isHello(textDec)) {
                    if (ev.pubkey != myPubkey) com.astrolexis.pyblock.data.util.PaymentUri.helloCode(textDec)?.let { code ->
                        rememberPeerCode(peer, code)
                        interestPeers.add(peer)
                        viewModelScope.launch { PaynymClaims.mutex.withLock { claimIncomingPaynym(peer) } }
                    }
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
                    if (!mine && textDec.startsWith("pyblock:paid?")) {
                        // The receipt may carry the payer's own code (`from=`), which makes it
                        // self-sufficient: the receiver can derive the address even when the payer
                        // never opted in to advertising their code in their profile.
                        com.astrolexis.pyblock.data.util.PaymentUri.receiptSender(textDec)?.let { code -> rememberPeerCode(peer, code) }
                        if (_state.value.peerPaynyms[peer] != null)
                            viewModelScope.launch { PaynymClaims.mutex.withLock { claimIncomingPaynym(peer) } }
                    }
                    requestProfile(peer)
                }
            }
        }
    }

    /**
     * Oldest first, id breaking ties. created_at is a whole second and a busy room puts several
     * messages inside one, so sorting on it alone lets equal rows swap places between renders.
     */
    private val EVENT_ORDER = compareBy<NostrEvent>({ it.created_at }, { it.id })

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
                conversations = s.conversations - pubkey,   // and off the disk on the next write
            )
        }
        scheduleArchive()
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
        if (backfilling) { pendingDMs.add(m); return }
        _state.update { s ->
            val existing = s.conversations[m.peer] ?: emptyList()
            if (existing.any { it.id == m.id }) return@update s
            val all = (existing + m).sortedBy { it.createdAt }
            val list = if (all.size > DMArchive.PER_PEER_LIMIT) all.drop(all.size - DMArchive.PER_PEER_LIMIT) else all
            s.copy(conversations = s.conversations + (m.peer to list))
        }
        scheduleArchive()
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

    private fun jsonField(content: String, key: String): String? =
        try { org.json.JSONObject(content).optString(key, "").takeIf { it.isNotBlank() } } catch (e: Exception) { null }

    private fun parseTier(content: String): String? = try {
        JSONObject(content).optString("tier").takeIf { it.isNotEmpty() }
    } catch (e: Exception) { null }

    private fun parseColor(content: String): String? = try {
        JSONObject(content).optString("color").takeIf { it.isNotEmpty() }
    } catch (e: Exception) { null }

    private fun now(): Long = System.currentTimeMillis() / 1000
}
