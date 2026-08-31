package com.astrolexis.pyblock.data.wallet

import android.content.Context
import android.content.SharedPreferences
import com.astrolexis.pyblock.data.store.SecurePrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * The result of a ricochet build: the tx chain plus the ephemeral hop addresses and
 * their keys. The keys are KEPT (not thrown away) so the user can later PROVE the chain
 * is theirs — e.g. an exchange asking about the address that funded a deposit. Android
 * mirror of iOS `RicochetOutcome`.
 */
data class RicochetOutcome(
    val txids: List<String>,        // [0] = source spend … [last] = recipient payment
    val hopAddresses: List<String>, // hop0 … hop_{n-1}
    val hopWifs: List<String>,      // parallel to hopAddresses (provenance proof)
)

/** One ricochet hop address + its key, for the dedicated coin-control area + key export. */
data class RicochetHopEntry(
    val address: String,
    val wif: String,
    val isSender: Boolean,   // the last hop — the address a recipient/exchange saw
    val dateMs: Long,
)

/**
 * One completed ricochet send — the full tx chain plus metadata AND the hop
 * addresses/keys, so the otherwise-invisible throwaway hops stay inspectable and,
 * crucially, PROVABLE (compliance: the last hop is the address a recipient/exchange saw
 * as the sender; the user must be able to show it's theirs). Android mirror of iOS
 * `RicochetRecord`.
 */
@Serializable
data class RicochetRecord(
    val id: String,            // final txid (unique per ricochet)
    val dateMs: Long,
    val txids: List<String>,
    val hops: Int,
    val amountSats: Long,
    val toAddress: String,
    val network: String,       // "mainnet"
    val hopAddresses: List<String>,
    val hopWifs: List<String>,
) {
    val finalTxid: String get() = txids.lastOrNull() ?: id
    /** The address the recipient/exchange saw as the sender (the last hop before the
     *  recipient). If an exchange asks where a deposit came from, this is the address to
     *  prove ownership of — its key is in `hopWifs.last`. */
    val senderAddress: String? get() = hopAddresses.lastOrNull()
    val senderWif: String? get() = hopWifs.lastOrNull()
}

/**
 * Persistent history of ricochet sends, newest first. Stored ENCRYPTED
 * (Keystore-backed EncryptedSharedPreferences) because it holds hop WIFs — those keys
 * carry no funds after the ricochet (everything is swept forward) but are secrets kept
 * for provenance/message-signing, so they get the same at-rest protection as wallet
 * keys. Android mirror of iOS `RicochetHistoryStore`.
 */
object RicochetHistory {
    private const val PREFS = "pyblock_ricochet"
    private const val KEY_RECORDS = "records"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private var loaded = false

    private val _records = MutableStateFlow<List<RicochetRecord>>(emptyList())
    val records: StateFlow<List<RicochetRecord>> = _records.asStateFlow()

    private fun prefsOrNull(ctx: Context): SharedPreferences? =
        runCatching { SecurePrefs.open(ctx, PREFS, "${PREFS}_legacy", resetOnCorruption = false) }.getOrNull()

    @Synchronized
    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        val p = prefsOrNull(ctx) ?: return       // stay unloaded; retry next call
        loaded = true
        val raw = p.getString(KEY_RECORDS, null) ?: return
        val list = runCatching { json.decodeFromString<List<RicochetRecord>>(raw) }.getOrDefault(emptyList())
        _records.value = list.sortedByDescending { it.dateMs }
    }

    fun records(ctx: Context, network: String): List<RicochetRecord> {
        ensureLoaded(ctx)
        return _records.value.filter { it.network == network }
    }

    /** Every distinct ricochet hop address (with its key) across all ricochets of a
     *  network — for the dedicated "Ricochet addresses" coin-control area + key export. */
    fun hopEntries(ctx: Context, network: String): List<RicochetHopEntry> {
        ensureLoaded(ctx)
        val seen = HashSet<String>()
        val out = ArrayList<RicochetHopEntry>()
        for (r in _records.value.filter { it.network == network }) {
            r.hopAddresses.forEachIndexed { i, addr ->
                if (i < r.hopWifs.size && seen.add(addr)) {
                    out.add(RicochetHopEntry(addr, r.hopWifs[i], isSender = i == r.hopAddresses.size - 1, dateMs = r.dateMs))
                }
            }
        }
        return out
    }

    @Synchronized
    fun add(ctx: Context, outcome: RicochetOutcome, hops: Int, amountSats: Long, toAddress: String, network: String): RicochetRecord {
        ensureLoaded(ctx)
        val final = outcome.txids.lastOrNull() ?: UUID.randomUUID().toString()
        val r = RicochetRecord(
            id = final, dateMs = System.currentTimeMillis(), txids = outcome.txids, hops = hops,
            amountSats = amountSats, toAddress = toAddress, network = network,
            hopAddresses = outcome.hopAddresses, hopWifs = outcome.hopWifs,
        )
        val next = ArrayList(_records.value.filter { it.id != final })   // dedup on retry/rebuild
        next.add(0, r)
        _records.value = next
        persist(ctx)
        return r
    }

    @Synchronized
    fun remove(ctx: Context, id: String) {
        ensureLoaded(ctx)
        _records.value = _records.value.filter { it.id != id }
        persist(ctx)
    }

    private fun persist(ctx: Context) {
        val p = prefsOrNull(ctx) ?: return
        p.edit().putString(KEY_RECORDS, json.encodeToString(_records.value)).apply()
    }
}
