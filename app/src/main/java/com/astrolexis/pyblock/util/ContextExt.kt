package com.astrolexis.pyblock.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/** Walk the ContextWrapper chain to the hosting Activity. Needed because
 *  LocalizedApp (i18n) provides a ContextThemeWrapper as LocalContext — so
 *  `LocalContext.current` is not the Activity directly, but the Activity is its
 *  base. Use this for startActivity / window flags (FLAG_SECURE) etc. */
fun findActivity(ctx: Context): Activity? {
    var c: Context? = ctx
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
