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

    /** At this height CAROUSEL becomes the flagship pool mode (the "new lotto"). Before it LOTTO is
     *  primary; at/after it CAROUSEL is. Drives the flagship colour so the UI swaps automatically as
     *  the timechain crosses the boundary — no rebuild. Mirrors iOS. */
    const val CAROUSEL_SWITCH_HEIGHT = 970_000
    fun primaryStratum(tip: Int): String = if (tip >= CAROUSEL_SWITCH_HEIGHT) "carousel" else "lotto"

    fun confirmations(u: BlakeApi.Utxo, tip: Int): Int =
        if (tip > 0) maxOf(0, tip - u.height + 1) else 0

    /** Mature post-fork coinbase → safe to spend (non-replayable). */
    fun isSpendable(u: BlakeApi.Utxo, tip: Int): Boolean =
        u.coinbase && u.height >= FORK_HEIGHT && confirmations(u, tip) >= COINBASE_MATURITY

    /** Human reason a coin is still locked (null if spendable). */
    fun lockReason(u: BlakeApi.Utxo, tip: Int): String? {
        if (isSpendable(u, tip)) return null
        if (u.height < FORK_HEIGHT) return "pre-fork · replay-exposed"
        // Post-fork but NOT our own mined coinbase (a received/incoming coin) → replay-exposed
        // (the fork has no replay protection), so locked. NOT a pre-fork coin.
        if (!u.coinbase) return "received · replay-exposed"
        val need = COINBASE_MATURITY - confirmations(u, tip)
        return "immature · ${maxOf(0, need)} blocks to mature"
    }

    /** Locked for REPLAY reasons (pre-fork or received), so the user MAY unlock it (accepting the
     *  replay risk). Immature coinbase is a consensus lock — NOT unlockable, must wait to mature. */
    fun isReplayLocked(u: BlakeApi.Utxo, tip: Int): Boolean {
        if (isSpendable(u, tip)) return false
        return u.height < FORK_HEIGHT || !u.coinbase
    }

    /** Effectively spendable = safe mature coinbase, OR the user unlocked a replay-locked coin. */
    fun isEffectivelySpendable(u: BlakeApi.Utxo, tip: Int): Boolean =
        isSpendable(u, tip) || (isReplayLocked(u, tip) && UnlockStore.isUnlocked(u.id))
}
