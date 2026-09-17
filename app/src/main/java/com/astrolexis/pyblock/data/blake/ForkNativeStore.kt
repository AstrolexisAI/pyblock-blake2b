package com.astrolexis.pyblock.data.blake

import android.content.Context

/**
 * Coins we PROVED are fork-native without being coinbase: outputs paying our own addresses from a
 * transaction whose every input was fork-native (a mature post-fork coinbase, or a coin already in
 * this set). Such a transaction cannot exist on the SHA-256 chain — its inputs don't exist there —
 * so its change is as non-replayable as the coinbase it came from.
 *
 * Without this, the change from a send came back as a "received" coin and was replay-locked: the
 * user saw their unlocked coin "revert to locked" after every payment. Keyed by "txid:vout". A coin
 * the USER unlocked ([UnlockStore]) never qualifies: that one really is replay-exposed, and so is
 * anything spent from it.
 */
object ForkNativeStore {
    private var prefs: android.content.SharedPreferences? = null
    private const val KEY = "forknative.v1"
    @Volatile private var ids: Set<String> = emptySet()

    fun init(ctx: Context) {
        if (prefs != null) return
        prefs = ctx.applicationContext.getSharedPreferences("pyblockb.forknative", Context.MODE_PRIVATE)
        ids = prefs?.getStringSet(KEY, emptySet())?.toSet() ?: emptySet()
    }
    fun contains(id: String): Boolean = id in ids
    @Synchronized fun add(new: Set<String>) {
        if (new.isEmpty()) return
        ids = ids + new
        prefs?.edit()?.putStringSet(KEY, ids)?.apply()
    }
}
