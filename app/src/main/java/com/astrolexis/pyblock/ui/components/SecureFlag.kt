package com.astrolexis.pyblock.ui.components

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import java.util.concurrent.atomic.AtomicInteger

/**
 * Marks the current surface as secure: while a composable that calls [SecureFlag]
 * is on-screen, its window carries FLAG_SECURE — blocking screenshots, screen
 * recording, and the recents/app-switcher thumbnail. Use it on every surface that
 * renders a private key (WIF), a seed/mnemonic, a signing key, or the account
 * backup secret/QR.
 *
 * Handles both cases correctly:
 *  - Inside a Compose Dialog or ModalBottomSheet (which render in their OWN window
 *    that the Activity's FLAG_SECURE does NOT cover) → secures that dialog window.
 *  - Full-screen content → secures the Activity window, ref-counted so overlapping
 *    secure surfaces don't clear the flag out from under each other.
 *
 * Single-Activity app, so the process-global ref counter is safe.
 */
private val activityRefs = AtomicInteger(0)

@Composable
fun SecureFlag() {
    val view = LocalView.current
    val ctx = LocalContext.current
    DisposableEffect(view) {
        val dialogWindow = (view.parent as? DialogWindowProvider)?.window
        if (dialogWindow != null) {
            // Dialog / bottom-sheet own window — secure it directly; it's destroyed
            // with the surface, so no explicit clear is needed.
            dialogWindow.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
            onDispose { }
        } else {
            // NOT (ctx as? Activity): LocalizedApp wraps LocalContext, so walk to the Activity.
            val activityWindow = com.astrolexis.pyblock.util.findActivity(ctx)?.window
            if (activityRefs.incrementAndGet() == 1) {
                activityWindow?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            onDispose {
                if (activityRefs.decrementAndGet() <= 0) {
                    activityWindow?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }
    }
}
