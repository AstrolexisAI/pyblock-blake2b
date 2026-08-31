package com.astrolexis.pyblock.data.wallet

import android.content.Context
import android.content.SharedPreferences
import com.astrolexis.pyblock.data.crypto.VaultCrypto
import com.astrolexis.pyblock.data.store.SecurePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.Base64

/**
 * Spending-password vault. Encrypts each wallet's WIF at rest under a random Vault
 * Master Key (VMK); the VMK is wrapped by the password (scrypt) and, optionally,
 * stored behind biometrics. Locked → the VMK isn't in memory, so keys can't be
 * read/spent even on an unlocked device. Mirror of the iOS `WalletVault`.
 *
 * FUND-CRITICAL migration invariant: the VMK's password-wrap is persisted BEFORE
 * any plaintext key is overwritten, and each key is re-encrypted only after its
 * ciphertext is verified to decrypt back to the exact original. A crash mid-migration
 * leaves every key recoverable; the sweep finishes on the next unlock.
 */
object WalletVault {
    private const val PREFS = "pyblock_vault"
    private const val KEY_PW = "vmk-pw"          // WrappedKey.serialize()
    const val MAGIC = "PBV1:"                     // marks an encrypted WIF pref value

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    private var vmk: ByteArray? = null
    private var initialized = false
    // Bumped on every lock(); an in-flight async unlock/enable checks it so a lock during
    // the scrypt await can't resurrect the VMK afterwards.
    @Volatile private var lockGen = 0

    private fun prefs(ctx: Context): SharedPreferences? =
        runCatching { SecurePrefs.open(ctx, PREFS, "${PREFS}_legacy", resetOnCorruption = false) }.getOrNull()

    /** Learn whether a password is already set. Idempotent; retries if the secure
     *  store was momentarily unavailable (never marks itself done on failure). */
    fun init(ctx: Context) {
        if (initialized) return
        val p = prefs(ctx) ?: return               // Keystore not ready yet — retry next call
        initialized = true
        _enabled.value = p.getString(KEY_PW, null) != null
    }

    val isUnlocked: Boolean get() = vmk != null

    // MARK: blob helpers (used by WalletStore)

    fun isEncrypted(value: String): Boolean = value.startsWith(MAGIC)

    fun encrypt(wif: String): String? {
        val k = vmk ?: return null
        val blob = VaultCrypto.seal(wif.toByteArray(Charsets.UTF_8), k) ?: return null
        return MAGIC + Base64.getEncoder().encodeToString(blob)
    }

    fun decrypt(value: String): String? {
        if (!isEncrypted(value)) return null
        val k = vmk ?: return null
        val blob = runCatching { Base64.getDecoder().decode(value.removePrefix(MAGIC)) }.getOrNull() ?: return null
        val plain = VaultCrypto.open(blob, k) ?: return null
        return String(plain, Charsets.UTF_8)
    }

    // MARK: setup / migration

    /** Generate a VMK, persist its password-wrap FIRST, then encrypt every plaintext
     *  WIF (verify-before-overwrite). Returns false and rolls back cleanly on failure. */
    suspend fun enable(ctx: Context, password: String): Boolean {
        if (_enabled.value) return false
        val p = prefs(ctx) ?: return false
        val gen = lockGen
        val newVmk = VaultCrypto.randomBytes(32)
        val wrapped = withContext(Dispatchers.Default) { VaultCrypto.wrapVMK(newVmk, password) } ?: return false
        // 1) Persist the password-wrap FIRST — from here the VMK is recoverable.
        if (!p.edit().putString(KEY_PW, wrapped.serialize()).commit()) return false
        // 1b) FUND-CRITICAL: prove the PERSISTED wrap unwraps back to the VMK BEFORE we
        // overwrite any plaintext — guards against a truncating/lying store write.
        val reloaded = VaultCrypto.WrappedKey.parse(p.getString(KEY_PW, null) ?: return false)
            ?: run { p.edit().remove(KEY_PW).commit(); return false }
        val check = withContext(Dispatchers.Default) { VaultCrypto.unwrapVMK(reloaded, password) }
        if (check == null || !check.contentEquals(newVmk) || gen != lockGen) {
            p.edit().remove(KEY_PW).commit(); return false      // bad wrap or locked mid-setup → don't touch keys
        }
        vmk = newVmk
        _unlocked.value = true
        _enabled.value = true
        // 2) Encrypt any plaintext keys now (safe to re-run; finishes on unlock too).
        encryptPendingKeys(ctx)
        return true
    }

    /** Re-encrypt any wallet keys still stored in plaintext, verifying each round-trip
     *  before overwriting, and restoring the plaintext if the encrypted write doesn't land. */
    fun encryptPendingKeys(ctx: Context) {
        if (vmk == null) return
        WalletStore.ensureLoaded(ctx)
        for (w in WalletStore.wallets.value) {
            val raw = WalletStore.rawWif(ctx, w.id) ?: continue
            if (isEncrypted(raw)) continue
            val enc = encrypt(raw) ?: continue
            if (decrypt(enc) != raw) continue                       // verify ciphertext BEFORE overwriting
            val ok = WalletStore.writeRawWif(ctx, w.id, enc)
            if (!ok || WalletStore.rawWif(ctx, w.id) != enc) {
                WalletStore.writeRawWif(ctx, w.id, raw)             // restore — never leave a key missing
            }
        }
    }

    // MARK: unlock / lock

    suspend fun unlock(ctx: Context, password: String): Boolean {
        val p = prefs(ctx) ?: return false
        val wrapped = VaultCrypto.WrappedKey.parse(p.getString(KEY_PW, null) ?: return false) ?: return false
        val gen = lockGen
        val recovered = withContext(Dispatchers.Default) { VaultCrypto.unwrapVMK(wrapped, password) } ?: return false
        // If a lock() intervened during the scrypt await, honor it — don't resurrect the VMK.
        if (gen != lockGen) { recovered.fill(0); return false }
        vmk = recovered
        _unlocked.value = true
        encryptPendingKeys(ctx)
        WalletStore.backfillPubkeys(ctx)   // keys reachable now → cache watch-only pubkeys for locked sync
        return true
    }

    /** Wipe the VMK from memory (auto-lock on background). */
    fun lock() {
        lockGen++
        vmk?.fill(0)
        vmk = null
        _unlocked.value = false
    }
}
