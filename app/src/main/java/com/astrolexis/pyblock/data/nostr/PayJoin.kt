package com.astrolexis.pyblock.data.nostr

import java.util.UUID

/**
 * PayJoin (BIP-78) "Collaborative Send" wire protocol, carried over NIP-44
 * encrypted Nostr DMs as `pyblock:pj-*` payloads. This file is the PURE layer:
 * message encode/parse + an ordering-enforcing session model. NO coupling to
 * bdk, Nostr, or any signing key — the money core lives in `PayJoinTx` and the
 * transport in `PayJoinCoordinator`. Port of iOS `PayJoinProtocol.swift`.
 *
 * Apple wording is moot on Android (no App Store), but the user-facing name stays
 * **Collaborative Send** for parity — never "mixer"/"CoinJoin".
 */
object PayJoin {
    /** Protocol version. Bumped only on a breaking wire change. */
    const val VERSION = 1

    /** Floor on the extra fee the sender absorbs for the receiver's added input
     *  (a P2PKH input is ≈148 vB). The sender guard enforces the effective cap. */
    const val MAX_ADDITIONAL_FEE_SATS: ULong = 1_000u

    /** Fresh session id — ties every `pj-*` message of one negotiation together. */
    fun newSessionId(): String = UUID.randomUUID().toString().replace("-", "").lowercase()

    /** Cheap prefilter so the transport can hide these from the visible chat. */
    fun looksLikePayJoin(content: String): Boolean = content.startsWith("pyblock:pj-")

    /** Keep an abort `reason` to a wire-safe slug (no `&`, `=`, or spaces). */
    fun slug(s: String): String {
        val allowed = "abcdefghijklmnopqrstuvwxyz0123456789_-".toSet()
        val filtered = s.lowercase().replace(' ', '_').filter { it in allowed }
        return filtered.ifEmpty { "unknown" }.take(40)
    }
}

/** One protocol message, carried as an encrypted DM `pyblock:pj-*` payload. */
sealed class PayJoinMessage {
    abstract val id: String

    /** sender→receiver: "I'd like to pay you `amountSats` collaboratively." */
    data class Req(override val id: String, val amountSats: ULong, val feeRateSatVb: ULong) : PayJoinMessage()
    /** receiver→sender: a fresh receive address + consent (`ok`=false declines). */
    data class Addr(override val id: String, val address: String, val ok: Boolean) : PayJoinMessage()
    /** sender→receiver: the fully-signed ORIGINAL psbt (valid fallback tx). */
    data class Orig(override val id: String, val psbtB64: String, val maxFeeSats: ULong) : PayJoinMessage()
    /** receiver→sender: the rebuilt PAYJOIN psbt (receiver input added + signed). */
    data class Prop(override val id: String, val psbtB64: String) : PayJoinMessage()
    /** sender→receiver: courtesy notice that the payjoin tx was broadcast. */
    data class Done(override val id: String, val txid: String) : PayJoinMessage()
    /** either side: negotiation dropped. `reason` is a short slug (no spaces). */
    data class Abort(override val id: String, val reason: String) : PayJoinMessage()

    /** Encode to the `pyblock:pj-<verb>?...` DM payload. Values are never URL-
     *  encoded — base64 PSBTs contain only `A–Z a–z 0–9 + / =` (no `&`), and the
     *  parser splits on `&` then the first `=`, so padding survives intact. */
    fun encode(): String = when (this) {
        is Req -> "pyblock:pj-req?id=$id&amt=$amountSats&fee=$feeRateSatVb&v=${PayJoin.VERSION}"
        is Addr -> "pyblock:pj-addr?id=$id&addr=$address&ok=${if (ok) 1 else 0}"
        is Orig -> "pyblock:pj-orig?id=$id&psbt=$psbtB64&maxfee=$maxFeeSats"
        is Prop -> "pyblock:pj-prop?id=$id&psbt=$psbtB64"
        is Done -> "pyblock:pj-done?id=$id&txid=$txid"
        is Abort -> "pyblock:pj-abort?id=$id&reason=${PayJoin.slug(reason)}"
    }

    companion object {
        /** Parse a `pyblock:pj-*` payload, or null if it isn't a valid one. */
        fun parse(content: String): PayJoinMessage? {
            if (!content.startsWith("pyblock:pj-")) return null
            val q = content.indexOf('?')
            if (q < 0) return null
            val verb = content.substring("pyblock:pj-".length, q)
            val m = query(content)
            val id = m["id"]?.takeIf { it.isNotEmpty() } ?: return null
            return when (verb) {
                "req" -> {
                    val amt = m["amt"]?.toULongOrNull() ?: return null
                    val fee = m["fee"]?.toULongOrNull() ?: return null
                    Req(id, amt, fee)
                }
                "addr" -> {
                    val addr = m["addr"]?.takeIf { it.isNotEmpty() } ?: return null
                    Addr(id, addr, (m["ok"] ?: "1") != "0")
                }
                "orig" -> {
                    val psbt = m["psbt"]?.takeIf { it.isNotEmpty() } ?: return null
                    Orig(id, psbt, m["maxfee"]?.toULongOrNull() ?: PayJoin.MAX_ADDITIONAL_FEE_SATS)
                }
                "prop" -> {
                    val psbt = m["psbt"]?.takeIf { it.isNotEmpty() } ?: return null
                    Prop(id, psbt)
                }
                "done" -> {
                    val txid = m["txid"]?.takeIf { it.isNotEmpty() } ?: return null
                    Done(id, txid)
                }
                "abort" -> Abort(id, m["reason"] ?: "unknown")
                else -> null
            }
        }

        /** Split a `key=value&key=value` query on `&` then the FIRST `=`
         *  (base64 `=` padding stays in the value). */
        private fun query(s: String): Map<String, String> {
            val qs = s.substringAfter('?', "")
            val out = HashMap<String, String>()
            for (pair in qs.split('&')) {
                val i = pair.indexOf('=')
                if (i > 0) out[pair.substring(0, i)] = pair.substring(i + 1)
            }
            return out
        }
    }
}

// MARK: - Session state machine

enum class PayJoinRole { SENDER, RECEIVER }

/** Lifecycle of one negotiation. Progress is monotonic; an out-of-order or
 *  replayed message is rejected (never rewinds state). */
sealed class PayJoinState {
    object Idle : PayJoinState()
    object Requested : PayJoinState()
    object Addressed : PayJoinState()
    object Original : PayJoinState()
    object Proposal : PayJoinState()
    data class Broadcast(val txid: String) : PayJoinState()
    data class Aborted(val reason: String) : PayJoinState()

    val isTerminal: Boolean get() = this is Broadcast || this is Aborted
}

/** A live negotiation. `receive` enforces ordering (the replay/reorder gate); the
 *  money steps are performed by the caller and reflected via `advance`. */
data class PayJoinSession(
    val role: PayJoinRole,
    val peerPubkey: String,
    val id: String,
    val amountSats: ULong,
    val feeRateSatVb: ULong,
    val address: String? = null,
    val maxFeeSats: ULong = PayJoin.MAX_ADDITIONAL_FEE_SATS,
    val state: PayJoinState = PayJoinState.Idle,
) {
    /** Whether an incoming `message` is expected right now for this role — the
     *  replay/reorder gate. Performs no money action. */
    fun expects(message: PayJoinMessage): Boolean {
        if (message.id != id || state.isTerminal) {
            // An abort for our session is always acceptable (lets us tear down).
            return message is PayJoinMessage.Abort && message.id == id
        }
        return when {
            role == PayJoinRole.RECEIVER && message is PayJoinMessage.Req && state is PayJoinState.Idle -> true
            role == PayJoinRole.RECEIVER && message is PayJoinMessage.Orig && state is PayJoinState.Addressed -> true
            role == PayJoinRole.SENDER && message is PayJoinMessage.Addr && state is PayJoinState.Requested -> true
            role == PayJoinRole.SENDER && message is PayJoinMessage.Prop && state is PayJoinState.Original -> true
            message is PayJoinMessage.Done -> false   // the sender is the one who sends done
            message is PayJoinMessage.Abort -> true
            else -> false
        }
    }

    /** Apply an accepted inbound `message`, or null if it wasn't expected. */
    fun receive(message: PayJoinMessage): PayJoinSession? {
        if (!expects(message)) return null
        return when (message) {
            is PayJoinMessage.Abort -> copy(state = PayJoinState.Aborted(message.reason))
            is PayJoinMessage.Req -> copy(amountSats = message.amountSats, feeRateSatVb = message.feeRateSatVb, state = PayJoinState.Requested)
            is PayJoinMessage.Addr ->
                if (message.ok) copy(address = message.address, state = PayJoinState.Addressed)
                else copy(state = PayJoinState.Aborted("declined"))
            is PayJoinMessage.Orig -> copy(state = PayJoinState.Original)
            is PayJoinMessage.Prop -> copy(state = PayJoinState.Proposal)
            is PayJoinMessage.Done -> copy(state = PayJoinState.Broadcast(message.txid))
        }
    }

    /** Reflect a locally-driven step (a message WE send, or a broadcast we did).
     *  Only advances; never rewinds. Returns null on an illegal transition. */
    fun advance(newState: PayJoinState): PayJoinSession? {
        if (state.isTerminal) return null
        return copy(state = newState)
    }
}
