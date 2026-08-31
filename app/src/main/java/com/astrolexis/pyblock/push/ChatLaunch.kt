package com.astrolexis.pyblock.push

import androidx.compose.runtime.mutableStateOf

/** Cross-layer signal: a tapped "new encrypted DM" push wants the chat inbox.
 *  MainActivity raises it from the notification intent; RootScaffold navigates
 *  to the chat tab and NostrChatScreen consumes it to open the inbox. */
object ChatLaunch {
    const val EXTRA_OPEN_CHAT = "pyblock_open_chat"

    val openInbox = mutableStateOf(false)

    fun request() { openInbox.value = true }

    /** Returns true once per request, resetting the signal. */
    fun consume(): Boolean {
        val v = openInbox.value
        openInbox.value = false
        return v
    }
}
