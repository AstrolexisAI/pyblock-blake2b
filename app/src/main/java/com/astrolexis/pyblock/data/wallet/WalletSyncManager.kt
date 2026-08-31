package com.astrolexis.pyblock.data.wallet

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Runs ONE Kyoto CBF node at a time (serial scheduler). Concurrent nodes to the
 * same peer starve each other's sync, so we focus one continuously (detail view)
 * or rotate through all wallets to warm their cached balances. Mirrors iOS
 * `WalletSyncManager`. Node instances are cached so their live balance persists.
 */
object WalletSyncManager {
    private val nodes = HashMap<String, BdkNode>()
    private var running: BdkNode? = null
    private var focusId: String? = null
    private var rotateJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Synchronized
    fun getNode(
        ctx: Context,
        meta: VanityWallet,
        chain: String = com.astrolexis.pyblock.data.store.ChainStore.LEGACY,
    ): BdkNode =
        nodes.getOrPut("${meta.id}:$chain") { BdkNode(ctx.applicationContext, meta, chain) }

    /** Keep one wallet's node running continuously (e.g. while its detail is open). */
    @Synchronized
    fun focus(ctx: Context, meta: VanityWallet) {
        focusId = meta.id
        rotateJob?.cancel(); rotateJob = null
        switchTo(getNode(ctx, meta))
    }

    @Synchronized
    fun unfocus() { focusId = null }

    private fun switchTo(n: BdkNode) {
        if (running === n) return
        running?.stop()
        running = n
        n.start()
    }

    /** Rotate through wallets, giving each up to ~2 min to sync, refreshing caches. */
    fun scheduleAll(ctx: Context, metas: List<VanityWallet>) {
        synchronized(this) {
            if (focusId != null || rotateJob?.isActive == true || metas.isEmpty()) return
        }
        rotateJob = scope.launch {
            for (m in metas) {
                if (focusId != null) break
                val n = synchronized(this@WalletSyncManager) { getNode(ctx, m).also { switchTo(it) } }
                // First-ever sync is a CBF recovery that must download ~90k filters
                // (kyoto starts from its hardcoded checkpoint) — that's several minutes.
                // Killing it early (the old 2-min cap) meant it never completed, never
                // persisted, and re-recovered forever. Give it up to 12 min to finish;
                // once done it persists and future syncs use fast ScanType.Sync.
                // Up to 20 min: a slow single peer serving ~90k filters, with new-block
                // resets along the way, can take longer than the old 12-min cap — which
                // stopped the scan at ~98%, throwing the whole recovery away.
                var waited = 0
                while (waited < 1_200_000 && !n.synced && isActive) { delay(2_000); waited += 2_000 }
                delay(4_000) // grace to catch the balance after filters complete
            }
            // Stop the node to free the single peer — BUT never stop one that's still
            // recovering: killing a near-complete first scan loses all its progress.
            // Leave it running to finish; only synced/idle nodes are stopped here.
            synchronized(this@WalletSyncManager) {
                val r = running
                if (r == null || r.synced) { r?.stop(); running = null }
            }
        }
    }

    @Synchronized
    fun stopAll() {
        rotateJob?.cancel(); rotateJob = null
        running?.stop(); running = null
    }

    /** Sweep hook: hand each wallet its confirmed UTXOs (from the batched
     *  wallet_utxos lookup) so balances appear without waiting for a CBF scan.
     *  Re-reads the wallet list so just-claimed PayNym wallets are included. */
    suspend fun seedConfirmed(ctx: Context, byAddress: Map<String, List<com.astrolexis.pyblock.data.model.Utxo>>) {
        if (byAddress.isEmpty()) return
        for (meta in WalletStore.wallets.value) {
            val utxos = byAddress[meta.address].orEmpty()
            if (utxos.isNotEmpty()) getNode(ctx, meta).seedConfirmed(utxos)
        }
    }

    /** Every wallet paired with its (cached) node — for the node dashboard. */
    @Synchronized
    fun nodesForAllWallets(ctx: Context): List<Pair<VanityWallet, BdkNode>> =
        WalletStore.wallets.value.map { it to getNode(ctx, it) }

    /** Ensure syncing is running (the node dashboard observes without churning). */
    fun resumeAll(ctx: Context) = scheduleAll(ctx, WalletStore.wallets.value)
}
