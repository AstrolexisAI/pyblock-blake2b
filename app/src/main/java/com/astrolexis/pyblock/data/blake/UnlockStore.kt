package com.astrolexis.pyblock.data.blake

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Coins the user has EXPLICITLY unlocked for spending despite replay exposure. The BLAKE2b fork
 * has no replay protection, so a pre-fork (shared) or received coin can be replayed on the
 * Bitcoin (SHA-256) chain — moving it here can affect/lose the balance there. By default such
 * coins are LOCKED (see [BlakeFork]); this store records the ones the user chose to unlock after
 * the warning. Keyed by "txid:vout". Never touches immature coinbase (that's a consensus lock, not
 * a policy one — it can't be unlocked).
 */
object UnlockStore {
    private var prefs: android.content.SharedPreferences? = null
    private const val KEY = "unlocked.v1"

    private val _ids = MutableStateFlow<Set<String>>(emptySet())
    val ids: StateFlow<Set<String>> = _ids.asStateFlow()

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("pyblockb.unlock", Context.MODE_PRIVATE)
        _ids.value = prefs?.getStringSet(KEY, emptySet())?.toSet() ?: emptySet()
    }

    fun isUnlocked(id: String): Boolean = id in _ids.value
    fun unlock(id: String) { _ids.value = _ids.value + id; persist() }
    fun relock(id: String) { _ids.value = _ids.value - id; persist() }
    private fun persist() { prefs?.edit()?.putStringSet(KEY, _ids.value)?.apply() }
}
