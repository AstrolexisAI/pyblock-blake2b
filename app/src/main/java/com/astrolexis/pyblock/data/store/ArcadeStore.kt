package com.astrolexis.pyblock.data.store

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Whether the loud arcade visuals (animated starfield, neon bloom on cards,
 *  breathing marquee, glyph glow on values) are on. A user preference, default
 *  ON — persisted + observable by Compose so a toggle re-skins the whole app live.
 *
 *  This is the "lobby volume" knob. It is ORTHOGONAL to `LocalSober` (see ArcadeKit):
 *  the vault (Wallet tab) is always calm regardless of this, so amounts stay
 *  trustworthy. Effective effects = `visualsEnabled && !LocalSober.current`. */
object ArcadeStore {
    private const val PREFS = "pyblock.arcade"
    private const val KEY = "visuals"
    private lateinit var prefs: SharedPreferences

    var visualsEnabled by mutableStateOf(true)
        private set

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        visualsEnabled = prefs.getBoolean(KEY, true)
    }

    fun setVisuals(v: Boolean) {
        visualsEnabled = v
        prefs.edit().putBoolean(KEY, v).apply()
    }
}
