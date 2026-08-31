package com.astrolexis.pyblock.data.wallet

import android.content.Context
import org.bitcoindevkit.Persister
import org.bitcoindevkit.Wallet
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists each wallet's activity (tx history) so the ACTIVITY view shows it
 * without keeping every node running. Also reads it straight from the wallet's
 * SQLite when the cache is empty (same store the balance comes from). Mirrors
 * iOS WalletNode.cacheTxs / persistedTxs.
 */
object WalletActivityCache {
    private const val PREFS = "pyblock_activity"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun write(ctx: Context, walletId: String, txs: List<BdkNode.ActivityTx>) {
        val arr = JSONArray()
        txs.forEach {
            arr.put(JSONObject().put("t", it.txid).put("n", it.net).put("c", it.confirmed)
                .put("h", it.height ?: -1).put("s", it.timestamp ?: -1L))
        }
        prefs(ctx).edit().putString(walletId, arr.toString()).apply()
    }

    fun cached(ctx: Context, walletId: String): List<BdkNode.ActivityTx> {
        val s = prefs(ctx).getString(walletId, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(s)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                BdkNode.ActivityTx(o.getString("t"), o.getLong("n"), o.getBoolean("c"),
                    o.getInt("h").takeIf { it >= 0 }, o.getLong("s").takeIf { it >= 0 })
            }
        }.getOrDefault(emptyList())
    }

    /** Read history straight from the persisted SQLite; caches it. Falls back to
     *  the cache on any error (e.g. a running node holds the DB). */
    fun persisted(ctx: Context, meta: VanityWallet): List<BdkNode.ActivityTx> {
        val dbPath = File(File(ctx.filesDir, "wallets"), "${meta.id}.sqlite").absolutePath
        if (!File(dbPath).exists()) return cached(ctx, meta.id)
        return runCatching {
            // WATCH-ONLY descriptors — same source of truth as the node, so this
            // loads the persisted DB whether or not the vault is unlocked.
            val (ext, chg) = WalletDescriptors.watch(ctx, meta) ?: return cached(ctx, meta.id)
            val w = Wallet.load(ext, chg, Persister.newSqlite(dbPath))
            val txs = w.transactions().map { ctx2 ->
                val sr = w.sentAndReceived(ctx2.transaction)
                val net = sr.received.toSat().toLong() - sr.sent.toSat().toLong()
                val pos = ctx2.chainPosition
                if (pos is org.bitcoindevkit.ChainPosition.Confirmed)
                    BdkNode.ActivityTx(ctx2.transaction.computeTxid().toString(), net, true,
                        pos.confirmationBlockTime.blockId.height.toInt(), pos.confirmationBlockTime.confirmationTime.toLong())
                else BdkNode.ActivityTx(ctx2.transaction.computeTxid().toString(), net, false, null, null)
            }
            write(ctx, meta.id, txs)
            txs
        }.getOrDefault(cached(ctx, meta.id))
    }
}
