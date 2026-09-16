package com.astrolexis.pyblock.data.store

import android.content.Context
import android.content.res.Configuration

/** String lookup for code that has no Context at hand (spend errors, rental status labels,
 *  the address checker). Follows the in-app language in [LocaleStore], not the system one. */
object AppStrings {
    @Volatile private var app: Context? = null

    fun init(ctx: Context) { app = ctx.applicationContext }

    fun get(id: Int, vararg args: Any): String {
        val base = app ?: return ""
        val cfg = Configuration(base.resources.configuration).apply { setLocale(LocaleStore.locale(LocaleStore.lang.value)) }
        val res = base.createConfigurationContext(cfg).resources   // resources only — never used to start anything
        return if (args.isEmpty()) res.getString(id) else res.getString(id, *args)
    }
}
