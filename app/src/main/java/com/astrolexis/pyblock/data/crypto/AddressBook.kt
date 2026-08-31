package com.astrolexis.pyblock.data.crypto

import android.content.Context
import org.bitcoindevkit.Address
import org.bitcoindevkit.Network
import org.json.JSONArray
import org.json.JSONObject
import java.text.Collator
import java.util.UUID

/** A saved plain Bitcoin address with a friendly name. Mirrors iOS AddressEntry.
 *  Device-local, public data — a bad write can never move funds. */
data class AddressEntry(
    val id: String,            // UUID.toString()
    val name: String,          // trimmed; may be blank (unnamed)
    val address: String,       // normalized mainnet, whitespace-trimmed
    val addedAt: Long,         // millis since epoch
) {
    val short: String get() = if (address.length <= 22) address else address.take(14) + "…" + address.takeLast(6)
    val displayName: String get() = name.ifBlank { short }
}

/** Saved Bitcoin addresses with friendly names, for recurring payments. Same JSON-prefs
 *  CRUD pattern as [PaynymBook]; mirrors the iOS AddressBook API 1:1. */
object AddressBook {
    private const val PREFS = "pyblock_addressbook"
    private const val KEY = "entries_v1"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Locale-aware, case-insensitive — replaces iOS localizedCaseInsensitiveCompare.
    private val collator = Collator.getInstance().apply { strength = Collator.SECONDARY }

    /** Valid mainnet address of any type. Gates saving. iOS: AddressBook.isValid. */
    fun isValid(address: String): Boolean =
        runCatching { Address(address.trim(), Network.BITCOIN) }.isSuccess

    fun all(ctx: Context): List<AddressEntry> {
        val raw = prefs(ctx).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                AddressEntry(
                    id = o.getString("id"),
                    name = o.optString("name", ""),
                    address = o.getString("address"),
                    addedAt = o.optLong("addedAt", 0),
                )
            }.sortedWith(compareBy(collator) { it.name })   // by name; blank sorts first
        }.getOrDefault(emptyList())
    }

    fun entry(ctx: Context, address: String): AddressEntry? {
        val a = address.trim()
        return all(ctx).firstOrNull { it.address == a }
    }

    /** iOS name(forAddress:) — nil/blank collapses to null. */
    fun name(ctx: Context, address: String): String? =
        entry(ctx, address)?.name?.ifBlank { null }

    /** Add or update by address. Returns null on invalid address; an existing address has
     *  its name updated instead of duplicating (iOS upsert). */
    fun upsert(ctx: Context, name: String, address: String): AddressEntry? {
        val addr = address.trim()
        if (!isValid(addr)) return null
        val nm = name.trim()
        val list = all(ctx).toMutableList()
        val idx = list.indexOfFirst { it.address == addr }
        val result: AddressEntry
        if (idx >= 0) {
            result = list[idx].copy(name = nm)
            list[idx] = result
        } else {
            result = AddressEntry(UUID.randomUUID().toString(), nm, addr, System.currentTimeMillis())
            list.add(result)
        }
        save(ctx, list)
        return result
    }

    fun setName(ctx: Context, id: String, name: String) {
        save(ctx, all(ctx).map { if (it.id == id) it.copy(name = name.trim()) else it })
    }

    fun remove(ctx: Context, id: String) {
        save(ctx, all(ctx).filterNot { it.id == id })
    }

    private fun save(ctx: Context, list: List<AddressEntry>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(JSONObject().put("id", e.id).put("name", e.name)
                .put("address", e.address).put("addedAt", e.addedAt))
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }
}
