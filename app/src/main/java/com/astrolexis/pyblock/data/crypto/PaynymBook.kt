package com.astrolexis.pyblock.data.crypto

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A saved PayNym peer — their "PM8T…" payment code plus an optional [alias]. The primary
 * identifier is the deterministic cosmic name ([PaynymName]); the alias is a personal label.
 * Codes are public identifiers, so contacts live in plain SharedPreferences (JSON). Mirrors the
 * iOS PaynymBook.
 */
data class PaynymContact(
    val id: String,
    val alias: String,
    val code: String,
    val nostr: String?,
    val addedAt: Long,
) {
    /** Alias if set, else the deterministic cosmic name. */
    val displayName: String get() = if (alias.isBlank()) PaynymName.cosmic(code) else alias
}

object PaynymBook {
    private const val PREFS = "pyblock_paynym_contacts"
    private const val KEY = "contacts_v1"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(ctx: Context): List<PaynymContact> {
        val raw = prefs(ctx).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                PaynymContact(
                    id = o.getString("id"),
                    alias = o.optString("alias", ""),
                    code = o.getString("code"),
                    nostr = o.optString("nostr", "").takeIf { it.isNotBlank() },
                    addedAt = o.optLong("addedAt", 0),
                )
            }.sortedBy { it.displayName.lowercase() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun contactForCode(ctx: Context, code: String): PaynymContact? = all(ctx).firstOrNull { it.code == code }

    /** Add or update by code. Returns null if [code] is not a valid BIP-47 payment code.
     *  Blank alias → the cosmic name is shown. */
    const val AUTO_PLACEHOLDER = "someone from the chat"

    /** A contact that arrives on its own, from the chat. Added if missing; a placeholder alias takes
     *  the real name when it arrives. An alias the user typed is never touched. */
    fun autoLabel(ctx: Context, code: String, name: String?, nostr: String? = null) {
        val cc = code.trim()
        if (PaymentCode.decode(cc) == null) return
        val cur = all(ctx).firstOrNull { it.code == cc }
        when {
            cur == null -> upsert(ctx, name?.takeIf { it.isNotBlank() } ?: AUTO_PLACEHOLDER, cc, nostr)
            cur.alias == AUTO_PLACEHOLDER && !name.isNullOrBlank() -> upsert(ctx, name, cc, nostr)
        }
    }

    fun aliasFor(ctx: Context, code: String): String? =
        all(ctx).firstOrNull { it.code == code }?.alias?.takeIf { it.isNotBlank() && it != AUTO_PLACEHOLDER }

    fun upsert(ctx: Context, alias: String, code: String, nostr: String? = null): PaynymContact? {
        val cc = code.trim()
        if (PaymentCode.decode(cc) == null) return null
        val a = alias.trim()
        val list = all(ctx).toMutableList()
        val idx = list.indexOfFirst { it.code == cc }
        val result: PaynymContact
        if (idx >= 0) {
            val cur = list[idx]
            result = cur.copy(alias = if (a.isNotBlank()) a else cur.alias, nostr = nostr ?: cur.nostr)
            list[idx] = result
        } else {
            result = PaynymContact(UUID.randomUUID().toString(), a, cc, nostr, System.currentTimeMillis())
            list.add(result)
        }
        save(ctx, list)
        return result
    }

    fun setAlias(ctx: Context, id: String, alias: String) {
        save(ctx, all(ctx).map { if (it.id == id) it.copy(alias = alias.trim()) else it })
    }

    fun remove(ctx: Context, id: String) {
        save(ctx, all(ctx).filterNot { it.id == id })
    }

    private fun save(ctx: Context, list: List<PaynymContact>) {
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(
                JSONObject()
                    .put("id", c.id).put("alias", c.alias).put("code", c.code)
                    .put("nostr", c.nostr ?: "").put("addedAt", c.addedAt),
            )
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }
}
