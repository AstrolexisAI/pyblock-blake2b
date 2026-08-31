package com.astrolexis.pyblock.data.store

import android.content.Context
import android.content.SharedPreferences
import com.astrolexis.pyblock.data.model.AnonRegReq
import com.astrolexis.pyblock.data.net.ApiClient

/** Anonymous device registration for HMAC-authed order creation. Persists
 *  device_id + secret. */
object DeviceStore {
    private const val PREFS = "pyblock.device"
    // Nullable + resetOnCorruption=false: this holds the device_id + HMAC secret
    // that IS the anonymous account (entitlements, orders, purchases). A transient
    // Keystore failure must NEVER wipe it — degrade (unavailable this session, blob
    // preserved, retry next launch) instead of regenerating a throwaway account.
    private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        prefs = runCatching { SecurePrefs.open(ctx, "$PREFS.enc", PREFS, resetOnCorruption = false) }.getOrNull()
    }

    private val deviceId: Int get() = prefs?.getInt("device_id", 0) ?: 0
    private val secret: String? get() = prefs?.getString("secret", null)

    /** Current creds without triggering a registration (for account backup). */
    fun peek(): Pair<Int, String>? {
        val id = deviceId; val s = secret
        return if (id != 0 && !s.isNullOrEmpty()) id to s else null
    }

    /** Adopt a backed-up account (restore on a new device). The server keeps
     *  honouring this device_id+secret, so entitlements follow. */
    fun import(id: Int, secret: String) {
        prefs?.edit()?.putInt("device_id", id)?.putString("secret", secret)?.apply()
    }

    /** Portable account string: `pyblock-acct:v1:<id>:<secret>`. */
    fun backupString(): String? = peek()?.let { (id, s) -> "pyblock-acct:v1:$id:$s" }

    /** Parse a backup string → (id, secret), or null if malformed. */
    fun parseBackup(raw: String): Pair<Int, String>? {
        val parts = raw.trim().split(":")
        if (parts.size < 4 || parts[0] != "pyblock-acct") return null
        val id = parts[2].toIntOrNull() ?: return null
        val secret = parts.drop(3).joinToString(":")   // secret may contain ':'
        return if (id != 0 && secret.isNotEmpty()) id to secret else null
    }

    /** Returns (deviceId, secret), registering anonymously if needed. */
    suspend fun credentials(forceNew: Boolean = false): Pair<Int, String>? {
        // Store unavailable (transient Keystore failure): don't mint a throwaway
        // account we can't persist — degrade; the real account is intact on disk.
        val p = prefs ?: return null
        if (!forceNew) {
            val id = deviceId; val s = secret
            if (id != 0 && !s.isNullOrEmpty()) return id to s
        }
        val reg = runCatching { ApiClient.api.registerAnonymous(AnonRegReq()) }.getOrNull() ?: return null
        if (reg.ok && !reg.secret.isNullOrEmpty()) {
            p.edit().putInt("device_id", reg.deviceId).putString("secret", reg.secret).apply()
            return reg.deviceId to reg.secret!!
        }
        return null
    }
}
