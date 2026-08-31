package com.astrolexis.pyblock.ui.components

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle

/**
 * Copy a SECRET (private-key WIF, account backup secret, seed) to the clipboard
 * flagged sensitive: on Android 13+ this keeps the value out of the paste-preview
 * toast and marks it for clipboard-history redaction. Use this — never the plain
 * Compose ClipboardManager — for anything that grants spend/account control.
 */
fun copySensitiveToClipboard(ctx: Context, text: String, label: String = "secret") {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    val clip = ClipData.newPlainText(label, text)
    if (Build.VERSION.SDK_INT >= 33) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    cm.setPrimaryClip(clip)
}
