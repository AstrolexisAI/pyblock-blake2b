package com.astrolexis.pyblock.data.store

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Chain selector — reduced to LEGACY (Bitcoin, SHA-256) ONLY. The BIP-110 / BLAKE2b
 *  fork support was removed; this remains as a no-op legacy selector so the pool
 *  endpoints keep their pre-fork behaviour (no `chain=` param) and the many callers
 *  still compile. */
object ChainStore {
    const val LEGACY = "legacy"

    /** Observable current chain (always legacy). */
    var chain by mutableStateOf(LEGACY)
        private set

    /** In-memory copy the interceptor reads off the network thread (always legacy). */
    @Volatile
    var wire: String = LEGACY
        private set

    val isFork: Boolean get() = false

    fun init(ctx: Context) {}       // no-op: legacy only
    fun set(value: String) {}       // no-op: legacy only
    fun toggle() {}                 // no-op: no fork to toggle to

    fun forkActive(): Boolean = false

    /** Human label for the chain the spendable wallet transacts on. */
    const val SEND_CHAIN_LABEL = "Bitcoin"
}
