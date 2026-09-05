package com.astrolexis.pyblock.data.wallet

import android.content.Context
import com.astrolexis.pyblock.data.crypto.PaymentCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Saved send destinations — a plain address book. A contact's [value] is either a Bitcoin address
 * (1…/bc1q…) or a BIP-47 PayNym payment code (PM…). Not secret (destinations aren't keys), so it
 * lives in a normal SharedPreferences as JSON. Mirrors the iOS `BlakeContactsStore`.
 */
@Serializable
data class BlakeContact(val id: String, val label: String, val value: String, val createdAt: Long) {
    val isPaymentCode: Boolean get() = looksLikePaymentCode(value)

    companion object {
        fun looksLikePaymentCode(s: String) = PaymentCode.looksLikePaymentCode(s.trim())

        /** Lightweight validity: a PM code OR a plausible mainnet address (same test the wizard uses). */
        fun isValidDestination(s: String): Boolean {
            val a = s.trim()
            if (a.isEmpty()) return false
            if (looksLikePaymentCode(a)) return true
            if (a.length < 26) return false
            if (a.startsWith("1") || a.startsWith("3")) return a.length <= 35
            if (a.lowercase().startsWith("bc1")) return a.length <= 62
            return false
        }
    }
}

object BlakeContactsStore {
    private const val PREFS = "pyblockb_contacts"
    private const val KEY = "contacts.v1"
    private val json = Json { ignoreUnknownKeys = true }

    private val _contacts = MutableStateFlow<List<BlakeContact>>(emptyList())
    val contacts: StateFlow<List<BlakeContact>> = _contacts.asStateFlow()
    private var prefs: android.content.SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs != null) return
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs?.getString(KEY, null)
        if (raw != null) {
            _contacts.value = runCatching { json.decodeFromString<List<BlakeContact>>(raw) }
                .getOrDefault(emptyList()).sortedBy { it.label.lowercase() }
        }
    }

    private fun persist() {
        val sorted = _contacts.value.sortedBy { it.label.lowercase() }
        _contacts.value = sorted
        prefs?.edit()?.putString(KEY, json.encodeToString(sorted))?.apply()
    }

    /** Saved label for a destination value, if any. */
    fun labelFor(value: String): String? {
        val v = value.trim()
        return _contacts.value.firstOrNull { it.value == v }?.label
    }

    /** Upsert by value (re-saving the same destination just updates its label). */
    fun add(label: String, value: String) {
        val v = value.trim(); val l = label.trim()
        val existing = _contacts.value.firstOrNull { it.value == v }
        _contacts.value = if (existing != null) {
            _contacts.value.map { if (it.id == existing.id) it.copy(label = if (l.isEmpty()) it.label else l) else it }
        } else {
            _contacts.value + BlakeContact(UUID.randomUUID().toString(), if (l.isEmpty()) v.take(12) else l, v, System.currentTimeMillis())
        }
        persist()
    }

    fun remove(c: BlakeContact) { _contacts.value = _contacts.value.filter { it.id != c.id }; persist() }
}
