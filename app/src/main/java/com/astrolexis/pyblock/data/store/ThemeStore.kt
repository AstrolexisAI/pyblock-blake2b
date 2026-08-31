package com.astrolexis.pyblock.data.store

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Selected accent palette, persisted + observable by Compose. */
object ThemeStore {
    private const val PREFS = "pyblock.theme"
    private const val KEY = "palette"
    private lateinit var prefs: SharedPreferences

    /** The user's EXPLICIT theme pick, or null when they haven't chosen one
     *  (then the classic Matrix green applies — see [effectivePaletteId]). */
    var paletteId by mutableStateOf<String?>(null)
        private set

    /** The palette actually applied at render time. An explicit user pick
     *  (fail-closed to Matrix if it's a premium palette without Whale), else
     *  the classic Matrix green. */
    val effectivePaletteId: String
        get() {
            val explicit = paletteId
            if (explicit != null) {
                val p = com.astrolexis.pyblock.ui.theme.PyPalettes.byId(explicit)
                if (p.free || EntitlementsStore.isWhale) return explicit
            }
            return "matrix"
        }

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        paletteId = prefs.getString(KEY, null)   // null → Matrix green
    }

    fun select(id: String) {
        paletteId = id
        prefs.edit().putString(KEY, id).apply()
    }

    /** Drop a premium pick that's active without Whale, falling back to
     *  Matrix (fail-closed). */
    fun enforce(premiumUnlocked: Boolean) {
        val explicit = paletteId ?: return
        if (!com.astrolexis.pyblock.ui.theme.PyPalettes.byId(explicit).free && !premiumUnlocked) {
            paletteId = null
            prefs.edit().remove(KEY).apply()
        }
    }
}
