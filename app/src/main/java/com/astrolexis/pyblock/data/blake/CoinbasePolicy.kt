package com.astrolexis.pyblock.data.blake

/**
 * What the node's client says about coinbase maturity right now, where the wallet's coin rules can
 * read it. Set from `chain_status.php` on every status refresh; empty until the server says
 * otherwise, which is the safe state — an absent or failed answer must change nothing on screen.
 */
object CoinbasePolicy {
    @Volatile private var state: BlakeApi.CoinbaseMaturity? = null

    fun update(s: BlakeApi.CoinbaseMaturity?) { state = s }
    val current: BlakeApi.CoinbaseMaturity? get() = state

    /** True while the longer maturity is actually enforced by the chain. */
    val inForce: Boolean get() = state?.inForce == true

    /** The block that frees this coin, or null when the rule does not hold it. Only a coinbase mined
     *  at or after `start_height` is covered, and only while it is younger than the rule's window.
     *  Every number comes from the server; nothing here is hardcoded. */
    fun heldUntil(u: BlakeApi.Utxo, tip: Int): Int? {
        val s = state ?: return null
        if (!s.inForce || !u.coinbase) return null
        val start = s.startHeight ?: return null
        val blocks = s.maturityBlocks ?: return null
        val release = s.releaseHeight ?: return null
        if (u.height < start || tip <= 0 || tip - u.height >= blocks) return null
        return release
    }
}
