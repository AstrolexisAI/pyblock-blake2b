package com.astrolexis.pyblock.data.store

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Feature gate for Collaborative Send (PayJoin). **OFF by default.** Turning it on
 * exposes a flow that builds and broadcasts a REAL mainnet transaction, so it stays
 * hidden until validated. Port of iOS `PayJoinFeature.swift` (no App Store on
 * Android, so a plain Settings toggle drives it). Compose-observable.
 */
object PayJoinFeature {
    private const val PREFS = "pyblock.payjoin"
    private const val KEY = "enabled"
    private var prefs: android.content.SharedPreferences? = null

    var enabled by mutableStateOf(false)
        private set

    fun init(ctx: Context) {
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        enabled = p.getBoolean(KEY, false)
    }

    fun set(on: Boolean) {
        enabled = on
        prefs?.edit()?.putBoolean(KEY, on)?.apply()
    }
}
