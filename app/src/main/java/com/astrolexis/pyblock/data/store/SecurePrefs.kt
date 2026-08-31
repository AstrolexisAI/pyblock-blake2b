package com.astrolexis.pyblock.data.store

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File

/** Thrown when a fund-critical secure store can't be opened and we refuse to
 *  destroy its (possibly recoverable) contents. Callers should degrade, not wipe. */
class SecurePrefsUnavailableException(name: String, cause: Throwable) :
    Exception("secure store '$name' unavailable", cause)

/** Keystore-backed encrypted SharedPreferences for at-rest secrets (wallet WIFs,
 *  device HMAC secret, LNURL session token). Migrates any legacy plaintext prefs
 *  of the same logical store on first open, then wipes them. */
object SecurePrefs {

    /**
     * @param resetOnCorruption when false (fund-critical stores, e.g. wallet keys),
     * a Keystore/decrypt failure NEVER deletes the store — a transient failure
     * (locked device / direct-boot / restore) must not silently destroy the private
     * keys. The encrypted blob is preserved and a [SecurePrefsUnavailableException]
     * is thrown so the caller degrades and recovers on the next successful open.
     */
    fun open(ctx: Context, encName: String, legacyName: String, resetOnCorruption: Boolean = true): SharedPreferences {
        val enc = create(ctx, encName, resetOnCorruption)
        migrateLegacy(ctx, legacyName, enc)
        return enc
    }

    private fun create(ctx: Context, name: String, resetOnCorruption: Boolean): SharedPreferences {
        // MasterKey.Builder(...).build() touches the Keystore and can THROW on real
        // devices (direct-boot / user not unlocked / OEM keystore / restore) — keep
        // it INSIDE the guarded path so it can never crash the app at launch.
        fun buildPrefs(): SharedPreferences {
            val key = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                ctx, name, key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
        return try {
            buildPrefs()
        } catch (e: Throwable) {   // Throwable: Keystore can surface Errors too
            // Always preserve the encrypted blob before ANY reset, so nothing is
            // ever irrecoverably destroyed by a corrupt/unavailable-keyset path.
            runCatching { backupPrefsFile(ctx, name) }
            if (!resetOnCorruption) {
                // Fund-critical: fail loud, keep the data, recover next open.
                throw SecurePrefsUnavailableException(name, e)
            }
            // Non-critical (regenerable identity/token): recreate.
            ctx.deleteSharedPreferences(name)
            buildPrefs()
        }
    }

    /** Copy the raw prefs file aside (best-effort) so a wipe is never destructive.
     *  Keeps only the latest copy so repeated failures don't accumulate on disk. */
    private fun backupPrefsFile(ctx: Context, name: String) {
        val dir = File(ctx.applicationInfo.dataDir, "shared_prefs")
        val src = File(dir, "$name.xml")
        if (!src.exists()) return
        dir.listFiles { f -> f.name.startsWith("$name.corrupt-") && f.name.endsWith(".bak") }?.forEach { it.delete() }
        src.copyTo(File(dir, "$name.corrupt-${System.currentTimeMillis()}.bak"), overwrite = true)
    }

    private fun migrateLegacy(ctx: Context, legacyName: String, enc: SharedPreferences) {
        val legacy = ctx.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
        val old = legacy.all
        if (old.isEmpty() || enc.all.isNotEmpty()) return
        val e = enc.edit()
        for ((k, v) in old) when (v) {
            is String -> e.putString(k, v)
            is Int -> e.putInt(k, v)
            is Long -> e.putLong(k, v)
            is Boolean -> e.putBoolean(k, v)
            is Float -> e.putFloat(k, v)
        }
        e.apply()
        legacy.edit().clear().apply()
    }
}
