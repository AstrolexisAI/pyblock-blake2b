package com.astrolexis.pyblock.data.store

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.security.SecureRandom

/** Node Defender identity: a stable anonymous handle (so re-submits update
 *  the player's best instead of stacking) + the persisted display name. */
object DefenderStore {
    private const val PREFS = "pyblock.defender"
    private lateinit var prefs: SharedPreferences

    var playerName by mutableStateOf("")
        private set

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        playerName = prefs.getString("name", "") ?: ""
    }

    val handle: String
        get() {
            prefs.getString("handle", null)?.let { return it }
            val rng = SecureRandom()
            val h = ByteArray(8).also { rng.nextBytes(it) }.joinToString("") { "%02x".format(it) }
            prefs.edit().putString("handle", h).apply()
            return h
        }

    fun setName(name: String) {
        playerName = name
        prefs.edit().putString("name", name).apply()
    }
}
