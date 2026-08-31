package com.astrolexis.pyblock.data.nostr

import android.content.Context
import com.astrolexis.pyblock.data.store.PayJoinReservations
import com.astrolexis.pyblock.data.wallet.BdkNode
import com.astrolexis.pyblock.data.wallet.PayJoinTx
import com.astrolexis.pyblock.data.wallet.UtxoInfo
import com.astrolexis.pyblock.data.wallet.UtxoMetaStore
import com.astrolexis.pyblock.data.wallet.VanityWallet
import com.astrolexis.pyblock.data.wallet.WalletStore
import com.astrolexis.pyblock.data.wallet.WalletSyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * PayJoin ("Collaborative Send") orchestration. Android port of iOS
 * `PayJoinCoordinator.swift`. Drives the BIP-78 wire protocol
 * ([PayJoinMessage]/[PayJoinSession]) over Nostr DMs, holds live sessions,
 * reserves coins for the round-trip, and calls the money core [PayJoinTx] via the
 * wallet's [BdkNode] bridge (which serializes on the wallet lock + broadcasts
 * through the running CBF client).
 *
 * Transport is injected by [NostrClient] via [attach]: outbound is `sendDM`,
 * inbound `pyblock:pj-*` DMs are routed to [handleIncoming] from NostrClient's
 * kind-4 handler and filtered out of the visible chat.
 *
 * Gated by [com.astrolexis.pyblock.data.store.PayJoinFeature] — no UI entry point
 * until the on-device 2-phone gate is cleared.
 */
object PayJoinCoordinator {

    /** Outbound transport + name lookup, supplied by NostrClient (avoids holding the VM). */
    interface Transport {
        fun sendDM(peer: String, content: String)
        fun nameFor(pubkey: String): String
    }

    /** Per-session live state (protocol session + role-specific working data). */
    data class Live(
        val session: PayJoinSession,
        // sender
        val senderWalletId: String? = null,
        val selected: List<UtxoInfo>? = null,
        val original: PayJoinTx.Original? = null,
        // receiver
        val receiverWalletId: String? = null,
        val receiverCoin: PayJoinTx.ReceiverCoin? = null,
        val receiveAddress: String? = null,
        val deadlineMs: Long,
    )

    private val _sessions = MutableStateFlow<Map<String, Live>>(emptyMap())
    val sessions: StateFlow<Map<String, Live>> = _sessions.asStateFlow()
    /** Session ids of inbound requests awaiting the user's consent (receiver UI). */
    private val _pendingConsent = MutableStateFlow<List<String>>(emptyList())
    val pendingConsent: StateFlow<List<String>> = _pendingConsent.asStateFlow()
    /** Short human status per session for the UI. */
    private val _status = MutableStateFlow<Map<String, String>>(emptyMap())
    val status: StateFlow<Map<String, String>> = _status.asStateFlow()

    private const val TIMEOUT_MS = 120_000L
    /** Anti-DoS: cap concurrent, non-terminal inbound requests from ONE peer. A flood of
     *  `pj-req` would otherwise pile up pending-consent entries + arm timeouts (and, once
     *  coins are contributed, pressure the spendable pool). Excess is rejected with abort. */
    private const val MAX_INBOUND_PER_PEER = 3

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lock = Any()   // serializes check-then-act on the session map (WS reader + IO steps)

    @Volatile private var transport: Transport? = null
    @Volatile private var appCtx: Context? = null

    /** Wire up transport + app context. Called by NostrClient at init. */
    fun attach(ctx: Context, transport: Transport) {
        this.appCtx = ctx.applicationContext
        this.transport = transport
    }

    // MARK: - Sender entry (called by the UI / coin-control send)

    /** Begin a Collaborative Send: reserve the coins, send `pj-req`, and wait for the
     *  recipient's address before building the original. Returns the session id, or null
     *  if the selection is invalid / conflicts with another in-flight session. */
    fun startSend(
        peer: String, amountSats: ULong, feeRateSatVb: ULong,
        selected: List<UtxoInfo>, senderWalletId: String,
    ): String? = synchronized(lock) {
        if (selected.isEmpty() || selected.map { it.walletId }.toSet().size != 1) return@synchronized null
        val id = PayJoin.newSessionId()
        val keys = selected.map { it.key }
        if (PayJoinReservations.conflicts(keys, id)) return@synchronized null
        PayJoinReservations.reserve(keys, id)

        var s = PayJoinSession(role = PayJoinRole.SENDER, peerPubkey = peer, id = id,
            amountSats = amountSats, feeRateSatVb = feeRateSatVb)
        s = s.advance(PayJoinState.Requested) ?: s
        setLive(id, Live(session = s, senderWalletId = senderWalletId, selected = selected,
            deadlineMs = now() + TIMEOUT_MS))
        setStatus(id, "Waiting for recipient…")
        // Warm the sender's node so it can build + broadcast when the round-trip completes.
        focusNode(senderWalletId)
        send(peer, PayJoinMessage.Req(id, amountSats, feeRateSatVb))
        armTimeout(id)
        id
    }

    // MARK: - Receiver consent (called by the UI after pj-req)

    /** Accept an inbound request: reserve the contributed coin, send our receive address,
     *  and wait for the original. `receiveAddress` + `coin` come from the UI. */
    fun acceptInbound(sessionId: String, receiverWalletId: String,
                      coin: PayJoinTx.ReceiverCoin, receiveAddress: String) = synchronized(lock) {
        val live = liveOf(sessionId) ?: return@synchronized
        if (live.session.role != PayJoinRole.RECEIVER) return@synchronized
        val ckey = coin.key
        val ctx = appCtx
        val frozen = ctx?.let { UtxoMetaStore.isFrozen(it, ckey) } ?: false
        if (PayJoinReservations.conflicts(listOf(ckey), sessionId) || frozen) {
            abort(sessionId, "coin_unavailable"); return@synchronized
        }
        PayJoinReservations.reserve(listOf(ckey), sessionId)

        val next = live.session.advance(PayJoinState.Addressed) ?: live.session
        setLive(sessionId, live.copy(
            receiverWalletId = receiverWalletId, receiverCoin = coin,
            receiveAddress = receiveAddress, session = next))
        removePending(sessionId)
        setStatus(sessionId, "Sharing a receive address…")
        // Warm the receiver's node so buildProposal has an open wallet.
        focusNode(receiverWalletId)
        send(next.peerPubkey, PayJoinMessage.Addr(sessionId, receiveAddress, ok = true))
    }

    /** Decline an inbound request. */
    fun declineInbound(sessionId: String) = synchronized(lock) {
        val live = liveOf(sessionId) ?: return@synchronized
        send(live.session.peerPubkey, PayJoinMessage.Addr(sessionId, "", ok = false))
        finish(sessionId, "Declined.")
    }

    // MARK: - Inbound routing (called by NostrClient's kind-4 DM handler)

    /** Route a decrypted `pyblock:pj-*` DM. `mine` = we authored it (ignored — our own
     *  state already advanced locally when we sent it). Safe to call from any thread. */
    fun handleIncoming(peer: String, mine: Boolean, content: String) {
        if (mine) return
        val msg = PayJoinMessage.parse(content) ?: return
        synchronized(lock) {
            // First contact from a payer: open a receiver session (parked for consent).
            if (msg is PayJoinMessage.Req && liveOf(msg.id) == null) {
                // Rate-limit: refuse if this peer already has too many in-flight requests.
                val inFlight = _sessions.value.values.count {
                    it.session.peerPubkey == peer && it.session.role == PayJoinRole.RECEIVER &&
                        !it.session.state.isTerminal
                }
                if (inFlight >= MAX_INBOUND_PER_PEER) {
                    send(peer, PayJoinMessage.Abort(msg.id, "rate_limited")); return
                }
                var s = PayJoinSession(role = PayJoinRole.RECEIVER, peerPubkey = peer, id = msg.id,
                    amountSats = msg.amountSats, feeRateSatVb = msg.feeRateSatVb)
                s = s.receive(msg) ?: s
                setLive(msg.id, Live(session = s, deadlineMs = now() + TIMEOUT_MS))
                addPending(msg.id)
                setStatus(msg.id, "Wants to pay you collaboratively.")
                armTimeout(msg.id)
                return
            }

            val live = liveOf(msg.id) ?: return
            if (live.session.peerPubkey != peer) return
            val next = live.session.receive(msg) ?: return
            setLive(msg.id, live.copy(session = next))

            when (msg) {
                is PayJoinMessage.Abort -> finish(msg.id, "Cancelled (${msg.reason}).")
                is PayJoinMessage.Addr ->
                    if (msg.ok) onAddress(msg.id) else finish(msg.id, "Recipient declined.")
                is PayJoinMessage.Orig -> onOriginal(msg.id, msg.psbtB64)
                is PayJoinMessage.Prop -> onProposal(msg.id, msg.psbtB64)
                is PayJoinMessage.Done -> {
                    PayJoinReservations.release(msg.id)
                    setStatus(msg.id, "Received. (${msg.txid.take(12)}…)")
                }
                is PayJoinMessage.Req -> Unit   // handled above
            }
        }
    }

    // MARK: - Step drivers

    /** Sender got the recipient's address → build + send the original. */
    private fun onAddress(id: String) {
        val live = liveOf(id) ?: return
        if (live.session.role != PayJoinRole.SENDER) return
        val walletId = live.senderWalletId ?: return
        val selected = live.selected ?: return
        val address = live.session.address ?: return
        setStatus(id, "Building the payment…")
        scope.launch {
            try {
                val node = focusNode(walletId) ?: throw IllegalStateException("wallet unavailable")
                val ctx = appCtx ?: throw IllegalStateException("no context")
                val frozen = UtxoMetaStore.frozenKeys(ctx)
                val original = node.pjBuildOriginal(
                    sessionId = id, selected = selected, toAddress = address,
                    amountSats = live.session.amountSats.toLong(),
                    feeRateSatVb = live.session.feeRateSatVb.toLong(), frozenKeys = frozen)
                synchronized(lock) {
                    val l = liveOf(id) ?: return@synchronized
                    val adv = l.session.advance(PayJoinState.Original) ?: l.session
                    setLive(id, l.copy(original = original, session = adv))
                    setStatus(id, "Sent — waiting for the recipient to co-sign…")
                    send(l.session.peerPubkey, PayJoinMessage.Orig(id, original.psbtB64, original.ctx.maxFeeSats.toULong()))
                }
            } catch (e: Exception) {
                synchronized(lock) { abort(id, "build_failed") }
            }
        }
    }

    /** Receiver got the original → build + send the payjoin proposal. */
    private fun onOriginal(id: String, origPsbtB64: String) {
        val live = liveOf(id) ?: return
        if (live.session.role != PayJoinRole.RECEIVER) return
        val walletId = live.receiverWalletId ?: return
        val coin = live.receiverCoin ?: return
        val addr = live.receiveAddress ?: return
        setStatus(id, "Co-signing…")
        scope.launch {
            try {
                val node = focusNode(walletId) ?: throw IllegalStateException("wallet unavailable")
                val propB64 = node.pjBuildProposal(
                    originalPsbtB64 = origPsbtB64, coin = coin, receiveAddress = addr,
                    amountSats = live.session.amountSats.toLong(),
                    feeRateSatVb = live.session.feeRateSatVb.toLong())
                synchronized(lock) {
                    val l = liveOf(id) ?: return@synchronized
                    val adv = l.session.advance(PayJoinState.Proposal) ?: l.session
                    setLive(id, l.copy(session = adv))
                    setStatus(id, "Co-signed — waiting for it to be broadcast…")
                    send(l.session.peerPubkey, PayJoinMessage.Prop(id, propB64))
                }
            } catch (e: Exception) {
                synchronized(lock) { abort(id, "cosign_failed") }
            }
        }
    }

    /** Sender got the receiver's proposal → vet, finalize, broadcast. */
    private fun onProposal(id: String, propPsbtB64: String) {
        val live = liveOf(id) ?: return
        if (live.session.role != PayJoinRole.SENDER) return
        val walletId = live.senderWalletId ?: return
        val original = live.original ?: return
        setStatus(id, "Verifying & broadcasting…")
        scope.launch {
            try {
                val node = focusNode(walletId) ?: throw IllegalStateException("wallet unavailable")
                node.awaitBroadcastReady()
                val txid = node.pjFinalizeAndBroadcast(propPsbtB64, original)
                synchronized(lock) {
                    val l = liveOf(id) ?: return@synchronized
                    val adv = l.session.advance(PayJoinState.Broadcast(txid)) ?: l.session
                    setLive(id, l.copy(session = adv))
                    PayJoinReservations.release(id)
                    setStatus(id, "Sent! (${txid.take(12)}…)")
                    send(l.session.peerPubkey, PayJoinMessage.Done(id, txid))
                }
            } catch (e: PayJoinTx.PayJoinException) {
                if (e.err == PayJoinTx.Err.BROADCAST_UNCERTAIN) {
                    // The tx may already be on the wire — never auto-fall-back. Tell the
                    // receiver so it releases its contributed coin NOW instead of waiting
                    // out the 120 s timeout (anti-griefing). The tx, if broadcast, stands.
                    synchronized(lock) {
                        send(peerFor(id), PayJoinMessage.Abort(id, "broadcast_uncertain"))
                        PayJoinReservations.release(id)
                        setStatus(id, "May have been sent — check your activity before retrying.")
                        setAborted(id, "uncertain")
                    }
                } else guardReject(id)
            } catch (e: Exception) {
                guardReject(id)
            }
        }
    }

    /** A guard rejected the proposal (possibly a malicious counterparty). Do NOT
     *  auto-broadcast the fallback; surface it so the user can send normally or cancel. */
    private fun guardReject(id: String) = synchronized(lock) {
        send(peerFor(id), PayJoinMessage.Abort(id, "guard"))
        setStatus(id, "Couldn't complete safely. You can send it normally instead.")
        setAborted(id, "guard")
    }

    /** Sender-side fallback (S6): broadcast the plain original after a failed/timed-out
     *  PayJoin. Explicit — the UI offers it; never automatic. PayJoinTx refuses if a
     *  PayJoin broadcast was already attempted for this session (Defect C). */
    fun broadcastFallback(sessionId: String) {
        val live = liveOf(sessionId) ?: return
        if (live.session.role != PayJoinRole.SENDER) return
        val walletId = live.senderWalletId ?: return
        val original = live.original ?: return
        setStatus(sessionId, "Sending normally…")
        scope.launch {
            try {
                val node = focusNode(walletId) ?: throw IllegalStateException("wallet unavailable")
                node.awaitBroadcastReady()
                val txid = node.pjBroadcastFallback(original)
                PayJoinReservations.release(sessionId)
                setStatus(sessionId, "Sent normally. (${txid.take(12)}…)")
            } catch (e: Exception) {
                setStatus(sessionId, "Couldn't send.")
            }
        }
    }

    // MARK: - Lifecycle

    private fun abort(id: String, reason: String) {
        peerForOrNull(id)?.let { send(it, PayJoinMessage.Abort(id, reason)) }
        finish(id, "Cancelled ($reason).")
    }

    private fun finish(id: String, msg: String) {
        PayJoinReservations.release(id)
        removePending(id)
        setStatus(id, msg)
        setAborted(id, msg)
    }

    /** Move a session to a terminal aborted state (monotonic; ignores if already terminal). */
    private fun setAborted(id: String, reason: String) {
        val l = liveOf(id) ?: return
        val adv = l.session.advance(PayJoinState.Aborted(reason)) ?: l.session
        setLive(id, l.copy(session = adv))
    }

    /** Drop a finished/cancelled session's UI state (called by the UI on dismiss). */
    fun clear(sessionId: String) = synchronized(lock) {
        PayJoinReservations.release(sessionId)
        dropLive(sessionId)
        _status.value = _status.value - sessionId
        removePending(sessionId)
    }

    private fun armTimeout(id: String) {
        scope.launch {
            delay(TIMEOUT_MS)
            synchronized(lock) {
                val l = liveOf(id) ?: return@synchronized
                if (!l.session.state.isTerminal) abort(id, "timeout")
            }
        }
    }

    // MARK: - Helpers

    private fun now() = System.currentTimeMillis()

    private fun send(peer: String, msg: PayJoinMessage) {
        transport?.sendDM(peer, msg.encode())
    }

    private fun peerFor(id: String): String = liveOf(id)?.session?.peerPubkey ?: ""
    private fun peerForOrNull(id: String): String? = liveOf(id)?.session?.peerPubkey

    /** Human name for a peer (for the UI). */
    fun nameFor(pubkey: String): String = transport?.nameFor(pubkey) ?: "@${pubkey.take(8)}"

    private fun metaFor(walletId: String): VanityWallet? =
        WalletStore.wallets.value.firstOrNull { it.id == walletId }

    /** Ensure the wallet's node is running (opens the wallet synchronously; the CBF client
     *  comes up shortly after) and return it. */
    private fun focusNode(walletId: String): BdkNode? {
        val ctx = appCtx ?: return null
        val meta = metaFor(walletId) ?: return null
        WalletSyncManager.focus(ctx, meta)
        return WalletSyncManager.getNode(ctx, meta)
    }

    // ---- session map mutation (always under `lock`) ----

    private fun liveOf(id: String): Live? = _sessions.value[id]
    private fun setLive(id: String, l: Live) { _sessions.value = _sessions.value + (id to l) }
    private fun dropLive(id: String) { _sessions.value = _sessions.value - id }
    private fun setStatus(id: String, s: String) { _status.value = _status.value + (id to s) }
    private fun addPending(id: String) {
        if (id !in _pendingConsent.value) _pendingConsent.value = _pendingConsent.value + id
    }
    private fun removePending(id: String) { _pendingConsent.value = _pendingConsent.value - id }
}
