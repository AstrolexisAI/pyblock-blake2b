package com.astrolexis.pyblock.data.wallet

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * User preference for the address type of NEW wallets ("+ NEW").
 *  - ASK    → prompt SegWit/Legacy each time (default).
 *  - SEGWIT → always create bc1q without asking.
 *  - LEGACY → always create 1… without asking.
 * The "don't ask again" checkbox sets SEGWIT/LEGACY; Settings can reset it back to ASK (reversible).
 */
object NewAddressPref {
    enum class Mode { ASK, SEGWIT, LEGACY }

    private const val PREF = "pyblockb.newaddr.v1"
    private const val KEY = "mode"
    val mode = MutableStateFlow(Mode.ASK)

    fun init(ctx: Context) {
        val v = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "ASK")
        mode.value = runCatching { Mode.valueOf(v ?: "ASK") }.getOrDefault(Mode.ASK)
    }

    fun set(ctx: Context, m: Mode) {
        mode.value = m
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, m.name).apply()
    }
}
