package com.astrolexis.pyblock.data.wallet

import android.content.Context
import org.json.JSONObject

/** Per-UTXO metadata (label + frozen/do-not-spend). Keyed on "txid:vout". Persisted (survives
 *  re-sync, not derived from the live wallet). Mirrors iOS UtxoMetaStore. Freezing here is
 *  enforced in [BdkNode.send] — a frozen coin is never selected and the final tx is asserted to
 *  spend none. */
object UtxoMetaStore {
    private const val PREFS = "pyblock_utxo_meta"
    private const val KEY = "meta_v1"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun key(txid: String, vout: Int) = "$txid:$vout"

    data class Meta(val label: String = "", val frozen: Boolean = false,
                    val sats: Long = 0L, val walletId: String = "")

    private fun load(ctx: Context): MutableMap<String, Meta> {
        val raw = prefs(ctx).getString(KEY, null) ?: return mutableMapOf()
        return runCatching {
            val o = JSONObject(raw)
            val m = mutableMapOf<String, Meta>()
            o.keys().forEach { k ->
                val e = o.getJSONObject(k)
                m[k] = Meta(e.optString("label", ""), e.optBoolean("frozen", false),
                    e.optLong("sats", 0), e.optString("walletId", ""))
            }
            m
        }.getOrDefault(mutableMapOf())
    }

    private fun save(ctx: Context, m: Map<String, Meta>) {
        val o = JSONObject()
        m.forEach { (k, v) ->
            if (v.label.isNotEmpty() || v.frozen) {   // drop empty entries to keep the store lean
                o.put(k, JSONObject().put("label", v.label).put("frozen", v.frozen)
                    .put("sats", v.sats).put("walletId", v.walletId))
            }
        }
        prefs(ctx).edit().putString(KEY, o.toString()).apply()
    }

    fun isFrozen(ctx: Context, key: String): Boolean = load(ctx)[key]?.frozen == true
    fun frozenKeys(ctx: Context): Set<String> = load(ctx).filterValues { it.frozen }.keys
    fun label(ctx: Context, key: String): String = load(ctx)[key]?.label ?: ""

    /** All-wallet frozen total. */
    fun frozenSats(ctx: Context): Long = load(ctx).values.filter { it.frozen }.sumOf { it.sats }

    /** Single-wallet frozen total (used by the SEND spendable calc). */
    fun frozenSats(ctx: Context, walletId: String): Long =
        load(ctx).values.filter { it.frozen && it.walletId == walletId }.sumOf { it.sats }

    fun setFrozen(ctx: Context, key: String, frozen: Boolean, sats: Long, walletId: String) {
        val m = load(ctx)
        val cur = m[key] ?: Meta()
        m[key] = cur.copy(frozen = frozen, sats = sats, walletId = walletId)
        save(ctx, m)
    }

    fun toggleFrozen(ctx: Context, key: String, sats: Long, walletId: String) =
        setFrozen(ctx, key, !isFrozen(ctx, key), sats, walletId)

    fun setLabel(ctx: Context, key: String, label: String, walletId: String) {
        val m = load(ctx)
        val cur = m[key] ?: Meta()
        m[key] = cur.copy(label = label.trim(), walletId = walletId)
        save(ctx, m)
    }
}

/** A spendable UTXO decorated with persisted freeze/label metadata (read-model for the
 *  COINS hub + the SEND coin picker). Mirrors iOS WalletNode.UtxoInfo. */
data class UtxoInfo(
    val key: String,          // "txid:vout"
    val txid: String,
    val vout: Int,
    val valueSats: Long,
    val confirmations: Int,   // 0 if unconfirmed
    val walletId: String,
    val walletLabel: String,
    val address: String,      // best-effort; "" if not resolvable
    val label: String,        // from UtxoMetaStore
    val frozen: Boolean,      // from UtxoMetaStore
)
