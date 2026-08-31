package com.astrolexis.pyblock.data.util

import android.content.Context
import android.content.Intent
import android.net.Uri

/** Opens a Lightning payload (BOLT11 invoice or LNURL) in the user's wallet via
 *  an implicit `lightning:` intent — but only after validating the payload
 *  shape, so a hostile/garbled server response can't drive an arbitrary intent. */
object LnUri {
    fun isValid(payload: String): Boolean {
        val t = payload.trim().lowercase()
        return t.startsWith("lnbc") || t.startsWith("lntb") ||
            t.startsWith("lnbcrt") || t.startsWith("lnurl")
    }

    fun open(ctx: Context, payload: String): Boolean {
        if (!isValid(payload)) return false
        return runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("lightning:${payload.trim()}"))
            val act = com.astrolexis.pyblock.util.findActivity(ctx)
            if (act != null) act.startActivity(intent)
            else ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }
}
