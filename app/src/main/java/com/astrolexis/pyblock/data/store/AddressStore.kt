package com.astrolexis.pyblock.data.store

import android.content.Context
import android.content.SharedPreferences
import com.astrolexis.pyblock.data.model.TrackedAddress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Local persistence of tracked BTC addresses (SharedPreferences + JSON).
 *  Initialised once from [com.astrolexis.pyblock.PyBlockApp]. */
object AddressStore {
    private const val PREFS = "pyblock.addresses"
    private const val KEY = "tracked"
    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var prefs: SharedPreferences
    private val _addresses = MutableStateFlow<List<TrackedAddress>>(emptyList())
    val addresses: StateFlow<List<TrackedAddress>> = _addresses.asStateFlow()

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _addresses.value = runCatching {
            prefs.getString(KEY, null)?.let { json.decodeFromString<List<TrackedAddress>>(it) } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    fun add(address: String, label: String?) {
        val a = address.trim()
        if (a.isEmpty() || _addresses.value.any { it.address == a }) return
        _addresses.value = _addresses.value + TrackedAddress(a, label?.trim()?.ifEmpty { null })
        persist()
    }

    fun remove(address: String) {
        _addresses.value = _addresses.value.filterNot { it.address == address }
        persist()
    }

    private fun persist() {
        prefs.edit().putString(KEY, json.encodeToString(_addresses.value)).apply()
    }
}
