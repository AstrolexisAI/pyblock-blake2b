package com.astrolexis.pyblock.data.blake

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * User labels for coins and transactions. Keyed by a UTXO id "txid:vout" (a coin) or a txid (a
 * sent/received tx). The two namespaces don't collide (a txid has no ":"). Not secret — plain
 * SharedPreferences JSON. Mirrors iOS `BlakeLabelStore`.
 */
object BlakeLabelStore {
    private const val PREFS = "pyblockb_labels"
    private const val KEY = "labels.v1"
    private val json = Json { ignoreUnknownKeys = true }

    private val _labels = MutableStateFlow<Map<String, String>>(emptyMap())
    val labels: StateFlow<Map<String, String>> = _labels.asStateFlow()
    private var prefs: android.content.SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs != null) return
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs?.getString(KEY, null)?.let { raw ->
            _labels.value = runCatching { json.decodeFromString<Map<String, String>>(raw) }.getOrDefault(emptyMap())
        }
    }

    /** The label for a coin/tx id, or null if none/empty. */
    fun labelFor(id: String): String? = _labels.value[id]?.takeIf { it.isNotBlank() }

    fun set(id: String, label: String) {
        val t = label.trim()
        _labels.value = if (t.isEmpty()) _labels.value - id else _labels.value + (id to t)
        prefs?.edit()?.putString(KEY, json.encodeToString(_labels.value))?.apply()
    }
}
