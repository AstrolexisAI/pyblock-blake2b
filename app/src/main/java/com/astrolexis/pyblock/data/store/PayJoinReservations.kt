package com.astrolexis.pyblock.data.store

/**
 * Session-scoped coin reservation. While a Collaborative Send (PayJoin) is
 * negotiating, its selected sender coins (and the receiver's contributed coin)
 * are held here so a concurrent normal send or a second PayJoin can't
 * double-select the same UTXOs during the Nostr round-trip. Keys are
 * `UtxoMetaStore.key(txid, vout)` outpoint ids. Port of iOS `PayJoinReservations`.
 *
 * A liveness/UX guard (a double-spend just fails at the node), not a fund-safety
 * guard. Accessed from the main-thread coordinator; kept simple.
 */
object PayJoinReservations {
    /** outpoint key → owning session id. */
    private val reserved = HashMap<String, String>()

    fun reserve(keys: List<String>, sessionId: String) {
        keys.forEach { reserved[it] = sessionId }
    }

    /** Release every coin held by a session (on completion or abort). */
    fun release(sessionId: String) {
        reserved.entries.removeAll { it.value == sessionId }
    }

    /** Coins reserved by SOME OTHER session (the set a new selection must avoid). */
    fun heldByOthers(sessionId: String): Set<String> =
        reserved.filterValues { it != sessionId }.keys

    /** True if any of `keys` is held by a different session. */
    fun conflicts(keys: List<String>, sessionId: String): Boolean {
        val others = heldByOthers(sessionId)
        return keys.any { it in others }
    }

    /** Convenience for callers that don't own a session (e.g. a normal send). */
    val allReserved: Set<String> get() = reserved.keys.toSet()
}
