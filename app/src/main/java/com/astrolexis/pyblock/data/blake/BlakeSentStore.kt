package com.astrolexis.pyblock.data.blake

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Locally-recorded outgoing transactions (the fork exposes only UTXOs, no per-wallet tx
 * status). We keep what we broadcast so sends show in ACTIVITY as "sent", and mark them
 * confirmed once their spent coins leave the UTXO set. Mirrors iOS `SentStore`.
 */
data class SentRecord(
    val id: String,               // final txid
    val date: Long,               // epoch seconds
    val amountSats: Long,
    val toAddress: String,
    val spentCoinIds: List<String>,
    val ricochet: Boolean,
)

object BlakeSentStore {
    private const val KEY = "pyblockb.sent.v1"
    private var prefs: android.content.SharedPreferences? = null

    private val _records = MutableStateFlow<List<SentRecord>>(emptyList())
    val records: StateFlow<List<SentRecord>> = _records.asStateFlow()

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("pyblockb.sent", Context.MODE_PRIVATE)
        load()
    }

    fun add(txid: String, amountSats: Long, toAddress: String, spentCoinIds: Set<String>, ricochet: Boolean) {
        val r = SentRecord(txid, System.currentTimeMillis() / 1000, amountSats, toAddress, spentCoinIds.toList(), ricochet)
        _records.value = (listOf(r) + _records.value.filter { it.id != txid })
        persist()
    }

    /** Pending while any spent coin is still live; unknown inputs → pending for 30 min. */
    fun isPending(r: SentRecord, liveCoinIds: Set<String>): Boolean =
        if (r.spentCoinIds.isNotEmpty()) r.spentCoinIds.any { it in liveCoinIds }
        else System.currentTimeMillis() / 1000 - r.date < 1800

    fun remove(id: String) { _records.value = _records.value.filter { it.id != id }; persist() }

    private fun load() {
        val s = prefs?.getString(KEY, null) ?: return
        val out = ArrayList<SentRecord>()
        runCatching {
            val arr = JSONArray(s)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val coins = ArrayList<String>()
                o.optJSONArray("spent")?.let { for (j in 0 until it.length()) coins.add(it.getString(j)) }
                out.add(SentRecord(o.getString("id"), o.getLong("date"), o.getLong("amt"),
                    o.getString("to"), coins, o.optBoolean("ric")))
            }
        }
        _records.value = out.sortedByDescending { it.date }
    }

    private fun persist() {
        val arr = JSONArray()
        _records.value.forEach { r ->
            arr.put(JSONObject().put("id", r.id).put("date", r.date).put("amt", r.amountSats)
                .put("to", r.toAddress).put("ric", r.ricochet).put("spent", JSONArray(r.spentCoinIds)))
        }
        prefs?.edit()?.putString(KEY, arr.toString())?.apply()
    }
}
