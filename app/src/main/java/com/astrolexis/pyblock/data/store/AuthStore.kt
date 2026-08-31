package com.astrolexis.pyblock.data.store

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** LNURL-auth session — persisted opaque token + pubkey. Observable. */
object AuthStore {
    private const val PREFS = "pyblock.auth"
    // Nullable + resetOnCorruption=false: never wipe the LNURL session on a
    // transient Keystore failure — degrade (appears logged-out this session, token
    // preserved on disk, retry next launch) instead of destroying it.
    private var prefs: SharedPreferences? = null

    var sessionToken by mutableStateOf<String?>(null)
        private set
    var pubkey by mutableStateOf<String?>(null)
        private set

    val isLoggedIn get() = !sessionToken.isNullOrEmpty()

    fun init(ctx: Context) {
        prefs = runCatching { SecurePrefs.open(ctx, "$PREFS.enc", PREFS, resetOnCorruption = false) }.getOrNull()
        sessionToken = prefs?.getString("token", null)
        pubkey = prefs?.getString("pubkey", null)
    }

    fun save(token: String, pubkey: String) {
        sessionToken = token
        this.pubkey = pubkey
        prefs?.edit()?.putString("token", token)?.putString("pubkey", pubkey)?.apply()
    }

    fun logout() {
        sessionToken = null
        pubkey = null
        prefs?.edit()?.clear()?.apply()
    }

    fun bearer(): String? = sessionToken?.let { "Bearer $it" }
}
