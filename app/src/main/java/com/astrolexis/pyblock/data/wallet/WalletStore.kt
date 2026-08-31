package com.astrolexis.pyblock.data.wallet

import android.content.Context
import android.content.SharedPreferences
import com.astrolexis.pyblock.data.store.SecurePrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persists saved vanity wallets. Metadata (address, label, cached balance) is a
 * JSON blob; each private key (WIF) is stored separately under its own key. Both
 * live in Keystore-backed EncryptedSharedPreferences. Compose-observable list.
 *
 * Fund-critical: opened with resetOnCorruption=false so a transient Keystore
 * failure can NEVER wipe the WIFs — access degrades (returns null) and recovers
 * on the next successful open instead of destroying keys.
 */
object WalletStore {
    private const val PREFS = "pyblock_wallets"
    private const val KEY_LIST = "wallets-json"
    private fun wifKey(id: String) = "wif-$id"

    private val _wallets = MutableStateFlow<List<VanityWallet>>(emptyList())
    val wallets: StateFlow<List<VanityWallet>> = _wallets.asStateFlow()
    private var loaded = false

    /** Null if the secure store is temporarily unavailable (never wipes). */
    private fun prefsOrNull(ctx: Context): SharedPreferences? =
        runCatching { SecurePrefs.open(ctx, PREFS, "${PREFS}_legacy", resetOnCorruption = false) }.getOrNull()

    @Synchronized
    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        val p = prefsOrNull(ctx) ?: return          // stay unloaded; retry next call
        loaded = true
        val json = p.getString(KEY_LIST, null) ?: return
        val loadedList = runCatching { Json.decodeFromString<List<VanityWallet>>(json) }.getOrDefault(emptyList())
        // Collapse any duplicate-address wallets (e.g. the same key imported twice)
        // — two entries for one address would double-count its balance + activity.
        // Keep the first; the address and its funds stay fully tracked via that entry.
        val seen = HashSet<String>()
        val deduped = loadedList.filter { seen.add(it.address) }
        _wallets.value = deduped
        if (deduped.size != loadedList.size) persist(p)
        // Backfill watch-only pubkeys for any legacy wallets whose keys are
        // reachable now (vault off / already unlocked). Locked wallets retry on unlock.
        if (_wallets.value.any { it.pubkeyHex == null }) backfillPubkeys(ctx)
    }

    /** @return true if the key + metadata were durably saved.
     *  Callers run on the WS thread, Main (sweep/UI) and IO — @Synchronized +
     *  atomic update{} keep the list/persist pair consistent across all of them. */
    @Synchronized
    fun add(ctx: Context, wallet: VanityWallet, wifPlain: String): Boolean {
        ensureLoaded(ctx)
        val p = prefsOrNull(ctx) ?: return false
        // Address-level dedupe (defense in depth vs racing claimers): the same
        // key under a second UUID would double-count the balance.
        if (_wallets.value.any { it.address == wallet.address && it.id != wallet.id }) return true
        // Vault on + unlocked → store the WIF encrypted; otherwise plaintext (the
        // vault's unlock sweep encrypts any plaintext keys later).
        val toStore = if (WalletVault.enabled.value && WalletVault.isUnlocked)
            (WalletVault.encrypt(wifPlain) ?: wifPlain) else wifPlain
        // Cache the WATCH-ONLY public key up front (validated against the address),
        // so the node can sync from the pubkey alone — even with the vault locked —
        // without ever loading the WIF into the long-running wallet.
        val entry = if (wallet.pubkeyHex == null)
            wallet.copy(pubkeyHex = com.astrolexis.pyblock.data.crypto.VanityCrypto.validatedPubkeyHex(wifPlain, wallet.address))
        else wallet
        // The private key is fund-critical: commit() it durably BEFORE the metadata,
        // so a crash can never leave a wallet entry without its key.
        if (!p.edit().putString(wifKey(entry.id), toStore).commit()) return false
        _wallets.update { list -> list.filter { it.id != entry.id } + entry }
        persist(p)
        // Empty tx baseline so the wallet's first incoming tx (e.g. a PayNym
        // receive) notifies instead of being swallowed as historical.
        WalletTxNotifier.markNew(ctx, entry.id)
        return true
    }

    /** Cache a wallet's watch-only public key (enables locked-vault sync). No-op if
     *  unchanged. Never touches the WIF. */
    @Synchronized
    fun setPubkey(ctx: Context, id: String, pubkeyHex: String) {
        ensureLoaded(ctx)
        val p = prefsOrNull(ctx) ?: return
        var changed = false
        _wallets.update { list ->
            list.map {
                if (it.id == id && it.pubkeyHex != pubkeyHex) { changed = true; it.copy(pubkeyHex = pubkeyHex) } else it
            }
        }
        if (changed) persist(p)
    }

    /** Derive + cache the watch-only pubkey for every legacy wallet whose key is
     *  currently accessible (vault unlocked or off), validating it reproduces the
     *  saved address before storing. Idempotent; skips wallets already cached or
     *  currently locked (they retry on the next unlock). */
    fun backfillPubkeys(ctx: Context) {
        ensureLoaded(ctx)
        for (w in _wallets.value) {
            if (w.pubkeyHex != null) continue
            val wif = wif(ctx, w.id) ?: continue                 // locked/unavailable → retry next unlock
            val hex = com.astrolexis.pyblock.data.crypto.VanityCrypto.validatedPubkeyHex(wif, w.address) ?: continue
            setPubkey(ctx, w.id, hex)
        }
    }

    @Synchronized
    fun remove(ctx: Context, id: String) {
        ensureLoaded(ctx)
        val p = prefsOrNull(ctx) ?: return
        p.edit().remove(wifKey(id)).apply()
        _wallets.update { list -> list.filter { it.id != id } }
        persist(p)
    }

    /** Spendable WIF: decrypts through the vault when on (null while locked); plaintext otherwise. */
    fun wif(ctx: Context, id: String): String? {
        // FUND-CRITICAL chokepoint: vault on but locked → never hand out a spendable key,
        // even a plaintext-at-rest one (e.g. a PayNym key received while locked). Gates
        // every spend/reveal/sign surface, not just the wallet route.
        if (WalletVault.enabled.value && !WalletVault.isUnlocked) return null
        val raw = rawWif(ctx, id) ?: return null
        return if (WalletVault.isEncrypted(raw)) WalletVault.decrypt(raw) else raw
    }

    /** Raw stored value (encrypted blob or plaintext WIF) — used by the vault sweep. */
    fun rawWif(ctx: Context, id: String): String? = prefsOrNull(ctx)?.getString(wifKey(id), null)

    /** Overwrite a wallet's stored key value (vault swaps plaintext → encrypted). */
    fun writeRawWif(ctx: Context, id: String, value: String): Boolean =
        prefsOrNull(ctx)?.edit()?.putString(wifKey(id), value)?.commit() ?: false

    /** Update the cached balance/tip after a sync so the list stays warm. */
    @Synchronized
    fun updateCache(ctx: Context, id: String, balanceSats: Long, tip: Int) {
        ensureLoaded(ctx)
        val p = prefsOrNull(ctx) ?: return
        _wallets.update { list ->
            list.map {
                if (it.id == id) it.copy(cachedBalanceSats = balanceSats, cachedTip = tip) else it
            }
        }
        persist(p)
    }

    private fun persist(p: SharedPreferences) {
        p.edit().putString(KEY_LIST, Json.encodeToString(_wallets.value)).apply()
    }
}
