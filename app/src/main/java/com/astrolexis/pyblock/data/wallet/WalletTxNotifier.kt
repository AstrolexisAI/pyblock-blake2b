package com.astrolexis.pyblock.data.wallet

import android.content.Context
import com.astrolexis.pyblock.push.PushNotifier
import org.json.JSONObject

/**
 * Local notifications for incoming wallet transactions, in two stages:
 *  1. **received** — first time an incoming tx is seen (0-conf / mempool),
 *  2. **confirmed** — when it reaches ≥1 confirmation on-chain.
 *
 * Per-txid state is persisted so each transition fires once. A wallet is marked
 * "new" at creation (empty baseline) so a freshly-generated or PayNym-imported
 * wallet DOES notify its first incoming payment, while a pre-existing wallet
 * seen for the first time establishes a silent baseline. Mirrors iOS
 * WalletTxNotifier.swift.
 */
object WalletTxNotifier {
    data class Incoming(val txid: String, val sats: Long, val confirmed: Boolean)

    private const val PREFS = "pyblock_txnotif"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun initKey(id: String) = "init.$id"
    private fun stateKey(id: String) = "state.$id"

    /** Mark a just-created wallet (0 txs) so its first incoming tx notifies. */
    fun markNew(ctx: Context, walletId: String) {
        prefs(ctx).edit().putBoolean(initKey(walletId), true).putString(stateKey(walletId), "{}").apply()
    }

    fun reconcile(ctx: Context, walletId: String, label: String, incoming: List<Incoming>) {
        val p = prefs(ctx)
        val initialized = p.getBoolean(initKey(walletId), false)
        val state = load(p.getString(stateKey(walletId), null))
        for (tx in incoming) {
            if (tx.sats <= 0) continue
            val now = if (tx.confirmed) 1 else 0
            val prev = state[tx.txid]
            if (initialized) {
                if (prev == null) {
                    val tail = if (tx.confirmed) "confirmed" else "pending · 0-conf"
                    PushNotifier.notifyWallet(ctx, "⚡ Payment received", "+${fmt(tx.sats)} sats to $label · $tail")
                } else if (prev == 0 && now == 1) {
                    PushNotifier.notifyWallet(ctx, "✓ Payment confirmed", "+${fmt(tx.sats)} sats to $label confirmed on-chain")
                }
            }
            state[tx.txid] = maxOf(prev ?: 0, now)   // monotonic: never un-confirm
        }
        val e = p.edit().putString(stateKey(walletId), save(state))
        if (!initialized) e.putBoolean(initKey(walletId), true)
        e.apply()
    }

    private fun load(s: String?): MutableMap<String, Int> {
        val m = HashMap<String, Int>()
        if (s != null) runCatching {
            val o = JSONObject(s)
            o.keys().forEach { m[it] = o.getInt(it) }
        }
        return m
    }

    private fun save(m: Map<String, Int>): String {
        val o = JSONObject()
        m.forEach { (k, v) -> o.put(k, v) }
        return o.toString()
    }

    private fun fmt(n: Long): String = "%,d".format(n)
}
