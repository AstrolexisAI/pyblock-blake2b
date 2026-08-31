package com.astrolexis.pyblock.data.blake

/**
 * Fork economics shared across the wallet UI. A coin is SPENDABLE only if it's a
 * mature (100-conf) POST-FORK coinbase — those exist only on BLAKE2b (Node B) and
 * are non-replayable. Everything else (immature coinbase, or shared pre-fork coins)
 * is LOCKED: replay-exposed and not safely spendable on the fork. Mirrors iOS `BlakeFork`.
 */
object BlakeFork {
    /** BLAKE2b activated at this mainnet height. Only coinbase at/after this is fork-native. */
    const val FORK_HEIGHT = 961_640
    const val COINBASE_MATURITY = 100

    fun confirmations(u: BlakeApi.Utxo, tip: Int): Int =
        if (tip > 0) maxOf(0, tip - u.height + 1) else 0

    /** Mature post-fork coinbase → safe to spend (non-replayable). */
    fun isSpendable(u: BlakeApi.Utxo, tip: Int): Boolean =
        u.coinbase && u.height >= FORK_HEIGHT && confirmations(u, tip) >= COINBASE_MATURITY

    /** Human reason a coin is still locked (null if spendable). */
    fun lockReason(u: BlakeApi.Utxo, tip: Int): String? {
        if (isSpendable(u, tip)) return null
        if (!u.coinbase || u.height < FORK_HEIGHT) return "pre-fork · replay-exposed"
        val need = COINBASE_MATURITY - confirmations(u, tip)
        return "immature · ${maxOf(0, need)} blocks to mature"
    }
}
